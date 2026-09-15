package com.questtime.vr

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A small HTTP server so files can be sent to the headset from any browser.
 *
 * Getting QuickTime VR files onto a Quest is the worst part of using this app.
 * Android 11+ blocks MTP writes to an app's own folder, so `adb push` has been the
 * only reliable route - which means a cable, a terminal, and knowing the package
 * name. A page on the local network removes all three.
 *
 * It also checks what it receives. Half the value here is not the transfer but the
 * verdict: the archive that prompted this had files that were object movies, or
 * multi-node scenes, or - most often - classic Mac files whose header lives in a
 * resource fork that a browser upload cannot carry at all. Finding that out at the
 * moment of upload, in a sentence, beats finding out in the headset.
 *
 * Hand-rolled rather than pulling in a server library: the whole surface is two
 * routes and one multipart parse, and this project keeps its dependencies countable.
 */
/** One panorama the picker can see, as the page needs to show it. */
data class LibraryEntry(val name: String, val size: Long, val folder: String)

class UploadServer(
    private val targetDir: File,
    private val musicDir: File,
    /** Scratch space for staging an uploaded archive; never browsed by the picker. */
    private val cacheDir: File,
    private val onMusicChanged: () -> Unit,
    /** Whether a track ships inside the app; false on a fresh clone. */
    private val hasBundledTrack: () -> Boolean,
    /** Exactly what the picker can see, so the page cannot contradict the headset. */
    private val library: () -> List<LibraryEntry>,
) {

    private var socket: ServerSocket? = null
    private var running = false

    /** Where a browser should point, or null if the server is not up. */
    var url: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket(PORT)
            socket = s
            running = true
            val host = localAddress()
            url = if (host != null) "http://$host:$PORT" else null
            Log.i(TAG, "upload server listening on ${url ?: "port $PORT"}")
            thread(name = "qtvr-http", isDaemon = true) { accept(s) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start the upload server", e)
            running = false
            url = null
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        url = null
    }

    private fun accept(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed", e)
                return
            }
            // A browser opens several connections at once; keep each off the others.
            thread(isDaemon = true) {
                try {
                    client.use { handle(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "request failed", e)
                }
            }
        }
    }

    // -- HTTP ---------------------------------------------------------------

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] =
                line.substring(i + 1).trim()
        }

        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                respond(client.getOutputStream(), 200, "text/html; charset=utf-8",
                    page().toByteArray())

            method == "POST" && path == "/upload" ->
                handleUpload(client.getOutputStream(), input, headers)

            method == "POST" && path == "/music" ->
                handleMusic(client.getOutputStream(), input, headers)

            method == "GET" && path == "/music" ->
                respond(client.getOutputStream(), 200, "application/json",
                    musicInfo().toByteArray())

            method == "GET" && path == "/files" ->
                respond(client.getOutputStream(), 200, "application/json",
                    listing().toByteArray())

            else -> respond(client.getOutputStream(), 404, "text/plain", "not found".toByteArray())
        }
    }

    /** Read a multipart body, or respond with why it could not be read. */
    private fun readParts(
        out: OutputStream, input: InputStream, headers: Map<String, String>,
    ): List<Pair<String, ByteArray>>? {
        val contentType = headers["content-type"] ?: ""
        val boundary = contentType.substringAfter("boundary=", "").trim('"')
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (boundary.isEmpty() || length <= 0) {
            respond(out, 400, "application/json",
                """{"error":"expected a file upload"}""".toByteArray())
            return null
        }
        if (length > MAX_UPLOAD) {
            respond(out, 413, "application/json",
                """{"error":"that is larger than the ${MAX_UPLOAD / (1024 * 1024)} MB limit"}"""
                    .toByteArray())
            return null
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return multipart(body, boundary)
    }

    /**
     * Replace the background music. One track at a time, kept in the app's private
     * files so it never shows up in the picker.
     */
    private fun handleMusic(out: OutputStream, input: InputStream, headers: Map<String, String>) {
        val parts = readParts(out, input, headers) ?: return
        val (rawName, bytes) = parts.firstOrNull()
            ?: run {
                respond(out, 400, "application/json",
                    """{"error":"no file in that upload"}""".toByteArray())
                return
            }
        val name = safeName(rawName)
        try {
            musicDir.mkdirs()
            File(musicDir, Ambience.TRACK_FILE).writeBytes(bytes)
            File(musicDir, Ambience.TRACK_NAME_FILE).writeText(name)
        } catch (e: Exception) {
            Log.w(TAG, "could not store the music", e)
            respond(out, 500, "application/json",
                """{"error":"could not save it on the headset"}""".toByteArray())
            return
        }
        onMusicChanged()
        Log.i(TAG, "background music replaced with $name (${bytes.size / 1024} KB)")
        respond(out, 200, "application/json", musicInfo().toByteArray())
    }

    /** Name and length of the current track, for the page to show. */
    /**
     * There are three states here, not two, and the third is the one that matters
     * for anybody cloning this: no uploaded track *and* none bundled, because the
     * repository does not ship music. Reporting that as "using the built-in track"
     * would be a flat lie told by a page whose whole job is to tell you what is
     * actually on the headset.
     */
    private fun musicInfo(): String {
        val f = File(musicDir, Ambience.TRACK_FILE)
        if (!f.isFile || f.length() == 0L) {
            val state = if (hasBundledTrack()) "bundled" else "none"
            return """{"state":"$state","name":"","minutes":0,"note":""}"""
        }
        val name = runCatching { File(musicDir, Ambience.TRACK_NAME_FILE).readText() }
            .getOrDefault("uploaded track")
        val ms = durationMs(f)
        val minutes = (ms / 60000L).toInt()
        // The random drop-in makes a short loop obvious fast, so say so rather than
        // letting it be found out in the headset. Mind the two different zeros: a
        // track under a minute has a perfectly readable length and is simply far too
        // short, which is not the same as one whose duration could not be read at
        // all. Reporting the first as the second sends someone off to re-encode a
        // file that was fine.
        val note = when {
            ms <= 0L -> "its length could not be read"
            ms < 20L * 60_000L -> "shorter than the 30-60 minutes this works best with"
            else -> ""
        }
        return """{"state":"uploaded","name":${json(name)},"minutes":$minutes,""" +
            """"note":${json(note)}}"""
    }

    private fun durationMs(f: File): Long = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        r.release()
        d
    }.getOrDefault(0L)

    private fun handleUpload(out: OutputStream, input: InputStream, headers: Map<String, String>) {
        val parts = readParts(out, input, headers) ?: return

        // AppleDouble sidecars can arrive loose, not only inside a zip. A resource
        // fork is a real fork on an HFS+ or APFS volume and there is nothing beside
        // the file to upload - but the moment those files touch FAT, exFAT or an SMB
        // share, macOS writes the fork out as a separate `._Name`. Old archives have
        // usually been round-tripped through exactly that kind of media, so a plain
        // multi-select often carries both halves without the sender realising.
        val sidecars = HashMap<String, ByteArray>()
        val files = ArrayList<Pair<String, ByteArray>>()
        for ((raw, bytes) in parts) {
            // Checked on the raw name: safeName strips the leading dot, which would
            // turn the marker we are looking for into an ordinary filename.
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            if (base.startsWith("._")) sidecars[safeName(base.substring(2))] = bytes
            else files.add(raw to bytes)
        }

        val results = ArrayList<String>()
        for ((name, bytes) in files) {
            if (isArchive(name)) {
                results.addAll(expandArchive(name, bytes))
                continue
            }
            val moov = if (sidecars.isEmpty() || AppleZip.hasMoov(bytes)) null else
                sidecars[safeName(name)]
                    ?.let { AppleZip.resourceForkFromAppleDouble(it) }
                    ?.let { AppleZip.findResource(it, "moov") }
            if (moov != null) {
                results.add(saveAndCheck(name, bytes + moov, rescued = true))
            } else {
                results.add(saveAndCheck(name, bytes))
            }
        }
        // A sidecar with nothing to attach to is not worth a row of its own - it is
        // invisible in Finder and the sender did not knowingly send it.
        respond(out, 200, "application/json",
            results.joinToString(",", "[", "]").toByteArray())
    }

    private fun isArchive(rawName: String): Boolean =
        safeName(rawName).lowercase().endsWith(".zip")

    /**
     * Unpack a Mac archive, recovering the resource forks a browser could not carry.
     *
     * This is the way past the wall the footer used to just apologise for. A classic
     * Mac file keeps its `moov` in a resource fork; an upload drops it and the file
     * arrives headerless. Finder's Compress, though, stores that fork as an
     * AppleDouble sidecar, so an archive carries everything a loose upload loses -
     * 13 of the 27 files in the archive this was built against.
     *
     * Members are flattened to the top level: the picker browses the app's own folder
     * and one level of sub-folder, and a zip's internal shape is the sender's
     * filing, not something worth reproducing on the headset.
     *
     * Each member still goes through [Qtvr.inspect], exactly as a loose upload does.
     * Arriving in an archive is not a reason to trust a file.
     */
    private fun expandArchive(rawName: String, bytes: ByteArray): List<String> {
        val archive = safeName(rawName)
        val results = ArrayList<String>()
        // ZipFile needs random access, so the archive has to touch the disk. Cache
        // rather than targetDir: a half-written zip must never appear in the picker.
        val temp = runCatching {
            cacheDir.mkdirs()
            File.createTempFile("upload", ".zip", cacheDir).also { it.writeBytes(bytes) }
        }.getOrElse {
            Log.w(TAG, "could not stage $archive", it)
            return listOf(failure(archive, "That archive could not be unpacked here.", archive))
        }
        try {
            AppleZip.extract(temp, { member ->
                results.add(saveAndCheck(member.name, member.bytes, from = archive,
                    rescued = member.rescued))
            }, { lost ->
                // A loose upload of this file would say "flatten it first". That
                // advice is already spent - it came in an archive and there was no
                // sidecar - so say what is actually true instead.
                results.add(failure(lost,
                    "Its header is missing and the archive carries no resource fork for " +
                        "it. Compress the originals with Finder rather than the zip " +
                        "command, which drops forks.", archive))
            })
        } catch (e: Exception) {
            Log.w(TAG, "could not read $archive", e)
            results.add(failure(archive, "That does not read as a zip archive.", archive))
        } finally {
            temp.delete()
        }
        if (results.isEmpty()) {
            results.add(failure(archive, "There was nothing in that archive to open.", archive))
        }
        return results
    }

    /** A refusal for something that never reached [Qtvr.inspect]. */
    private fun failure(name: String, detail: String, from: String): String = buildString {
        append("{")
        append("\"name\":").append(json(name)).append(",")
        append("\"savedAs\":\"\",\"saved\":false,\"ok\":false,\"rescued\":false,")
        append("\"from\":").append(json(from)).append(",")
        append("\"summary\":").append(json("Could not be recovered")).append(",")
        append("\"detail\":").append(json(detail)).append(",")
        append("\"size\":0")
        append("}")
    }

    /** Store the upload, then say plainly whether the app can open it. */
    private fun saveAndCheck(
        rawName: String,
        bytes: ByteArray,
        from: String = "",
        rescued: Boolean = false,
    ): String {
        val name = safeName(rawName)
        val verdict = Qtvr.inspect(bytes)
        var saved = false
        var savedAs = name
        if (verdict.worthKeeping) {
            runCatching {
                targetDir.mkdirs()
                // QuickTime VR files often arrive with no extension at all, because
                // classic Mac files carried type codes instead. The picker filters on
                // the name, so give them one.
                if (!FileList.isPanorama(savedAs)) savedAs = "$savedAs.mov"
                File(targetDir, savedAs).writeBytes(bytes)
                saved = true
            }.onFailure { Log.w(TAG, "could not save $name", it) }
        }
        return buildString {
            append("{")
            append("\"name\":").append(json(name)).append(",")
            append("\"savedAs\":").append(json(if (saved) savedAs else "")).append(",")
            append("\"saved\":").append(saved).append(",")
            append("\"ok\":").append(verdict.opens).append(",")
            append("\"rescued\":").append(rescued).append(",")
            append("\"from\":").append(json(from)).append(",")
            append("\"summary\":").append(json(verdict.summary)).append(",")
            append("\"detail\":").append(json(verdict.detail)).append(",")
            append("\"size\":").append(bytes.size)
            append("}")
        }
    }

    private fun listing(): String {
        val entries = runCatching { library() }.getOrDefault(emptyList())
        val rows = entries.joinToString(",", "[", "]") {
            """{"name":${json(it.name)},"size":${it.size},"folder":${json(it.folder)}}"""
        }
        return """{"count":${entries.size},"files":$rows}"""
    }

    // -- plumbing -----------------------------------------------------------

    /**
     * Split a multipart body into (filename, content). Deliberately minimal: it
     * handles what a browser's file input sends and nothing more.
     */
    private fun multipart(body: ByteArray, boundary: String): List<Pair<String, ByteArray>> {
        val marker = "--$boundary".toByteArray()
        val out = ArrayList<Pair<String, ByteArray>>()
        var pos = indexOf(body, marker, 0)
        while (pos >= 0) {
            var start = pos + marker.size
            if (start + 2 <= body.size && body[start] == '-'.code.toByte() &&
                body[start + 1] == '-'.code.toByte()
            ) break                                         // closing boundary
            if (start + 2 <= body.size) start += 2          // CRLF after the boundary

            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), start)
            if (headerEnd < 0) break
            val head = String(body, start, headerEnd - start, Charsets.ISO_8859_1)
            val next = indexOf(body, marker, headerEnd)
            if (next < 0) break

            val contentStart = headerEnd + 4
            var contentEnd = next
            if (contentEnd - 2 >= contentStart) contentEnd -= 2   // trailing CRLF
            // The header is read as ISO-8859-1 so the byte offsets above stay exact,
            // which leaves the filename byte-for-char. Browsers send it as UTF-8, so
            // it has to be decoded back or every accent and symbol arrives mangled.
            val filename = Regex("filename=\"([^\"]*)\"").find(head)?.groupValues?.get(1)
                ?.let { AppleZip.decodeUtf8Strict(it) }
            if (!filename.isNullOrEmpty()) {
                out.add(filename to body.copyOfRange(contentStart, contentEnd))
            }
            pos = next
        }
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Keep the recognisable part of the name and nothing that could escape the folder. */
    /**
     * A filename safe to write, keeping as much of the sender's as possible.
     *
     * This used to be an allowlist of letters, digits and a little punctuation, which
     * is safe but quietly lossy: `isLetterOrDigit` is Unicode-aware and passes `é` and
     * CJK, yet drops every symbol, so `Green Spiky Land (KPT Bryce™)` arrived as
     * `Green Spiky Land (KPT Bryce)`. These names are the only label a panorama has -
     * the picker shows nothing else - so mangling one to avoid a danger it never posed
     * is the wrong trade.
     *
     * What actually has to go is anything that could escape this directory or upset
     * the filesystem: path separators, control characters, and the two names that mean
     * "somewhere else". Everything printable is kept.
     */
    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base
            .filter { it.code >= 0x20 && it.code != 0x7F && it != '/' && it != '\\' }
            .trim()
            // A leading dot hides the file from the picker's own listing; it is never
            // what someone meant by sending it.
            .trimStart('.')
            .trim()
        return cleaned.ifEmpty { "upload" }.take(120)
    }

    private fun json(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
        }
        return b.append("\"").toString()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (c == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            buf.write(c)
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"
            413 -> "Payload Too Large"; else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    internal fun page(): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>QuestTime VR — send files</title>
<style>
  /* Mac OS 9 "Platinum", which is what these files were made on.

     The type is the period's own, and checked rather than assumed: Charcoal (the
     system font from 8.5) and Chicago (before it) are BOTH gone from modern macOS,
     so they sit at the front of the stack as a courtesy to anyone still holding
     them and fall through in practice. Geneva and Monaco do still ship, and they
     are what actually renders. Nothing is downloaded - the headset's server has no
     internet and should not need any. Off a Mac the stack lands on Tahoma or
     Verdana, which are at least of the era.

     Deliberately light-only: Platinum had no dark mode, and faking one would be a
     costume rather than the thing. color-scheme says so, so the browser agrees. */
  :root{color-scheme:light;
        --ink:#000;--muted:#55595E;--face:#DDDDDD;--well:#FFFFFF;--desk:#8A8F99;
        --hi:#FFFFFF;--lo:#7F838A;--edge:#000000;
        --accent:#33619E;--ok:#1B5E20;--bad:#8C1F16;--warn:#7A5A00}
  *{box-sizing:border-box}
  body{margin:0;background:var(--desk);color:var(--ink);
       font:14px/1.5 Charcoal,Geneva,"Lucida Grande",Tahoma,Verdana,sans-serif}
  .wrap{max-width:660px;margin:0 auto;padding:28px 16px 56px}
  /* A Platinum window: 1px black frame, white top-left bevel, grey bottom-right. */
  .win{background:var(--face);border:1px solid var(--edge);
       box-shadow:inset 1px 1px 0 var(--hi),inset -1px -1px 0 var(--lo),2px 2px 0 rgba(0,0,0,.35)}
  /* The pinstriped title bar. Six 1px stripes is what the real one used. */
  .bar{border-bottom:1px solid var(--edge);padding:4px 8px;display:flex;
       align-items:center;gap:8px;
       background:repeating-linear-gradient(to bottom,#E6E6E6 0 1px,#C8C8C8 1px 2px)}
  /* The title sits centred with the stripes running either side of it, which is
     what makes a Platinum bar read as one. The close box stays hard left. */
  .bar .t{font-weight:bold;font-size:13px;background:var(--face);padding:0 8px;
          margin:0 auto;position:relative;left:-9px}
  .bar .box{width:11px;height:11px;background:var(--face);flex:0 0 auto;
            border:1px solid var(--edge);box-shadow:inset 1px 1px 0 var(--hi),
            inset -1px -1px 0 var(--lo)}
  .pad{padding:14px}
  h1{font:bold 20px/1.2 Charcoal,Geneva,sans-serif;margin:0 0 4px}
  p.sub{color:var(--muted);margin:0 0 14px;font-size:13px}
  h2{font:bold 14px/1.2 Charcoal,Geneva,sans-serif;margin:0;display:inline}
  /* A well: the inverse bevel, for anything you drop into or read out of. */
  .drop{background:var(--well);border:1px solid var(--lo);
        box-shadow:inset 1px 1px 0 rgba(0,0,0,.18);
        padding:26px 16px;text-align:center;cursor:pointer}
  .drop.over{background:#EDF3FB;border-color:var(--accent)}
  .drop b{display:block;font-size:14px;margin-bottom:5px}
  .drop span{color:var(--muted);font-size:12px;line-height:1.45}
  input[type=file]{display:none}
  ul{list-style:none;margin:10px 0 0;padding:0}
  li{border-top:1px solid #C4C4C4;padding:7px 2px;display:flex;gap:9px;
     align-items:flex-start;font-size:13px}
  li:first-child{border-top:0}
  li .mark{flex:0 0 auto;font-weight:bold;font-family:Monaco,"Andale Mono",monospace;
           font-size:11px;padding-top:1px}
  li .body{flex:1 1 auto;min-width:0}
  li .name{font-weight:bold;word-break:break-all}
  li .msg{color:var(--muted);font-size:12px;margin-top:1px;line-height:1.4}
  .ok .mark{color:var(--ok)} .bad .mark{color:var(--bad)} .warn .mark{color:var(--warn)}
  /* The library, condensed: one line each, name and size on the same row. */
  #lib li{padding:3px 2px;border-top:0;gap:8px;align-items:baseline}
  #lib .name{font-weight:normal}
  #lib .msg{margin-top:0;font-size:12px;white-space:nowrap}
  #lib .body{display:flex;gap:10px;justify-content:space-between;align-items:baseline}
  .shelf{max-height:210px;overflow:auto;background:var(--well);
         border:1px solid var(--lo);box-shadow:inset 1px 1px 0 rgba(0,0,0,.18);
         padding:6px 8px;margin-top:8px}
  /* Disclosure: the triangle is the control, exactly as it was. */
  details>summary{list-style:none;cursor:pointer;display:flex;align-items:center;gap:6px}
  details>summary::-webkit-details-marker{display:none}
  details>summary::before{content:"";width:0;height:0;
        border-left:7px solid var(--ink);border-top:5px solid transparent;
        border-bottom:5px solid transparent;transition:transform .12s;flex:0 0 auto}
  details[open]>summary::before{transform:rotate(90deg)}
  summary .count{color:var(--muted);font-size:12px;font-weight:normal}
  hr{border:0;border-top:1px solid var(--lo);border-bottom:1px solid var(--hi);margin:16px 0}
  footer{color:var(--muted);font-size:12px;line-height:1.5}
  code{font-family:Monaco,"Andale Mono",monospace;font-size:11px;
       background:var(--well);border:1px solid #C4C4C4;padding:0 3px}
</style></head><body><div class="wrap">
<div class="win">
<div class="bar"><span class="box"></span><span class="t">QuestTime VR</span></div>
<div class="pad">
<h1>Send files to the headset</h1>
<p class="sub">Drop QuickTime VR files here and they go straight to the Quest.</p>
<div id="drop" class="drop"><b>Choose files, or drop them here</b><span>Files, a whole folder, or a zip — all checked on arrival, and you are told if one will not open and why.<br>Classic Mac files missing their header are rebuilt where that can be done safely; a Finder zip restores the real one.</span></div>
<input id="pick" type="file" multiple>
<ul id="out"></ul>

<hr>
<details id="libwrap">
  <summary><h2>On the headset</h2><span class="count" id="libcount">checking\u2026</span></summary>
  <div class="shelf"><ul id="lib"></ul></div>
</details>

<hr>
<details id="musicwrap">
  <summary><h2>Background Music</h2><span class="count" id="track">checking\u2026</span></summary>
  <div id="mdrop" class="drop" style="margin-top:8px"><b>Choose a track, or drop one here</b><span>A full mix of roughly 30 minutes to an hour suits this best. Each panorama drops in at a random point, so a short loop gives itself away quickly.</span></div>
  <input id="mpick" type="file" accept="audio/*">
</details>

<hr>
<footer>
Files land in the app's own folder and appear in the picker straight away — tap
<b>Rescan</b> if it is already open.<br><br>
Many classic Mac QuickTime files keep their header in a <b>resource fork</b>, and no
browser upload can carry one — sent loose, those arrive headerless and are refused.
<b>Send them in a zip instead.</b> Select them in Finder, right-click, Compress, and
drop the archive here: the fork travels inside it and is put back on arrival.<br><br>
It has to be Finder's Compress (or <code>ditto</code>). The <code>zip</code> command
drops resource forks, so an archive made that way is no better than sending the files
loose. <code>reference/flatten.py</code> still works if you would rather do it yourself.
</footer>
</div></div></div>
<script>
const drop=document.getElementById('drop'),pick=document.getElementById('pick'),out=document.getElementById('out');
drop.onclick=()=>pick.click();
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};
drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');gather(e.dataTransfer).then(send)};
/**
 * Everything dropped, with folders walked.
 *
 * dataTransfer.files holds only loose files - drop a folder and it is empty, which
 * looked exactly like nothing happening. webkitGetAsEntry gives the tree instead, so
 * a folder of panoramas can be dragged straight on. The entries have to be taken
 * before the first await or the browser discards them.
 */
async function gather(dt){
  const entries=[...(dt.items||[])].map(i=>i.webkitGetAsEntry&&i.webkitGetAsEntry()).filter(Boolean);
  if(!entries.length) return [...dt.files];
  const out=[];
  const readDir=d=>new Promise(res=>{const r=d.createReader(),all=[];
    const step=()=>r.readEntries(b=>{if(!b.length){res(all);return}all.push(...b);step()});step()});
  const walk=async e=>{
    if(e.isFile){await new Promise(res=>e.file(f=>{out.push(f);res()},res));return}
    if(e.isDirectory){for(const c of await readDir(e)) await walk(c)}
  };
  for(const e of entries) await walk(e);
  return out;
}
pick.onchange=()=>send(pick.files);
function build(cls,mark,name,msg){const li=document.createElement('li');li.className=cls;
  li.innerHTML='<div class="mark">'+mark+'</div><div class="body"><div class="name"></div><div class="msg"></div></div>';
  li.querySelector('.name').textContent=name;li.querySelector('.msg').textContent=msg;
  return li}
function row(cls,mark,name,msg){const li=build(cls,mark,name,msg);out.prepend(li);return li}
/**
 * One member of an archive, placed directly below [after] rather than prepended:
 * the list grows upwards, so prepending members would stack them above their own
 * summary and in reverse. Returns the new row, to anchor the next one.
 */
function member(v,after){
  const li=build(v.ok?'ok':'bad',v.ok?'ok':'x',v.name,
    v.summary+(v.detail?' — '+v.detail:'')+
    (v.rescued&&v.saved?'  (header recovered from its resource fork)':'')+
    (v.saved&&v.savedAs!==v.name?'  (saved as '+v.savedAs+')':''));
  li.style.marginLeft='22px';
  after.insertAdjacentElement('afterend',li);
  return li}
async function send(files){
  for(const f of files){
    const li=row('warn','...',f.name,'sending '+(f.size/1048576).toFixed(1)+' MB');
    const fd=new FormData();fd.append('file',f);
    try{
      const r=await fetch('/upload',{method:'POST',body:fd});
      const j=await r.json();
      if(!j||!j.length){li.className='bad';li.querySelector('.mark').textContent='x';
             li.querySelector('.msg').textContent='no response for this file';continue}
      // An archive answers with one verdict per member, so the row that was
      // standing in for the upload becomes a summary and the members list below it.
      if(j.length>1||j[0].from){
        const ok=j.filter(v=>v.ok).length, saved=j.filter(v=>v.saved).length;
        const back=j.filter(v=>v.rescued&&v.saved).length;
        li.className=ok?'ok':'bad';
        li.querySelector('.mark').textContent=ok?'ok':'x';
        li.querySelector('.msg').textContent=j.length+' in the archive · '+saved+
          ' kept'+(back?', '+back+' recovered from a resource fork':'')+
          (j.length-ok?' · '+(j.length-ok)+' refused':'');
        let at=li; for(const v of j) at=member(v,at);
      }else{
        const v=j[0];
        li.className=v.ok?'ok':'bad';
        li.querySelector('.mark').textContent=v.ok?'ok':'x';
        li.querySelector('.msg').textContent=v.summary+(v.detail?' — '+v.detail:'')+
          (v.saved&&v.savedAs!==v.name?'  (saved as '+v.savedAs+')':'');
      }
    }catch(err){li.className='bad';li.querySelector('.mark').textContent='x';
      li.querySelector('.msg').textContent='upload failed: '+err}
  }
  showLibrary();
}
const mdrop=document.getElementById('mdrop'),mpick=document.getElementById('mpick'),track=document.getElementById('track');
mdrop.onclick=()=>mpick.click();
mdrop.ondragover=e=>{e.preventDefault();mdrop.classList.add('over')};
mdrop.ondragleave=()=>mdrop.classList.remove('over');
mdrop.ondrop=e=>{e.preventDefault();mdrop.classList.remove('over');sendMusic(e.dataTransfer.files[0])};
mpick.onchange=()=>sendMusic(mpick.files[0]);
function describe(j){
  if(j.state==='uploaded')return 'Now playing: '+j.name+(j.minutes?'  \u00b7  '+j.minutes+' min':'')+(j.note?'  \u2014  '+j.note:'');
  if(j.state==='bundled')return 'Using the track bundled with the app. Send one to replace it.';
  return 'No music yet \u2014 none is bundled with the app. Send a track and it plays while you are inside a panorama.';
}
async function showTrack(){try{track.textContent=describe(await (await fetch('/music')).json())}catch(e){track.textContent='\u2014'}}
async function sendMusic(f){
  if(!f)return;
  track.textContent='sending '+f.name+' ('+(f.size/1048576).toFixed(1)+' MB)\u2026';
  const fd=new FormData();fd.append('file',f);
  try{const j=await (await fetch('/music',{method:'POST',body:fd})).json();
    track.textContent=j.error?('could not use that: '+j.error):describe(j);
  }catch(e){track.textContent='upload failed: '+e}
}
showTrack();
async function showLibrary(){
  try{
    const j=await (await fetch('/files')).json();
    document.getElementById('libcount').textContent =
      j.count?(j.count+' file'+(j.count===1?'':'s')+' the viewer can reach, across every folder it searches'):'Nothing on the headset yet. Send something above.';
    const lib=document.getElementById('lib');lib.innerHTML='';
    for(const f of j.files){
      const li=document.createElement('li');li.className='ok';
      li.innerHTML='<div class="mark">\u00b7</div><div class="body"><div class="name"></div><div class="msg"></div></div>';
      li.querySelector('.name').textContent=f.name;
      li.querySelector('.msg').textContent=(f.size/1048576).toFixed(1)+' MB  \u00b7  '+(f.folder||'top level');
      lib.appendChild(li);
    }
  }catch(e){document.getElementById('libcount').textContent='\u2014'}
}
showLibrary();
</script></body></html>
""".trimIndent()

    companion object {
        private const val TAG = VrActivity.TAG
        const val PORT = 8080

        /** Generous for a panorama, small enough that a stray drop cannot fill the headset. */
        private const val MAX_UPLOAD = 512 * 1024 * 1024
    }
}
