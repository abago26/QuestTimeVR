package com.questtime.vr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Recovering resource forks from a Mac-made .zip.
 *
 * A browser upload cannot carry a resource fork, so the classic Mac files that keep
 * their `moov` there arrive as headerless media and are refused — 13 of the 27 files
 * in a real archive, failing for a reason that has nothing to do with the files.
 *
 * When a Mac user right-click Compresses a selection, the resource fork travels as an
 * AppleDouble sidecar at `__MACOSX/._Name`. That sidecar is byte-identical to
 * `path/..namedfork/rsrc`, so the same resource-map walk reads it unchanged and the
 * reconstructed file is byte-identical to what `reference/flatten.py` writes — verified
 * by SHA-256 over all 13.
 *
 * Ported from `reference/applezip.py`. Port from there rather than re-deriving; it is
 * the implementation the 5/27 -> 16/27 measurement came from.
 */
object AppleZip {

    private const val APPLEDOUBLE_MAGIC = 0x00051607
    private const val ENTRY_RESOURCE_FORK = 2

    /** One file recovered from the archive, ready for the [Qtvr.inspect] gate. */
    class Member(
        /** Basename as it should reach the picker, always ending `.mov`. */
        val name: String,
        val bytes: ByteArray,
        /** True when the moov came from a sidecar rather than the data fork. */
        val rescued: Boolean,
    )

    /**
     * Walk an archive, handing each openable candidate to [out] one at a time.
     *
     * One member is held in memory at a time. The sidecars are read first and kept —
     * they are resource forks, a few KB each — because a member cannot be reconstructed
     * until its sidecar is known and zip entry order puts `__MACOSX/` wherever it likes.
     *
     * Members that already carry a moov are passed through untouched. Members with
     * neither a moov nor a usable sidecar are reported through [lost] and not stored:
     * that is a genuine dead end for browser upload and the page has to say so rather
     * than letting it vanish into a count.
     */
    fun extract(archive: File, out: (Member) -> Unit, lost: (String) -> Unit) {
        // ISO-8859-1 maps every byte to the char of the same value, so entry names come
        // back byte-preserving and entryName() can decode them itself. See below.
        ZipFile(archive, Charsets.ISO_8859_1).use { zip ->
            val sidecars = HashMap<String, ByteArray>()
            val members = ArrayList<ZipEntry>()

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val full = entryName(entry)
                val name = full.substringAfterLast('/')
                when {
                    full.startsWith("__MACOSX/") ->
                        if (name.startsWith("._")) {
                            sidecars[name.substring(2)] = zip.getInputStream(entry).use { it.readBytes() }
                        }
                    // Dotfiles are .DS_Store and friends, never panoramas.
                    !name.startsWith(".") -> members.add(entry)
                }
            }

            for (entry in members) {
                val name = entryName(entry).substringAfterLast('/')
                val data = zip.getInputStream(entry).use { it.readBytes() }

                if (hasMoov(data)) {
                    out(Member(movName(name), data, rescued = false))
                    continue
                }
                val moov = sidecars[name]
                    ?.let { resourceForkFromAppleDouble(it) }
                    ?.let { findResource(it, "moov") }
                if (moov == null) {
                    lost(name)
                    continue
                }
                // No offset rewriting: chunk offsets in a dual-fork movie already address
                // the data fork from its start, so appending leaves every byte where it was.
                out(Member(movName(name), data + moov, rescued = true))
            }
        }
    }

    /**
     * The member's real name, working around Finder's missing UTF-8 flag.
     *
     * `ditto` writes filenames as UTF-8 without setting the general-purpose flag bit
     * that says so. Reading the archive as ISO-8859-1 keeps the raw bytes intact; a
     * strict UTF-8 decode then recovers the original name, and a failure means the
     * archive really was cp437, in which case the byte-for-char reading is what we keep.
     *
     * Without this, `Green Spiky Land (KPT Bryce™)` reaches the picker as `BryceÔÇó`.
     */
    internal fun entryName(entry: ZipEntry): String = decodeUtf8Strict(entry.name)

    /**
     * Re-read a string that was decoded byte-for-char as the UTF-8 it probably is.
     *
     * Two callers, same underlying problem: a name arrives as bytes with no reliable
     * declaration of its encoding, and the only safe way to hold those bytes in a
     * String is ISO-8859-1, which maps every byte to the char of the same value. That
     * keeps them intact but renders `™` as `â¢` until this puts it back.
     *
     * [UploadServer] needs it for the filename in a multipart header, which browsers
     * send as UTF-8 while the header itself must be read byte-preserving to find the
     * part boundaries.
     */
    internal fun decodeUtf8Strict(raw: String): String {
        val bytes = raw.toByteArray(Charsets.ISO_8859_1)
        // An archive that *does* set the flag bit is decoded as UTF-8 by ZipFile before
        // we ever see it, whatever charset we asked for. Re-encoding such a name to
        // ISO-8859-1 would replace every character above U+00FF with '?' - turning a
        // correct name into a mangled one, the exact failure this function exists to
        // prevent. A name that does not survive the round trip was not byte-for-char,
        // so it was already decoded and is left alone.
        if (bytes.toString(Charsets.ISO_8859_1) != raw) return raw
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // Not UTF-8, so the archive really was written in a DOS code page. The
            // reference implementation decodes cp437 here; Android has no cp437 charset,
            // so the bytes are kept as ISO-8859-1 and a genuinely cp437 name renders
            // differently from `applezip.py`. Only the high half differs, and a Mac
            // archive - which is every archive this path exists for - never takes it.
            raw
        }
    }

    /**
     * Many of these files have no extension at all, because classic Mac files carried
     * type/creator codes instead. The picker filters on `.mov`, so a name without one
     * is invisible to it.
     */
    private fun movName(name: String): String {
        // Control characters can only arrive from the fallback below, but a filename
        // carrying one is worth neutralising before it reaches the filesystem.
        val safe = name.map { if (it == '/' || it.code < 0x20) '-' else it }
            .joinToString("").trim()
        return if (safe.lowercase().endsWith(".mov")) safe else "$safe.mov"
    }

    /**
     * The bytes of a panorama file, with its header put back if one came with it.
     *
     * The other way a resource fork travels. Inside a zip it is a member under
     * `__MACOSX/`; copied loose - onto a FAT or exFAT stick, over an SMB share, or
     * dragged into a headset - macOS writes it as a `._Name` file beside the data
     * fork. Most old archives have moved that way at least once, so the two files
     * are very often both there, and the person who copied them has no idea the
     * second one exists or matters.
     *
     * The upload page has paired them since zip import landed. Sideloading did not,
     * so the same folder copied over a cable produced "not a QuickTime file" for
     * every dual-fork movie in it while the browser accepted them happily.
     *
     * A file that already carries its own moov is returned untouched: a flattened
     * file has one, and appending a second header to it would be actively wrong.
     */
    fun readPaired(f: File): ByteArray {
        val bytes = f.readBytes()
        if (hasMoov(bytes)) return bytes
        val side = File(f.parentFile, "._" + f.name)
        if (!side.isFile || side.length() > MAX_SIDECAR) return bytes
        val moov = runCatching { resourceForkFromAppleDouble(side.readBytes()) }
            .getOrNull()?.let { findResource(it, "moov") } ?: return bytes
        return bytes + moov
    }

    /**
     * A sidecar is a header and some Finder metadata, never media. The real ones
     * here run to about 16 KB; the cap is generous and only there so a mis-named
     * large file cannot be read into memory for nothing.
     */
    private const val MAX_SIDECAR = 8 * 1024 * 1024

    /**
     * The resource fork inside an AppleDouble sidecar, or null.
     *
     * Magic 0x00051607, an entry count at offset 24, then that many 12-byte
     * (id, offset, length) entries from offset 26. Entry id 2 is the resource fork.
     */
    internal fun resourceForkFromAppleDouble(blob: ByteArray): ByteArray? {
        if (blob.size < 26) return null
        val b = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
        if (b.getInt(0) != APPLEDOUBLE_MAGIC) return null
        val count = b.getShort(24).toInt() and 0xFFFF
        for (i in 0 until count) {
            val base = 26 + i * 12
            if (base + 12 > blob.size) return null
            val id = b.getInt(base).toLong() and 0xFFFFFFFFL
            val off = b.getInt(base + 4).toLong() and 0xFFFFFFFFL
            val len = b.getInt(base + 8).toLong() and 0xFFFFFFFFL
            if (id == ENTRY_RESOURCE_FORK.toLong() && len > 0 && off + len <= blob.size) {
                return blob.copyOfRange(off.toInt(), (off + len).toInt())
            }
        }
        return null
    }

    /**
     * The bytes of the first resource of type [want], or null.
     *
     * Classic resource-map layout: a data offset and map offset in the first 8 bytes,
     * the type list offset at map+24, then per type an entry count and a reference-list
     * offset, and per reference a 3-byte offset into the data area at ref+5.
     */
    internal fun findResource(rf: ByteArray, want: String): ByteArray? {
        if (rf.size < 16) return null
        val b = ByteBuffer.wrap(rf).order(ByteOrder.BIG_ENDIAN)
        val dataOff = b.getInt(0).toLong() and 0xFFFFFFFFL
        val mapOff = (b.getInt(4).toLong() and 0xFFFFFFFFL).toInt()
        if (mapOff < 0 || mapOff + 30 > rf.size) return null

        val typeListOff = b.getShort(mapOff + 24).toInt() and 0xFFFF
        val tl = mapOff + typeListOff
        if (tl + 2 > rf.size) return null

        val nTypes = (b.getShort(tl).toInt() and 0xFFFF) + 1
        val wanted = want.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until nTypes) {
            val e = tl + 2 + i * 8
            if (e + 8 > rf.size) break
            if (!regionMatches(rf, e, wanted)) continue

            val nRes = (b.getShort(e + 4).toInt() and 0xFFFF) + 1
            val refOff = b.getShort(e + 6).toInt() and 0xFFFF
            for (r in 0 until nRes) {
                val ref = tl + refOff + r * 12
                if (ref + 12 > rf.size) break
                val off = ((rf[ref + 5].toInt() and 0xFF) shl 16) or
                    ((rf[ref + 6].toInt() and 0xFF) shl 8) or
                    (rf[ref + 7].toInt() and 0xFF)
                val start = dataOff + off
                if (start + 4 > rf.size) continue
                val length = b.getInt(start.toInt()).toLong() and 0xFFFFFFFFL
                if (start + 4 + length > rf.size) continue
                return rf.copyOfRange((start + 4).toInt(), (start + 4 + length).toInt())
            }
        }
        return null
    }

    private fun regionMatches(data: ByteArray, at: Int, want: ByteArray): Boolean {
        for (i in want.indices) if (data[at + i] != want[i]) return false
        return true
    }

    /**
     * Walk top-level atoms looking for a moov, without a full parse.
     *
     * Cheap enough to run on every member, and the only question being asked here is
     * whether the file needs rescuing at all — [Qtvr.inspect] does the real checking.
     */
    internal fun hasMoov(data: ByteArray): Boolean {
        val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        var pos = 0L
        while (pos + 8 <= data.size) {
            var size = b.getInt(pos.toInt()).toLong() and 0xFFFFFFFFL
            val typ = data.copyOfRange(pos.toInt() + 4, pos.toInt() + 8)
                .toString(Charsets.ISO_8859_1)
            if (size == 1L) {
                if (pos + 16 > data.size) return false
                size = b.getLong(pos.toInt() + 8)
            } else if (size == 0L) {
                size = data.size - pos
            }
            if (typ == "moov") return true
            if (size < 8) return false
            pos += size
        }
        return false
    }
}
