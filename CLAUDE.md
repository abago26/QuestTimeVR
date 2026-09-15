# QuestTime VR — working notes

A sideloadable Quest 3 app that opens QuickTime VR files and puts you inside them.
This file is for whoever picks the project up next, including me.

Everything needed to build is in this folder. Nothing is installed system-wide.

```
QuestTimeVR/
  build.sh            one-command build; wraps Gradle with the local JDK/SDK
  toolchain/          JDK 17, Gradle 8.9, Android SDK 34, NDK 30, CMake  (~3.9 GB)
                      plus gradle-home/ - Gradle's cache, kept here rather than ~
  reference/          Python reference decoder, ffmpeg ground truth, test files
  android/            the app
```

## Commands

```bash
./build.sh                          # assembleDebug
./build.sh testDebugUnitTest        # the JVM tests - these are the real safety net
```

```bash
# Classic Mac files first - see the resource-fork note below
reference/flatten.py -o /tmp/flat Imports/*
reference/panotype.py /tmp/flat/*.mov      # what is each one, without decoding
```

```bash
ADB=toolchain/android-sdk/platform-tools/adb
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
$ADB push some.mov /sdcard/Android/data/com.questtime.vr/files/
$ADB shell am start -n com.questtime.vr/.VrActivity \
    --es com.questtime.vr.PATH /sdcard/Android/data/com.questtime.vr/files/some.mov
$ADB logcat -s QuestTimeVR:V
```

`/sdcard/Android/data/com.questtime.vr/files/` needs no storage permission, but
Android 11+ blocks MTP writes there — it is `adb push` only. `/sdcard/QuestTimeVR/`
and `/sdcard/Download/` work too but need "All files access".

## The one idea the whole app rests on

**Nothing is reprojected.** The panorama goes to the compositor as a composition
layer whose projection already matches how QuickTime VR stored it:

| Source | Layer |
|---|---|
| cylindrical (1.0 and 2.x) | `XR_KHR_composition_layer_cylinder`, four 90° arcs |
| cubic (2.x / QuickTime 5) | `XR_KHR_composition_layer_cube`, real cubemap swapchain |

A cylinder layer *is* a cylindrical projection, so there is no equirectangular
conversion, no resampling, and no shaders, meshes or view matrices anywhere in the
app. `vr_renderer.cpp` contains just enough EGL to satisfy OpenXR's graphics binding
and then submits layers.

For the cylinder the entire geometry contract is two numbers: `centralAngle`
(horizontal sweep, radians) and `aspectRatio` (pixel width / height). The runtime
derives the vertical extent as `atan(centralAngle / (2 * aspectRatio))`.

## Hard-won details

Every one of these cost real time. Change them only with evidence.

**`centralAngle` is valid over `[0, 2π)` — half open.** A full turn passed as exactly
2π is out of range. `xrEndFrame` still returns success and renders nothing. Clamped
to 6.2831.

**One near-360° arc does not work on this runtime.** It blanks half the cylinder
depending on which way you face the seam. Split into four 90° arcs, each pointing at
its own quarter of the texture. No quality cost — each arc samples its own slice.

Arc widths are `i * W / arcs`, so a width that does not divide by four simply gives
slices differing by a texel, each carrying the angle its own width earns. Do not
reduce the arc count to make the division exact — that is how you land back on one
arc, and the blank half returns. Scaling `centralAngle` and `aspectRatio` by the same
`w/W` leaves their ratio untouched, so every arc still ends at the same horizon.

**`radius = 0` renders nothing**, despite the spec saying zero means an infinite
cylinder. Use a finite radius (50 m). Radius cancels out of the angular geometry, so
this changes nothing but visibility.

**2D textures need a vertical flip; cube faces do not.** Row 0 of a GL 2D texture is
the *bottom* row, so the cylindrical upload flips. Cubemap faces use the opposite
convention and upload as stored.

**GL's cubemap convention is left-handed.** For the −Z face it derives `s` from `−x`,
so world-right lands at image-left and every side face comes out mirrored. Faces are
mirrored horizontally in `Qtvr.toGlOrder`.

**Composition layers composite in submission order, not by depth.** The polar cap
quads must be submitted *before* the arcs, or they paint over the cylinder as visible
rectangles instead of showing through the pole holes.

**Horizon OS intercepts launches for apps that do not declare hand tracking.** You
get a "turn on your controllers" dialog and your process never starts — the only
trace is `RequiresControllersLaunchInterceptor` in logcat. Fixed by declaring
`oculus.software.handtracking` as not required.

**Quest 3 allows 16384 textures but only 8192 swapchain images.** Wide panoramas
(the chapel sample is 8832) must be downscaled. Uniformly, so angles are preserved.

**Classic Mac movies keep the `moov` in the resource fork.** The data fork holds
only `mdat`, and the movie header is resource 'moov' #128. Nothing outside macOS can
see a resource fork — `adb push` silently drops it — so the file lands on the headset
as headerless media and reads as "not a QuickTime file". `reference/flatten.py`
appends the moov resource to a copy of the data fork; no offset rewriting is needed,
because chunk offsets in a dual-fork movie already address the data fork from its
start. Of 27 files in `Imports/`, 13 needed this.

**Many QTVR files have no filename extension at all**, because classic Mac files
carried type/creator codes instead. The picker filters on `.mov`, so those are
invisible to it. `flatten.py` writes `.mov` names, which is why the import workflow
goes through it.

**Horizon OS keeps the 2D panel and the immersive activity alive together**, so
`VrActivity` never receives `onPause` when you step back to the picker. Lifecycle
callbacks do not carry the "which one is the user looking at" signal here. Anything
that must stop when the panel appears has to be told by `MainActivity.onResume` —
that is why [Ambience] is process-wide rather than owned by the Activity.

**Object tracks are identified by handler, not format.** It is `handler == "obje"`;
the `format == "obji"` check that was there for months never matched a real file. A
pure object movie therefore read as "an ordinary movie", and `inspect` called Maranello
an openable panorama while `extract` refused it.

**`inspect` must refuse in the same order `extract` throws.** It exists to predict
`extract`, so any divergence is a lie told to someone deciding whether to bother. Two
have already been found: the cubic rotation check (extract diverts on `isCubic` before
reaching it) and the object check. When changing one, change the other.

**A pause arriving right within an open is the panel, not the user.** Launching the
immersive activity resumes MainActivity as a side effect, so its `Ambience.pause()`
lands *after* `open()` and cleared `wanted` while the player was still preparing - the
track silently never started. Ambience ignores pauses within 2.5 s of an open.

**Cubic files have two video tracks.** `jpeg` for the image and `smc` for the
hot-spot mask, same dimensions, same sample count. Pick by codec, not by order.

## Format notes

| | QTVR 1.0 | QTVR 2.x |
|---|---|---|
| descriptor lives in | `pano` sample *description* | pano track's *sample* |
| encoding | 16.16 fixed point | QT atom container (`sean` → `pdat`), Float32 |
| pano track identified by | `format == "pano"` | `handler == "pano"` |
| cubic marker | n/a | `panoType == "cube"`, plus a `cuvw` atom |

**`panoType` has four states, not two.** `'hcyl'`, `'vcyl'`, `'cube'`, or blank — and
when it is blank the low bit of `flags` (offset 72 in the pano sample atom, right
before `panoType` at 76) carries the orientation instead: **set means stored upright**.

Everything before QuickTime 5 stored the panorama sideways as a single column of
tiles, so it is stacked and rotated 90° clockwise. QuickTime 5 dropped that
requirement. Both sample files here are the old rotated form — `chapel_hi.mov` is
blank with the bit clear, `street-1.mov` is `'cube'` with the bit set and never
rotates — so the unconditional rotation was right by luck, not by checking. An upright
`'hcyl'` file is now refused rather than rotated into a horizon running down the side
of your view.

Cinepak has two traps, both commented in `Cinepak.kt`: codebooks are inherited from
the previous strip before selective updates apply, and the green channel truncates
toward zero rather than flooring.

**`vPanTop`/`vPanBottom` are not always trustworthy.** The 1995 file claims ±42.5°
where its pixels imply ±44.03° — a 3.5% disagreement. The chapel's agree exactly.
The app derives vertical extent from pixel geometry, because the aspect ratio is what
actually has to line up.

## Verification

The JVM tests are the safety net, and they are unusually strong for a graphics
project because the decode path is pure Kotlin:

- `DecoderTest.cinepakMatchesFfmpegExactly` — all 5,750,784 channels identical to
  ffmpeg.
- `Qtvr2Test.matchesFfmpeg` — the 2.x pipeline reproduces ffmpeg byte for byte.
  Android's test classpath has no JPEG decoder, so the decoder seam is fed ffmpeg's
  own tiles. That is sharper, not weaker: with the codec factored out, container
  parsing, assembly and rotation have nothing to hide behind.
- `CubeTest` — cubic detection, angles, and that the gradient fill leaves no voids.

Neither the sample panoramas nor the ground truth is in the repository. Restore them
with `reference/make_truth.sh` — `reference/README.md` says what the three samples
are, down to size and hash.

**A missing fixture skips rather than fails**, so a clone without them reports a green
build having checked almost nothing. The test task warns loudly whenever anything was
skipped; believe the warning over the green.

`reference/cubemap.py` reimplements GL's cubemap sampling on the desktop. It is how
the face order and mirroring were settled — far faster than cycling properties in a
headset. Use it before guessing at cube orientation.

## Releasing

The release APK must be built from a tree with **no `res/raw/ambience.mp3`** - that
track is someone else's music. Move it aside, `./build.sh clean`, then
`assembleDebug`. Clean is not optional here, and the reason is worth knowing.

**Verify the APK by walking local file headers, not by listing the central
directory.** An incrementally-patched APK keeps orphaned entry bodies that the
directory no longer references: one build measured 35.3 MB on disk while its directory
listed 424 files totalling 10.6 MB, and a byte-walk found **835 local headers**. Both
numbers were true. Every "is the music gone?" check read the directory, so every check
said clean while the music was still sitting in the file. The assumption that a zip
contains only what its directory lists is the trap.

```bash
python3 - <<'EOF'
import struct, zipfile
d = open('QuestTimeVR-0.1.0.apk','rb').read()
pos = n = 0
while pos + 4 <= len(d) and d[pos:pos+4] == b'PK\x03\x04':
    nlen, elen = struct.unpack('<HH', d[pos+26:pos+30])
    csize = struct.unpack('<I', d[pos+18:pos+22])[0]
    pos += 30 + nlen + elen + csize; n += 1
listed = len(zipfile.ZipFile('QuestTimeVR-0.1.0.apk').infolist())
print(f"local {n}  directory {listed}  orphans {n-listed}   (orphans must be 0)")
EOF
```

A clean debug build is **10.20 MB**. The `'ambience'` string that turns up in the
bytes is the `getIdentifier` literal inside `classes3.dex` and is expected; what must
be absent is any `res/raw` or audio entry.

`apksigner` needs `JAVA_HOME` pointed at the local JDK:

```bash
JAVA_HOME=toolchain/jdk/Contents/Home \
  toolchain/android-sdk/build-tools/34.0.0/apksigner verify --print-certs QuestTimeVR-0.1.0.apk
```

It is signed with the **Android debug key** (`C=US, O=Android, CN=Android Debug`).
Fine for sideloading and what SideQuest expects; not an identity anyone controls.

## Ambience

`res/raw/ambience.mp3` is optional and gitignored — it is someone else's music.
`Ambience` looks the resource up **by name** rather than as `R.raw.ambience`, so the
project builds without it and simply runs silent. Drop any mp3 in under that name to
enable it.

### Swapping the track

The browser page has a Background Music section. An uploaded track is stored in the
app's **private** `filesDir` as `background-music`, with its display name beside it -
private on purpose, so it never turns up in the panorama picker. `Ambience` prefers it
over the bundled `res/raw/ambience.mp3`, and neither present is silence rather than a
crash.

`reloadTrack()` simply releases the prepared player. Swapping the source underneath a
playing MediaPlayer is not worth the trouble when the next open is a fade-in anyway.

30-60 minutes is recommended because of the random drop-in: a short loop gives itself
away within a couple of panoramas.

**There are three music states, not two.** Uploaded, bundled, and *neither* - and the
third is what every clone starts in, because the repository ships no track. The page
originally fell through to "Using the track built into the app" whenever nothing had
been uploaded, which on a fresh clone is a flat lie told by a page whose only job is
to report what is actually on the headset. `musicInfo` could not see the bundled
resource at all; `Ambience.hasBundledTrack` now answers that, and the three states are
verified on device by building with the track moved aside.

**Two different zeros.** A track under a minute has a perfectly readable length and is
just far too short; a file whose duration cannot be read at all is a different
problem. Reporting the first as the second sends someone off to re-encode a file that
was fine. `musicInfo` branches on milliseconds, not on minutes already rounded to zero.

Every open drops in at a random point, fading in over 2.5 s and out over 1.2 s
(0.7 s each way when swapping panoramas, so the gap does not drag). The seek stays
30 s clear of the end so a fade-in is never cut short by the loop wrapping. Music
starts when the *decode* starts, not when the panorama appears — decoding takes
seconds and the fade covers exactly that gap.

## Turning with the controllers

Either thumbstick, flicked left or right, snaps the view 45 degrees. Snap rather than
smooth: a panorama gives the inner ear nothing to agree with, and continuous rotation
against a fixed image is what makes people queasy.

One action with subaction paths for both hands, so neither controller has to be the
main one. It engages past 0.7 and re-arms below 0.3, so a thumb resting on the stick
does not spin the world.

The accumulated `yaw_` is added to each arc's `theta` and applied to the cube layer's
orientation. The polar caps are a flat colour, so rotating them about Y is a visual
no-op and they are left alone.

**The yaw sign is not what reasoning suggests.** "You turn right, so the world swings
left" is the intuition, and it is wrong: the layer pose rotates *with* you, so a
rightward flick is a positive yaw. It shipped inverted once on exactly that argument.
If turning ever feels backwards again, this ternary is the whole fix.

**`xrSyncActions` returns a success code, not an error, when the session is
unfocused** - the action states just come back inactive. Do not treat it as failure.

**`xrAttachSessionActionSets` can only be called once per session**, and
`setupControllers` is the only place that calls it. Hand tracking uses
XR_EXT_hand_tracking, which needs no action set, so the two do not collide.

## The menu bar

Toggled by the **left controller's menu button**, or a left-hand pinch. Only the left
controller has a menu button an app may bind - the right one's equivalent is the
system button, reserved by Horizon OS - so there are no subaction paths on that
action, unlike the turn.

The pinch path is wired but has never fired on this device: the aim extension reports
`aimValid=1` with `strength=0.00` and every joint at `0x0`. It is left connected so a
runtime that does deliver hand tracking gets the gesture for nothing, and the button
carries it meanwhile.

**Head-locked, which is why it needs no view pose.** A `XR_REFERENCE_SPACE_TYPE_VIEW`
reference space already tracks the head, so a quad at -Z in that space is in front of
you wherever you look. The app calls `xrLocateViews` nowhere and does not need to. It
is also the right behaviour for something you summon and dismiss - a world-locked bar
would need finding again after a snap turn.

**It is submitted last**, after the arcs rather than before them like the caps.
Composition order is paint order, so a layer submitted after the bar paints over it
however far away it claims to be.

Drawn in Kotlin (`MenuBar.kt`) as an ARGB_8888 bitmap and handed over as RGBA through
`nativeSetMenu`. Text is why: laying out a line of type in C++ would mean shipping a
font and a rasteriser to redo what `android.graphics` already does, and the renderer's
whole design is to own as little drawing as possible. Two details that matter -
`Canvas` leaves alpha premultiplied, which is what OpenXR expects unless the
unpremultiplied bit is set, and the bitmap is flipped on the way in for the same
reason the panorama is.

**The menu swapchain reuses the format the panorama negotiated**, not a hardcoded
`GL_RGBA8`. A runtime need not offer that format at all, and if it chose sRGB for the
panorama then a linear bar would come out at a different gamma.

Selection is not wired. There is nothing to point at yet - no raycast, no cursor - so
the bar shows the open file's name and what the controls do. Making it interactive
needs a pointer pose and a hit test, which is the next real piece of work.

## The hairline behind you

There is a thin dark line at the back of cylindrical panoramas, visible in the
headset and **not** in a cast stream. Widening the arc bleed
(`debug.questtime.bleed`, thousandths of a degree) makes it thinner but has never
removed it. Pushing the radius from 50 m to 500 m removed a second one that ran
around the polar cap.

Attempted and reverted: replacing the arc-and-cap layers with a full-screen shader
into the eye buffers - one pass per eye, no layer boundaries anywhere, poles handled
by CLAMP_TO_EDGE and the wrap by REPEAT. The line was still visible, so it was
reverted: rendering into a 1680x1760 eye buffer throws away the compositor's ability
to sample a 4032-wide panorama at full display resolution, and that resolution is
worth more than the line costs.

**The filtering is `GL_LINEAR`, and we probably do not control it.** Min and mag are
both `GL_LINEAR`, wrap is `GL_REPEAT` / `GL_CLAMP_TO_EDGE`, `mipCount = 1` - so the
layer path has no mipmapping. Matt Celia's suggestion (point filtering, to kill the
seam) aims at a real mechanism: each arc's `subImage.imageRect` confines sampling to
its slice, a bilinear kernel at the edge column reaches half a texel outside it, and
at the three *interior* boundaries what lies outside is the correct continuation of
the image - which is exactly why there is one line and not four. At the wrap, arc 3's
right edge and arc 0's left edge are neighbours in the world but opposite ends of the
texture.

The catch: those `glTexParameteri` calls set state on our GL texture object in our
process. The compositor is a different process, receives a buffer handle, and samples
with its own sampler. OpenXR exposes no filtering control on `XrSwapchainCreateInfo`
or `XrCompositionLayerCylinderKHR`, so lines 659-662 are very likely already no-ops
for the compositor. Flipping them to `GL_NEAREST` would change nothing and prove
nothing. A wrap-around apron column dies on the same unknown - whether the
compositor's filter clamps to the **imageRect** or to the **texture**, which is
undocumented.

**The experiment that would settle it** (not yet run): roll the panorama horizontally
by half an arc before upload, behind a debug property, and look once. Line stays at
the arc boundary, now showing continuous content - it is the layer seam. Line moves
with the image's own wrap - the arcs are innocent. Either answer is worth more than
another round of guessing, and it costs one `memmove` and thirty seconds in a headset.

That result is suggestive but **not conclusive**, and the next person should know
why: the shader path introduced trilinear mipmapping, and hardware mip selection
breaks down exactly at an `atan2` wrap, where the screen-space derivative of u jumps
a whole texture width. That produces its own dark seam in the same place. So "the
line survived a renderer with no seams" may just mean one seam was swapped for
another. If you pick this up, kill the mipmapping first (or feed `textureGrad`
analytic derivatives) before concluding anything about layer boundaries.

## Uploading from a browser

The picker runs a small HTTP server on port 8080 and shows its address. Anything on
the same Wi-Fi can open it and drop files in; they land in the app's own folder, which
needs no storage permission and is the first place the picker looks. This exists
because `adb push` was the only reliable route - Android 11+ blocks MTP writes to that
folder - and that means a cable and a terminal for what should be a drag and drop.

Half the value is the check, not the transfer. Every upload goes through
`Qtvr.inspect` before it is stored, and nothing that cannot be opened is kept. The
message that matters most is the resource-fork one: a classic Mac file uploaded from a
browser arrives with its media intact and its `moov` missing, because no upload can
carry a resource fork. Saying that, rather than "not a QuickTime file", is the
difference between a dead end and a next step.

Verified end to end by driving it with `curl` over `adb forward tcp:8080 tcp:8080`,
which sidesteps any Wi-Fi routing question.

Confirmed working in the headset on 12 Sep 2026, along with folder browsing and the
ambience fix - the address line does render in the panel, which nothing on the host
could establish.

### The resource-fork wall is the main thing standing in the way

Measured, not guessed. Matt Celia fed the server the same 27-file archive that is in
`Imports/`. **Five opened.** Of the 22 refusals, **13 were "Classic Mac file, header
missing"** - nearly half the library, failing for a reason that has nothing to do with
the files. They are the same ones that work here after `flatten.py`. He could not run
`flatten.py` because he is a person with a browser, not a repo clone. The message is
accurate and even names the fix; the fix is unreachable from where the user stands.

**A `.zip` upload closes it, and this was verified end to end.** A Mac user's
right-click Compress stores the resource fork as an AppleDouble sidecar at
`__MACOSX/._Name` (`ditto -c -k --sequesterRsrc` reproduces it). Entry id 2 in that
sidecar is the resource fork, byte-identical to `path/..namedfork/rsrc`; feed it to
`flatten.find_resource(rf, 'moov')` and append to the data fork exactly as
`flatten.py` does. Against the real archive: 27 members, 14 already carrying a moov in
the data fork, **13 recovered from sidecars, 0 unrecoverable** - precisely the 13 that
failed for Matt. Output matched `flatten.py` byte for byte by SHA-256, and
`panotype.py` reads 11 of the 13 as single-node cylindrical `cvid`, which the app
renders today. Projected 5/27 -> 16/27.

`reference/applezip.py` is the reference implementation and reproduces the whole
result - port from it rather than re-deriving:

```bash
ditto -c -k --sequesterRsrc --keepParent Imports /tmp/imports.zip
reference/applezip.py -o /tmp/rescued /tmp/imports.zip
```

**Finder writes UTF-8 filenames without setting the UTF-8 flag bit**, so a zip reader
that trusts the flag falls back to cp437 and `Green Spiky Land (KPT Bryce™)` reaches
the picker as `BryceΓäó`. Re-encode to cp437 and decode as UTF-8, keeping the original
if that fails. `entry_name` does this, and the Kotlin port needs the same - Java's
`ZipInputStream` has the identical default.

**There is no Python on the headset, and none is needed.** `AppleZip.kt` is the port
of `applezip.py`, checked byte-for-byte against it by SHA-256 on a real archive, so
the recovery that used to require a repo clone and a terminal now happens on device,
automatically, on upload. `flatten.py` stays as the reference implementation and as a
way to do it by hand; it is no longer something a user has to run.

**Two routes in, because a resource fork travels two ways.** Inside a zip, as an
AppleDouble sidecar under `__MACOSX/`. And *loose*, as a `._Name` file beside its
data fork - which is what macOS writes the moment those files touch FAT, exFAT or an
SMB share, and is how most old archives have actually travelled. A plain multi-select
therefore often carries both halves without the sender realising, so `handleUpload`
pairs them the same way `AppleZip.extract` does. A sidecar with nothing to attach to
is consumed silently rather than reported: it is invisible in Finder and nobody
knowingly sent it.

**What cannot be recovered, and why no amount of detection helps.** On HFS+ or APFS
the fork is a real fork - not a file, nothing beside it on disk. Drag such a file into
a browser and the fork does not travel; the bytes never leave the Mac. There is
nothing on the receiving end to detect or repair. That is the entire reason the advice
matters, and why it has to name a route the reader can actually take.

**The refusal message is part of the feature.** It named `reference/flatten.py` for
months - a file that only exists if you cloned the repository, so a dead end for
exactly the person the message is written for. `InspectTest` now asserts the detail
mentions zipping and *does not* mention `flatten.py`, because the old assertion
pinned the stale advice in place.

Still open: nested folders inside an archive need a policy - members are currently
flattened to the top level.

### What the page lists

`/files` reports everything the viewer can reach, built from the same `FileList`
helpers the picker uses - one source of truth, so the page cannot drift from the
headset. It counts loose files *and* folder contents, so its number is deliberately
larger than the picker's header, which counts only the level you are browsing. The
wording on each says which, because "21" beside "4" otherwise reads as a bug.

## Seeing what you are building

There are four tiers, and the cheap ones cover most of the project. Reach for the
headset last, not first.

**1. The JVM tests.** Most of this app is decode, geometry and server logic, none of
which needs a device. `./build.sh testDebugUnitTest` is seconds, and it is where the
byte-exact ffmpeg comparisons live. If a change can be expressed as a test, it should
be - the alternative is a build-install-wear cycle measured in minutes.

**2. The upload page, in a desktop browser.** `page()` is a pure string that touches
no Android API, so it renders on the host:

```bash
./build.sh testDebugUnitTest --tests '*PagePreviewTest*'
open android/app/build/preview/upload-page.html
```

`PagePreviewTest` is a development tool wearing a test's clothes - it asserts nothing,
it just writes the file. It lives in the test source set because that is the only
place with the app's classes on a JVM classpath. This caught a wrap bug the moment it
existed: `.zip` fell onto its own centred line in the drop zone and read as a heading.

**3. Casting, via Meta Quest Developer Hub.** Mirrors the headset to the desktop, and
it is the only way to watch someone else use it. **Do not trust it for fine detail** -
the hairline seam behind the viewer is plainly visible in the headset and completely
absent from a cast stream. Compression and downscaling eat exactly the class of defect
this project keeps hitting.

**4. The headset.** The only ground truth for composition layers, and there is no way
around it. `adb shell screencap` returns a 0-byte file for the 2D panel and a black
frame for the immersive view, so nothing here can be settled from the host.

The menu bar is in tier 2 as well, via Robolectric:

```bash
./build.sh testDebugUnitTest --tests '*MenuPreviewTest*'
open android/app/build/preview/          # menu-bar.png, menu-bar-long.png
```

**`GraphicsMode.NATIVE` is the load-bearing part.** Robolectric's default graphics
shadows record draw calls without rasterising anything, so the bitmap comes back fully
transparent and the preview is a confident lie. Native mode runs the real Android
graphics stack. `MenuPreviewTest` asserts on coverage and ink specifically so that a
silent regression to recording mode fails the build rather than quietly producing
blank PNGs.

The first render paid for the whole exercise: the hint was a single ellipsized line
that cut at "Thumbstic...", throwing away both the turn and the way out, while the
bottom 40% of the panel sat empty. It wraps to two lines now, and
`theHintFitsWithoutBeingCutOff` keeps it wrapped.

**Robolectric's `android-all` jars are large, and they land in `$GRADLE_USER_HOME`.**
Adding it filled a boot volume that had 117 MB left, and the symptom was not a disk
error but Gradle failing to release a lock on its own cache - which is a confusing
thing to debug from. `build.sh` now points `GRADLE_USER_HOME` at
`toolchain/gradle-home`, so the cache grows on the project's own volume with the rest
of the toolchain. That is what makes "everything needed to build is in this folder"
true rather than nearly true.

## Debugging on the headset, honestly

**The host cannot see either screen.** `adb shell screencap` returns a 0-byte file for
the 2D panel and a black frame for the immersive view, and `uiautomator dump` omits
some TextViews entirely - the server address line never appears in a dump even when
logging proves the text is set and the view visible. Anything visual needs a person
wearing it. Do not spend turns trying to prove a UI detail remotely.

**A stopped VrActivity looks exactly like a crashed renderer.** All the bring-up lines
appear - instance, system, swapchain, layer parameters - and then nothing: no
"session started", no "frames=". That is not a failure. With `mResumed=false
mStopped=true` the OpenXR session never reaches READY, so `xrBeginSession` is never
called and the loop sits in its not-running sleep. Check

```bash
adb shell dumpsys activity top | grep -E "mResumed|mStopped"
```

before debugging the renderer. Wearing the headset, or re-applying
`adb shell am broadcast -a com.oculus.vrpowermanager.prox_close`, is usually the fix.

**Quest's log buffer rotates in about thirty seconds.** Reading back with `logcat -d`
after the fact will lose lines and look like a bug. Stream it across the event instead.

## Known limits

- Object movies: detected and refused.
- Upright-stored cylinders (`'hcyl'`, or blank `panoType` with the flags bit set):
  detected and refused. A survey of nine files across three archives, 1995-2007,
  turned up none — every cylindrical file in the wild is the legacy rotated form, so
  there is nothing to test a fix against. `reference/fetch_wild.sh` and
  `reference/panotype.py` reproduce that survey.
- Hotspots: parsed enough to identify, not used. The street sample has a real
  hot-spot track sitting there unused.
- Multi-node scenes: detected and refused. One `pano` sample per node, and decoding
  every image sample under node one's descriptor stacks all the nodes into one very
  tall column that looks like a panorama and is not. Node selection is the feature
  that would make this more than a photo viewer. **Now the largest remaining gap with
  a number attached**: 7 of the 27 files in a real user's archive, and the only
  category left once zip import lands.
- Hand tracking returns `aimValid=1` with `strength=0.00` and all joints `0x0` on
  this device — believed to be controllers being powered, unconfirmed. The in-VR menu
  is blocked behind it.
- No in-app exit. Use the Meta button, or `adb shell am force-stop com.questtime.vr`.

## Style

Match what is there. The comments explain *why* — particularly where a line encodes
a runtime quirk that looks arbitrary. Do not add unverified decoders or renderers
that silently produce wrong output; refusing with a clear message is better, and the
project has held that line so far.
