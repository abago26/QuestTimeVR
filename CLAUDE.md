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
carried type/creator codes instead - **24 of the 27 files in a real archive**. The
picker filtered on `.mov` and so showed three of them, which is a poor welcome for
someone who has just copied a folder onto a headset. It now asks the file instead
when the name does not say: eight bytes, and the first atom must be one a classic
QuickTime movie opens with (`moov`, `mdat`, `pnot`, `wide`, `free`, `skip`).

Deliberately **not** `ftyp`. That is the modern ISO/MP4 signature, which no file of
this era carries, and accepting it would pull every stray `.mp4` in Download into a
list of panoramas. A modern `.mov` that does start with `ftyp` matches by extension
anyway, so nothing is lost.

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
- `SceneTest` — the node partition, against ffmpeg's own decode of the same frame
  range, for the first, middle and last node of a nine-node scene.
- `NodeTableTest` — the node table read off the real archive: names, the ids that
  skip, the default view. And `inspectPredictsExtractForEveryFileInTheArchive`, which
  walks all 27 files and holds the two functions to each other, because they have
  drifted apart twice before.
- `SceneTest` also covers 2.x: three nodes against ffmpeg, all 25 of Joshua Tree's
  decoding to panorama shapes, and that four nodes sharing one image track come out
  as four *different* places - a partition that ignored the offset would hand back
  four copies of one picture, which is what a plausible wrong answer looks like here.
- `FileListTest` — that every file in the archive is offered despite 24 of them
  having no extension, and that an `.mp4`, a text file and a 3-byte file are not.
- `SmcTest` — the hot-spot mask codec: a whole 216-frame track byte-exact against
  ffmpeg, plus three clips from ffmpeg's own smc *encoder* to reach the eleven
  opcodes real masks never use. Without those a fifth of the decoder would be
  written from a description and never executed.
- `HotspotTest` — the chain end to end on a real file: a pixel the mask says is a
  hot spot, turned back into a gaze, resolving to the node the link names. And the
  geometry on its own, including the gradient-cap correction that is otherwise
  invisible.

Neither the sample panoramas nor the ground truth is in the repository. Restore them
with `reference/make_truth.sh` — `reference/README.md` says what the three samples
are, down to size and hash.

**ffmpeg honours edit lists, and for these files that silently misaligns the truth.**
`make_truth.sh` passes `-ignore_editlist 1` on every decode and it is load-bearing.
A QuickTime edit list remaps a track's timeline; Joshua Tree's shared image tracks
carry lists that alternate empty edits with the four node segments, so by default
ffmpeg pads the gaps and emits the first node twice while dropping the last. The
result was ground truth that disagreed with a decoder which was in fact **correct** -
the most expensive kind of wrong, because the instrument looks fine and the code
looks broken. What settled it was rendering both and looking: the four candidate
groupings were obviously coherent panoramas, so the grouping was never the problem.
Ask for a picture early, again.

The edit list turned out to *confirm* the partition rather than contradict it - its
segments sit at media times 0, 7200, 14400, 21600, which are frames 0, 24, 48, 72,
exactly the node boundaries. The other four samples carry a single trivial edit, so
the flag changes nothing for them and every truth file regenerates byte-identically;
it is applied uniformly so no future fixture can be caught by it.

**A missing fixture skips rather than fails**, so a clone without them reports a green
build having checked almost nothing. The test task warns loudly whenever anything was
skipped; believe the warning over the green.

`reference/cubemap.py` reimplements GL's cubemap sampling on the desktop. It is how
the face order and mirroring were settled — far faster than cycling properties in a
headset. Use it before guessing at cube orientation.

## The name and the tile in the library

Sideloaded apps land in Horizon OS under **Unknown Sources**, and this one arrived
there with a blank tile reading "App Name Unavailable".

**The label was never the problem; the icon was.** `android:label` had been set on
`<application>` since the beginning. There was no `android:icon` at all, so the
system had nothing to draw and fell back to a placeholder for both halves of the
tile. Adding one fixed it. Both activities now also carry the label explicitly -
precautionary rather than diagnosed, because the immersive activity is rooted in its
own task and an explicit label costs nothing.

`reference/make_icons.sh` generates every density from `docs/images/app-icon.png`.
One source, so the tile cannot end up different at one size; nothing under
`res/mipmap-*` should be edited by hand.

**Getting out of Unknown Sources is not a manifest change.** No flag, metadata entry
or signing choice moves an app into the main library - that placement *is* the
distinction between "Meta has reviewed this" and "you installed it yourself". The
only routes are Meta's own: submit through the Horizon Store's developer console
(review, an organisation with a verified identity), or publish an unlisted build and
hand out its link, which installs through the store machinery and so lands in the
library properly while remaining invisible to anyone without the URL. Sideloading a
signed APK cannot reach either. Worth knowing before spending time on the manifest.

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

## Scenes — more than one panorama in a file

A scene is several panoramas in one movie: stand here, walk through the door, stand
there. Everything below is QuickTime VR **1.0**, which is what every multi-node file
in the archive turned out to be, and was read off those files rather than taken from
a specification — `NodeTableTest` and `SceneTest` are where the offsets are pinned.

**The image track is partitioned in storage order.** Every node has the same tile
count — the descriptor's `numFrames` — and the nodes sit back to back, so node k owns
samples `[k*n, (k+1)*n)`. Measured across all four scenes: 9x24=216, 13x24=312,
33x24=792, 35x24=840, each exactly the track's length. That exactness is the check;
if the arithmetic does not come out, the whole track is handed back rather than a
guessed-at fraction of it.

**A node's id is not its index, and using one as the other is silent.** White House
keeps thirteen nodes numbered 1,2,3,4,5,7,8,9,10,12,14,15,16 — 6, 11 and 13 were
deleted in authoring and nothing renumbered. Index selects the tiles; id is what links
refer to. Reading the id as a position fetches the wrong place for everything past the
fifth and runs off the end for the last three, and every one of those is a plausible
panorama of somewhere else. `nodeIdsAreNotPositionsAndMayHaveGaps` exists for this.

**A node sample is a flat sequence of 8-byte atoms**, not a QuickTime atom container —
they tile the sample exactly, which is how it was settled:

| atom | | |
|---|---|---|
| `pHdr` | 64 | node id, default pan/tilt/FOV as 16.16 fixed, and where the name is |
| `pLnk` | 68 | one per link; destination **node id** at payload+16 |
| `pHot` | 68 | one per hot spot |
| `strT` | n | every string in the node, as Pascal strings |

**String offsets count from the strT atom's own start**, so they include its 8-byte
header and the first string sits at offset 8, not 0. That reads exactly like an
off-by-eight bug and is not; `readsEveryNodeWithItsName` is what says so.

**Lincoln Memorial carries a second Cinepak track** — 192x84, a low-resolution copy
for scrubbing — and it decodes perfectly well. Nothing but the descriptor
distinguishes it from the real image track, and `imageTrack` picked the right one only
because it happens to be stored first. It now matches the descriptor's scene size
instead: tiles the width of the scene that stack to its height. The codec preference
still does the separate job of skipping the `smc` hot-spot track.

**The picker is two levels deep for a scene.** Choosing "Lincoln Memorial" does not
name a place to stand, so it opens the scene's nodes instead of the file, and A/X
steps back to the files rather than closing — the controls strip says "back" instead
of "open/close" in that state, because a strip describing a button that does something
else is the same failure as the hint that got cut. Reading the node table means
reading the whole file, so it happens on a worker for the same reason `showInfo` does.

### 2.x scenes: each node names its own track

A different arrangement, and in one way a simpler one. The pano track carries a
`tref`/`imgt` list of image-track ids, and each node's `pdat` holds a **1-based index
into that list** (`imageRefTrackIndex`, payload offset 4). So a node names its own
image track rather than taking a share of one, and each node has its **own
descriptor** - Point Lobos runs 1468, 1480, 1496, 1524, 752, 736 pixels wide across
six nodes, so using the first node's numbers for the fifth would stretch it. Every
check in `extract` is made against the node being opened, not against node zero.

**But nodes may share a track, and then it is partitioned exactly as 1.0 partitions
its single one.** Joshua Tree points four of its twenty-five nodes at the same
96-frame track, three at a 72-frame one, two at a 48. Every count comes out exact.
That makes the two versions one rule - 1.0 is simply the case where the list has one
entry and every node shares it - which is why `nodeSamples` takes the peers of a
track rather than a version number.

**2.x hot spots live in the qtvr track, and now work.** Joshua Tree listed 25 nodes
and offered no doorways at all. They were never missing - they are in a different
container, and `SceneAtoms` walks it:

```
sean
  ndhd   +0 version, +4 'pano' or 'obje', +8 the node's ID
  hspa
    hots   id = THE HOT SPOT ID, the number the smc mask stores per pixel
      vrsg   the author's words: "Go for a walk to Cyclops"
      hsin   kind; 'link' at +4
      link   destination node ID at +4
```

Three things cost a cycle each and are pinned by `SceneAtomsTest`:

- **The sample prefix is twelve bytes, not ten.** Ten is the figure usually quoted for
  a QuickTime atom container; start there and the first size reads zero and nothing is
  found at all.
- **The hot-spot id is the `hots` atom's id**, in the atom header rather than the
  payload. That is the whole join to the mask.
- **The node id is in `ndhd`, and is not the position.** Inventing them as index+1 is
  wrong twice over: the qtvr track counts object nodes too, and storage order is not
  id order - Joshua Tree stores `pano#15, #16, #17, #14`. A link to node 27 resolved
  to nothing and a link to 20 resolved to the wrong place.

**A doorway may lead to an object node**, which is a thing to spin rather than a place
to stand. Joshua Tree's ids 19 and 20 are `obje`, and two of node 0's four hot spots
point at them. Those links are dropped, so the reticle stays dark over them - offering
a way on that cannot be walked is worse than not lighting it.

2.x carries **no node names**. The readable text in these files ("Go for a walk to
Cyclops") belongs to hot spots, in a `vrsg` atom under the qtvr track's `ndhd` node
header; there is no `strT` beside the node the way 1.0 has. So 2.x nodes list by
position until someone walks that container.

Hot spots are still only parsed, so walking a scene is by list rather than by looking
at a door and pinching. The link graph is read and asserted — Lincoln's is reciprocal,
1↔2, 4↔5 — precisely so that whatever follows a link has to map an id back to an index
and cannot quietly use the id as a position.

## Walking through a doorway

Answering "what am I looking at" needs three things that had nothing to do with each
other: a codec, some atoms, and a piece of geometry.

**The mask is an image, and its pixels are numbers rather than colours.** Every node
can carry a hot-spot track the same size as its panorama, in which each pixel is the
id of the hot spot covering it, 0 for none. So `Smc` hands back palette indices and
never looks a colour up. Lincoln's first node is a clean example: one hot spot, id
248, and a mask containing exactly {0, 248}.

**The chain is hot spot → link → node, and the last step is the dangerous one.**
`pHot(248, 'link', 1)` names link 1; `pLnk(1, ...)` names destination node **id** 2;
and an id is not an index. `Hotspots.destination` searches for the id and never
indexes with it - see the White House note under [Scenes] for what that costs.

**The gaze-to-texel mapping is the compositor's own arithmetic, not a raycast.** A
cylinder layer *is* a cylindrical projection, so where a direction lands in the image
is exact:

- horizontally, linear in angle - the column is the fraction of `centralAngle`,
  wrapped, so the seam behind you is not a special case;
- vertically, **linear in height, not in angle**. The texture goes up the cylinder
  wall, and height is `radius * tan(pitch)`, so the row goes with the tangent. The
  top edge is at `atan(centralAngle / (2 * aspectRatio))` - the runtime's own
  expression, quoted in `Hotspots.texel` so the two cannot disagree about the horizon.

**The gradient caps have to come out before the mask is read**, and this is the one
that would have been invisible. What reaches the compositor is not what came out of
the file: `Caps.addGradient` centres the decoded band in a taller image and fills the
rest with sky and floor the mask knows nothing about. Sampling with the displayed
fraction squeezes every hot spot towards the horizon - by about a third on a typical
file - and the error is largest exactly where doorways are. `Hotspots.intoBand` takes
it out, and deliberately returns values outside [0,1) for a gaze into the gradient,
which reads as nothing there. A downscale needs no such correction: it is uniform, so
fractions survive it.

**The renderer only learns one bit.** Native reports the gaze direction in the
panorama's own frame - with `yaw_` already subtracted, because the layers rotate
*with* the snap turn and a hot spot has to stay on the doorway it was painted over -
and Kotlin, which has the mask and the node table, answers whether there is something
there. The reticle is the hand cursor's own dot in a `XR_REFERENCE_SPACE_TYPE_VIEW`
quad, so it needs no view pose, and it is **only up when there is something under
it**: a reticle welded to the middle of a photograph you came to look at is worse
than none.

**The trigger has two meanings and they never overlap.** In the list it chooses a
row; outside it, it walks through whatever is under the gaze. Whether the list is up
is what tells them apart.

**The label hangs below the reticle, never on it.** The point of looking at a doorway
is to see the doorway, so a card in the middle of the view covers the thing it is
naming. It sits far enough down to clear the dot and no further - about 4 degrees -
so the two are one glance apart, and it is submitted only alongside the reticle: a
name floating under nothing would be labelling something you are not looking at.

The words are the author's own. "To DCwalk.02", with the **link's** wording preferred
over the hot spot's, because authoring tools named hot spots after their own
numbering ("Link 248") and saved the description for the link. A way on the file
never named falls back to "A way on" rather than a serial number.

**The label's bitmap is a fixed size and the pill inside it is not.** That looks like
a drawing detail and is really about cost: a swapchain's dimensions are fixed at
creation, so a bitmap sized to its text would destroy and rebuild one every time the
gaze crossed to a doorway with a longer name - several times a second while looking
around a room. A constant bitmap is created once and refilled; the pill is drawn
centred at its measured width with transparent margins, which looks identical.

It is scaled by the menu bar's own pixels-per-metre - 1024 px across one metre - so
type drawn at a given size in Kotlin subtends the same angle as a panel that has
already been read in a headset, rather than one that was guessed at.

**Both versions walk, but through different containers.** A 1.0 node keeps its hot
spots as `pHot` atoms in its own pano sample, which is what `NodeTable` walks; 2.x
keeps them in the `qtvr` track's node header, as `hots` atoms under `ndhd` with their
names in `vrsg`, which is what `SceneAtoms` walks. Everything downstream of that -
the mask, the texel mapping, the gradient-cap correction, the reticle - is shared,
because by then a hot spot is just an id and a destination. Measured on the archive:
Lincoln Memorial 9 hot spots across 9 nodes, White House 12 across 13, Joshua Tree 4
on its first node alone where it used to report 0 across 25.

## Drawing controllers, and why there is nothing here now

Built, seen working in a headset, then removed on request. `git show 2807423` has it -
a projection layer containing nothing but controllers, eye buffers cleared to alpha 0
and submitted with `BLEND_TEXTURE_SOURCE_ALPHA` so the panorama came through
everywhere else. The cylinder layers were untouched and the frame counter stayed at
720 of 720, which is the thing the earlier eye-buffer experiment failed.

Three facts from it worth keeping even though the code is gone:

- **`XR_FB_render_model` is not on this runtime.** Meta's own controller meshes come
  from that extension and it is not among the **72** this runtime offers. It cannot be
  installed - it is part of Horizon OS, not a library - and a desktop Meta XR Simulator
  cannot add it to a headset. The only route to the authentic look is bundling Meta's
  glTF assets into the APK, which is a licensing decision rather than a technical one.
- **The projection matrix must come from the four `XrFovf` tangents.** A Quest's lenses
  look outwards, so left and right are not symmetric, and a textbook
  `perspective(fovy, aspect)` gives wrong parallax rather than an obviously broken
  picture.
- **`XR_USE_GRAPHICS_API_OPENGL_ES` must be defined before `openxr_platform.h`** or
  `XrSwapchainImageOpenGLESKHR` does not exist, and the error names the type rather
  than the missing define.

**And the extension list is logged one line per entry.** A joined line is truncated by
logcat mid-name, and a truncated list reads exactly like a missing extension - it said
34 where the truth is 72. The render-model answer happened to survive that, which is
the dangerous shape of the mistake rather than a comfort.


## The seams, and what they actually were — solved 14 Sep 2026

Three lines, three different causes, none of them what the notes assumed for months.
All three are fixed. The knobs that found them are still there.

**Top and bottom: the arc's own rect edge.** Each arc's `imageRect` spanned the full
texture height, so at the outermost row the compositor's filter reached past the rect.
The arcs now leave `vInset` rows (8) unsampled at each end, with `aspectRatio` scaled
by `swHeight_ / (swHeight_ - 2*vInset)` so the horizon does not move, and the caps'
`halfV` derived from the same trimmed geometry so the two still meet. This works
because those rows are deep in flat gradient - Monument Valley pads 442 - and clamping
to a flat colour is invisible. It is *not* transferable to the wrap, where the rows
either side are real picture.

**Behind you, the big one: `bleed` was causing it.** This is the counter-intuitive one
and it wasted the most time.

`bleed` grew each arc's `centralAngle` about its own centre while leaving its texture
rect alone - the same texels over a wider angle. That does buy overlap, and it also
displaces the arc's content by `bleed/2` at each edge. So every boundary showed the
picture stepping sideways by half the bleed. At the default 0.08 degrees that is about
**one pixel** on a Quest 3, which is precisely a hairline, and precisely why widening
the bleed only ever made it *look* thinner: more overlap, but more displacement too.

It gave itself away only when the value was pushed to 0.6 degrees to "cover the seam
better". At ~7 px the hairline became an obvious lateral step, and a photograph of a
step is unmistakably different from a photograph of a dark line. `bleed` now defaults
to **0** and should stay there.

The overlap it was buying comes from the texture instead. A cylindrical panorama is
uploaded with `pad_` columns of wrap-around on each side, and each arc reaches
`apron_` columns past its own slice - so neighbouring arcs, including the pair either
side of the wrap, overlap with correct content at correct positions. Overlap without
stretching.

**Then a black line survived that**, because the outermost rects sat flush against the
texture's edge and a filter kernel reaching half a texel past them found the border,
not a pixel. Hence `kGuardColumns`: `pad_ = kApronColumns + kGuardColumns`, so there
are always a few columns of real picture outside every rect that no arc addresses.
They exist purely to be sampled into.

**How it was found, because the method matters more than the answer.** Four
hypotheses, three wrong. What settled it was a photograph: "it shifts the image" is a
different symptom from "there is a dark line", and no amount of reasoning about
darkness was going to get there. Ask for a picture early.

Two measurements that are still worth trusting, both host-side: the decoded image is
continuous at the wrap (1.3x the ordinary column-to-column step, and no outlier
anywhere in 3000 columns), and it stays continuous after `Caps.addGradient` - measured
on the exact buffer that gets uploaded. So the data was never the problem.

**And a warning about instruments.** `debug.questtime.roll` slides the panorama to
separate "the line is at the layer boundary" from "the line is in the picture". It
read `swWidth_`, which was the image's width until the apron redefined it as
image-plus-padding - after which it strode 16 pixels too far per row, sheared the
picture, and read past its buffer. Nothing crashed. It produced a confident reading
that sent the search the wrong way, and cost a headset session. A broken instrument
does not look broken; it looks like evidence.

### Hand input, and why it is switched off

Hand tracking works (see Known limits) and so does everything built on it: the ray
meets the panel, the cursor sits where you point, the highlight follows. It is
**disabled by default** for one reason.

**One pinch produces two events.** The strength threshold fires a confirm, and one
millisecond later - the same frame - Meta's `aimPinch` bit fires a menu toggle.
Measured on device: `1 ms  input 5 -> input 0`, three times out of three. So a pinch
meant to open the list also chose whatever row the cursor was over, and the headset
flipped between panoramas with the info panel appearing unbidden.

An earlier attempt at this set `pinchArmed_ = false` inside the aim branch, which runs
*after* the strength check in the same frame - too late by one statement, and the
symptom barely changed.

The fix is not another guard. It is to stop reading two independent signals for one
gesture: `aimPinch` lags `pinchStrengthIndex` badly enough that a pinch reads as
closed to one and open to the other. Drive open/close *and* confirm from a single
state machine on the strength, and require a release between them.

```bash
adb shell setprop debug.questtime.hands 1      # to work on it
```

The cursor and the row mapping are worth keeping either way - `RowAtTest` covers the
mapping on the JVM, and the cursor is a quad layer, not a renderer.

### The knobs

| property | default | what it does |
|---|---|---|
| `debug.questtime.bleed` | 0 | angular stretch per arc. **Leave at 0** - see above |
| `debug.questtime.vinset` | 8 | texture rows left unsampled top and bottom |
| `debug.questtime.capinset` | 970 | thousandths; how far inside the rim the caps sit |
| `debug.questtime.roll` | 0 | thousandths of a turn; diagnostic only |
| `debug.questtime.radius` | 500 | cylinder radius, metres |
| `debug.questtime.arcs` | 4 | arc count. Do not reduce - see the note above |

Every one is logged on the `layer:` line at startup. They were not, and a value set on
the device was indistinguishable from one that had not taken.

## The first launch, when the headset is empty

A new install has no files, and the app opens a panorama chosen at random from what is
there. With nothing there it used to stay on the flat 2D panel - which is the worst
moment for that to happen, because the one thing a first-time user needs is the web
address, and what they got was a screen that did not look like the app at all.

`Welcome.kt` draws a panorama instead: a sky-to-ground gradient with the app's name,
the address this headset is actually serving on, and what to press.

**Generated rather than bundled, for the same reason `ambience.mp3` is not in the
repository.** Every stock panorama worth shipping is a photograph somebody owns. A
drawn one costs nothing in the APK, and it can name an address that no bundled image
could know.

**It is a `Panorama` like any other.** It goes through `Caps.addGradient` and the
cylinder geometry unchanged, so there is no second rendering path to keep in step.
The band is 4096x1137 - 3.6:1, which is White House's ratio - so the welcome sits at
about the same horizon as the first real file will, rather than looking like a
different app the moment something is opened.

**The text is laid out in degrees, not pixels.** At a full turn across 4096 px one
degree is 4096/360, and the sizes are written that way round so they can be checked
against something: the title is a 5-degree cap height, the body 2.4.

**Four times round, centred in each quarter.** Once would leave three quarters of the
turn blank at exactly the moment nobody knows which way to look. Centred in its own
quarter matters too: the cylinder is submitted as four 90-degree arcs, so a block
centred in one cannot be split by a boundary, and the wrap behind the viewer is a
boundary like any other. `saysItFourTimesRound` measures ink per quarter and requires
the four to agree within 5%.

**The horizon line stops either side of the text.** Drawn across the whole turn it
runs straight through the address - the one line on that wall somebody has to read
character by character. The blank stretches are where the turning cue was wanted
anyway.

To see it without emptying the headset:

```bash
adb shell "am start -n com.questtime.vr/.VrActivity \
    --ez com.questtime.vr.WELCOME true --ez com.questtime.vr.SHOW_PICKER true"
```

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

**And the picker pairs them too, not just the upload page.** That was a real gap:
the same folder copied over a cable produced "not a QuickTime file" for every
dual-fork movie in it while the browser accepted them happily. `AppleZip.readPaired`
is now what every open path reads through - the viewer, the info card, and the node
list - so a `._Name` sitting beside its data fork is put back automatically. A file
that already carries its own `moov` is returned untouched, because a flattened file
has one and appending a second header to it would be actively wrong. (`Lincoln
Memorial` is exactly that case, which is why `WHouseVR.MOV` is the fixture for it.)
Sidecars are also hidden from the picker: they are invisible in Finder and nobody
knowingly copied one.

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

### Rebuilding a file whose header never arrived

A loose classic Mac file arrives as an `mdat` and nothing else. It can often be
rebuilt anyway, because **Cinepak frames describe themselves**: flag byte, 24-bit
length, then width, height and strip count. Walking them reconstructs the sample
table.

Verified rather than assumed, twice over. The byte ranges found by walking
`White House - South Portico` are **identical** to the ones its recovered `moov`
lists - same count, same offsets, same sizes - and `HeaderlessTest` decodes the same
file both ways and asserts the pixels match exactly.

Everything else the header would have said is already assumed or already ignored: a
full turn, the legacy rotated storage, and a vertical extent taken from pixel aspect
because `vPanTop`/`vPanBottom` are not trustworthy. `PanoInfo` is left null rather
than invented.

**The node count is the one thing that cannot be recovered**, and it is the dangerous
one: a multi-node scene keeps every node's tiles in the same media, so a naive walk
stacks them into something that looks like a panorama and is not. The guard is shape.
A single node lands between 2:1 and 8:1 - White House is 3.6:1 - while 13 nodes stack
to about 47:1. `WHouseVR.MOV` is refused by that test, and the test says so by name.

Two traps, both found by tests rather than by reasoning:

- **A file with a `moov` must never take this path.** `flatten.py` appends the
  recovered header *after* the media, so a flattened file has its `mdat` first;
  stopping at the first `mdat` would rebuild a file that already had a perfectly good
  descriptor and throw away its node count. The whole atom list is walked before
  answering.
- **`inspect` had to change with it.** It exists to predict `extract`, so a file
  extract now rebuilds cannot still be reported as broken. The summary says
  `(rebuilt)` because the geometry was inferred from pixels rather than read.

Zip is still the better route and the page still says so - it restores the real
header instead of inferring one.

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
open android/app/build/preview/          # menu-bar.png, menu-list.png, welcome.png ...
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

**A debug key is not one key.** `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do
not match` on a build that is plainly the same version means the installed copy was
signed with a *different* `~/.android/debug.keystore` - both certificates read
`C=US, O=Android, CN=Android Debug`, and only their SHA-256 digests differ. Compare
them rather than guessing:

```bash
JAVA_HOME=toolchain/jdk/Contents/Home \
  toolchain/android-sdk/build-tools/34.0.0/apksigner verify --print-certs "$APK" |
  grep SHA-256
adb shell pm path com.questtime.vr        # then pull that and print its certs too
```

**And `versionName` is not proof of what is installed.** A build reported
`versionName=0.2.5`, and so did the tagged 0.2.5 release - but the copy on the headset
was a local debug push made during a feature branch that never bumped the string. The
feature had been removed in source and in every shipped APK, and the headset still
showed it, so the removal looked like it had failed. `dumpsys package` gives
`lastUpdateTime` as well; compare that against the commit before touching code.

```bash
adb shell dumpsys package com.questtime.vr | grep -E "versionName|lastUpdateTime"
```

Bumping `versionName` before pushing a build to the headset costs nothing and turns
this whole class of confusion into one line of output. This is the third instrument in
these notes that read like evidence and was not.

**And never redirect `adb install` to /dev/null.** This failure is silent by
design - the command exits 0 and prints the reason on stdout - so suppressing its
output leaves the old build running while every subsequent "test on device" reports
on code that is not there. It cost a long detour: a log line placed immediately after
one that *was* appearing never showed up, which looked impossible and was simply a
stale APK. If a change does not appear on the headset, check the install before
checking the code.

There is no way round it but `adb uninstall`, which takes the app's data with it.
Two things are worth saving first, and both are usually already on the Mac: the
panoramas in `/sdcard/Android/data/com.questtime.vr/files/`, and the uploaded music
track, which lives in private storage and needs `run-as`:

```bash
adb exec-out run-as com.questtime.vr cat files/background-music > /tmp/music.mp3
adb pull /sdcard/Android/data/com.questtime.vr/files/ /tmp/backup/
```

**Never `mkdir` the app's own folder after an uninstall.** This cost a cycle and the
symptom does not name its cause. Uninstalling removes
`/sdcard/Android/data/com.questtime.vr` entirely; creating it again from `adb shell`
makes it owned by **shell**, and the app then cannot read its own directory - every
file fails with `EACCES (Permission denied)` from `readPaired`, which reads like a
storage-permission problem and is not. A healthy one is owned by the app's uid:

```bash
adb shell ls -ld /sdcard/Android/data/com.questtime.vr
# drwxrws--- ... u0_a173 ext_data_rw      right
# drwxrws--- ... shell   ext_data_rw      wrong - rm -rf it and launch the app
```

Launch the app once and let Android create it, then push. `reference/to_headset.sh`
does the whole sequence.

**A great deal can be settled over the cable before anyone puts the headset on.**
The decode runs on a worker before the session starts, so `logcat` answers most of
the questions a headset session would otherwise be spent on - whether a file opens,
whether its header was recovered, how wide one node came out, how many hot spots
were found. Only the compositor's own behaviour needs eyes. Launch a file straight
in and read the log:

```bash
adb shell "am start -n com.questtime.vr/.VrActivity \
    --es com.questtime.vr.PATH '/sdcard/Android/data/com.questtime.vr/files/NAME'"
adb shell sleep 9      # on the device: the Mac's own `sleep` is not always available
adb logcat -d -s QuestTimeVR:V | grep -E "opening|hot spots|panorama |decode failed"
```

Launch them one at a time and wait. Firing several in a row proves nothing: each
open bumps `generation`, so all but the last are discarded mid-decode and the log
shows an `opening` line with no result under it.

**Quest's log buffer rotates in about thirty seconds.** Reading back with `logcat -d`
after the fact will lose lines and look like a bug. Stream it across the event instead.

## Known limits

- Object movies: refused **only when the file has no panorama track**. Scenes mix the
  two - Joshua Tree is 25 panorama nodes and 2 object nodes, Maranello 1 and 4 - and
  the blanket "there is an `obje` track, so refuse" check threw away 25 good
  panoramas and called Maranello an object movie when it has a real panorama in it.
  The two kinds live in separate tracks, so the pano track already holds exactly what
  can be shown. Spinning an object is still not implemented; the summary says
  "with object movies" so nobody wonders where they went.
- Upright-stored cylinders (`'hcyl'`, or blank `panoType` with the flags bit set):
  detected and refused. A survey of nine files across three archives, 1995-2007,
  turned up none — every cylindrical file in the wild is the legacy rotated form, so
  there is nothing to test a fix against. `reference/fetch_wild.sh` and
  `reference/panotype.py` reproduce that survey.
- Hot spots: **navigable, in 1.0 and 2.x alike.** Look at a doorway, a reticle
  appears with the way on named under it, pull the trigger and you are standing in
  the next node. The two versions keep them in different places - `pHot` atoms in
  the node's own pano sample for 1.0, `hots` under `ndhd` in the qtvr track for 2.x -
  and both containers are walked. See [Walking through a doorway] and [2.x scenes].
  Only `'link'` hot spots go anywhere; QuickTime VR's `'url '` and the rest are read
  and ignored, and the reticle stays dark over them so nothing looks clickable that
  is not. A link to an **object** node is dropped for the same reason.
- Multi-node scenes: **implemented, for 1.0 and 2.x alike.** All 7 multi-node files in
  a real archive open a node at a time - CompanyStore (33), Valley Green 6 (35),
  WHouseVR (13), Lincoln Memorial (9) in 1.0; Joshua Tree (25), Point Lobos (6),
  Apple Company Store (6) in 2.x. See [Scenes]. The archive now opens **24 of 27**,
  up from 16; the 3 left are ordinary movies with no panorama track in them at all.
- 2.x scenes list their nodes **by position, not by name.** There is no `strT` beside
  a 2.x node the way 1.0 has one; the readable text in those files belongs to hot
  spots, and that is where it is shown.
- Hand tracking **works** - measured 14 Sep 2026 - and is **not going to be used.**
  It is left wired behind `debug.questtime.hands` and switched off by default. The
  measurement is worth keeping because it corrects a note that stood wrong for
  months: with controllers held or merely powered, every joint reports `0x0` and
  `strength=0.00`; **set them down** and the same code reports `thumbFlags=0xf
  indexFlags=0xf` with the pinch gap tracking between 14 and 45 mm. The device was
  never the problem. What killed it as a feature is [Hand input, and why it is
  switched off] - one pinch arrives as two events a millisecond apart, so a gesture
  meant to open the list also chose a row. Fixing that means driving open, close and
  confirm from a single state machine on `pinchStrengthIndex`, and the controllers
  already do all three without it. Not a gap to close; a road not taken.
- No in-app exit. Use the Meta button, or `adb shell am force-stop com.questtime.vr`.

## Style

Match what is there. The comments explain *why* — particularly where a line encodes
a runtime quirk that looks arbitrary. Do not add unverified decoders or renderers
that silently produce wrong output; refusing with a clear message is better, and the
project has held that line so far.
