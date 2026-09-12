# Progress log

Newest first. Each entry records what changed and, where it matters, what was
actually verified rather than assumed.

---

## Getting the page honest before publishing — 12 Sep 2026

Asked what the upload page would report for music on a clone that ships no track. The
answer was worse than "nothing": it said **"Using the track built into the app."**
`musicInfo` only ever looked for an uploaded file and could not see whether a bundled
one existed, so absence of an upload was reported as presence of a bundle. A page
whose entire job is to say what is on the headset, lying by default, to the one
audience that has never used it.

Three states now - uploaded, bundled, none - and verified the honest way: moved
`res/raw/ambience.mp3` aside, rebuilt, and asked the running app rather than reasoning
about it. Fresh clone reports `{"state":"none"}` and the page offers to take a track.
Then restored and re-uploaded the mix.

Also added the library preview that was asked for. `/files` is built from the same
`FileList` helpers the picker uses, so the two cannot drift. It counts loose files and
folder contents, which makes its number larger than the picker's header - 21 against 4
here - so both now say which they mean. Two correct numbers that look like a bug is
still a bug in the writing.

## Background Music and controller turning — 12 Sep 2026

Two additions, both changeable without a rebuild.

**Background Music.** The upload page grew a second section. A track sent to `/music`
is kept in the app's private files - not the external folder, so the picker never
lists it - and Ambience prefers it over the bundled one. Verified end to end: a
12-second tone, then a 29-minute mix, with the log confirming it picked the uploaded
file up ("uploaded track, 25503 KB") and dropped in at 6m22s.

Testing with the short tone found a bug worth remembering: it reported "its length
could not be read" when the length read perfectly and the track was simply 12 seconds
long. Two different zeros, conflated by rounding to minutes before branching. It now
branches on milliseconds. A tiny thing, but it would have sent someone off to
re-encode a file that was fine.

**Controller turning.** Either thumbstick flicked left or right snaps 45 degrees. One
action with subaction paths for both hands; engages past 0.7 and re-arms below 0.3 so
a resting thumb does not spin the world. Yaw is added to each arc's theta and to the
cube layer's orientation; the polar caps are flat colour, so a Y rotation does nothing
to them and they are left alone.

Confirmed working in the headset - **and it went in inverted.** The sign came from
arguing "you turn right, so the world swings left", which sounds right and is not: the
layer pose rotates with you, so a rightward flick is a positive yaw. Flipped, and the
comment now records the wrong reasoning rather than just the right answer, because
that is the part that will be re-derived.

Worth noting how that landed: it was the one thing flagged as unverifiable from the
host, with the specific prediction that direction was the thing to check and that an
inversion would be a one-character fix. Both held. Naming what you could not test, and
what its failure would look like, turned a blind spot into a single round trip.

## Browser uploads, and a silent music regression — 12 Sep 2026

**Confirmed in the headset.** Music, folder browsing, the picker's server address
line and browser uploads all work. The address line was the one thing no host-side
tool could see - `screencap` gives 0 bytes for the panel and `uiautomator` omits the
view - so it took a person looking.

Three things: the music had stopped, the upscaled library was rejected, and uploading
wanted to stop requiring a cable.

**The music was a race I built.** `open()` sets `wanted = true` and kicks off an async
prepare; `MainActivity.onResume` then fires *as a side effect of launching the
immersive activity* - Horizon OS keeps both alive - and its `pause()` set
`wanted = false` before preparation finished. `onPrepared` checked `wanted`, found it
false, and returned without starting. Nothing to see in a log, because the state
machine did exactly what it was told. The folder-browsing change had made `refresh()`
slower, which shifted the timing enough to expose it. Ambience now ignores a pause
within 2.5 s of an open, and says so when it does.

**The upscale was reverted** - the 17 Real-ESRGAN panoramas removed and the originals
restored. It invented detail the 1995 captures never had, and that turned out not to be
wanted. `reference/upscale.py` and the downloaded upscaler were removed outright when the
project was prepared for publication - keeping a tool that alters a historical record,
unused, was an invitation rather than an option.

**The upload server.** Port 8080, address shown in the picker, files land in the app's
own folder. Hand-rolled on a `ServerSocket` rather than pulling in a library: two
routes and one multipart parse. Every upload is run through a new `Qtvr.inspect`
before being stored, and nothing that will not open is kept.

Driving it with `curl` over `adb forward` against real archive files found two bugs in
that checker straight away, which is the argument for testing with real files:

- **Object tracks are `handler == "obje"`, not `format == "obji"`.** The old check
  never matched anything. Maranello, a pure object movie, came back `opens: true`.
- **`inspect` and `extract` disagreed.** Fixed by mirroring extract's refusal order
  exactly. A unit test had already caught the same class of error once - the cubic
  rotation check, which extract never reaches because `isCubic` diverts first.

Real results now: a dual-fork classic file is told its header is in a resource fork a
browser cannot carry, and to flatten it; a 25-node scene is told it is multi-node and
carries object movies; an extensionless file is saved with `.mov` appended so the
picker can see it.

**Two debugging lessons, both costly.** `screencap` returns 0 bytes for the 2D panel
and black for the immersive view, and `uiautomator` omits the address TextView even
though logging proves the text is set - so a UI detail cannot be confirmed from the
host at all. And a *stopped* VrActivity produces the full bring-up log and then
silence, which reads exactly like a dead renderer; it is just nobody wearing the
headset. I chased both for several turns before checking `mResumed`.

## The hairline, and a renderer that did not fix it — 10 Sep 2026

A thin dark line at the back of cylindrical panoramas, and a second one around the
polar cap. Visible in the headset, invisible in a cast stream - which was itself the
clue that pointed at stereo.

Three things were tried. Two helped:

- **Clamping centralAngle was wrong at the total.** `[0, 2*pi)` applies to each
  *layer*, and with four arcs none is near 2*pi. Clamping the total left a 0.0098
  degree wedge unclaimed directly behind the viewer. Now clamped only when a single
  arc really carries the whole turn.
- **The cap sat at a different depth from the cylinder.** A flat quad at
  radius*tan(halfV) against a cylinder at radius means a step in distance at the rim,
  which each eye resolves differently - a stereo discontinuity, and precisely why a
  mono cast could not show it. Everything moved to 500 m, effectively infinity.
- **Arc bleed** of 0.08 degrees, so no two layer edges are coincident. Made the
  remaining line thinner. Not gone.

Then the real attempt: **a full-screen shader into the eye buffers**, replacing the
arcs and caps entirely. Each pixel becomes a direction and samples the panorama
directly - wrap by REPEAT, poles by CLAMP_TO_EDGE over the gradient's already
converged rows, translation ignored so the image sits at true infinity. No layer
boundaries anywhere by construction. It rendered correctly at 1680x1760 per eye.

**The line was still there.** Reverted at the user's call, and rightly: rendering into
an eye buffer discards the compositor's ability to sample a 4032-wide panorama at full
display resolution, and that is worth more than the line costs. `sky.h` deleted; the
radius and bleed improvements kept, since neither costs resolution.

Worth being honest about what that experiment did and did not prove. It is *not* a
clean result: the shader path introduced trilinear mipmapping, and hardware mip
selection collapses exactly at an `atan2` wrap, where du/dx jumps a whole texture
width - a well-known seam that lands in the same place. So the line surviving may
mean one seam was traded for another rather than that layer boundaries were innocent.
Anyone returning to this should disable mipmapping, or feed `textureGrad` analytic
derivatives, before drawing conclusions.

Remaining untested candidate: the panoramas' own content at the wrap. Column 0 and
column W-1 were checked for a black border and there is none, but they were only
compared for brightness, not for whether the two edges actually align. A stitching
mismatch in a 1990s file would show at exactly that azimuth under any renderer.

## Ambience — 10 Sep 2026

A half-hour Frutiger Aero playlist, dropped in at a random point on every open, fading
in over 2.5 s and out over 1.2 s. The random seek keeps 30 s clear of the track's end
so a fade-in is never cut off by the loop wrapping a second later, and the music
starts when the *decode* starts rather than when the panorama appears — decoding takes
several seconds and the fade covers exactly that gap.

The interesting part was "nothing in the main menu". The obvious wiring — start in
`VrActivity`, stop in its `onPause` — does not work, and the device said so plainly:
after stepping back to the picker the player was still `state:started`. **Horizon OS
keeps the 2D panel and the immersive activity alive at the same time**, so
`VrActivity` never sees `onPause` at all. Lifecycle callbacks simply do not carry the
"which of these is the user looking at" signal here. `Ambience` is now process-wide
and `MainActivity.onResume` pauses it — the picker asserting its own silence, rather
than the viewer trying to infer it.

Verified on device through the full cycle: picker silent, panorama `state:started`,
back to picker `state:paused`, open another `state:started`. Seek positions across
runs: 24m4s, 5m6s, 1m56s, 7m29s, 13m29s, 15m15s.

Also confirmed on device this session: the `onNewIntent` switching fix from
yesterday, which had never been flown. Three panoramas opened back to back, each with
its own decode, a new session claiming the old one, and a fresh random seek.

The track is gitignored, like the sample panoramas — it is someone else's music. So
`Ambience` looks the resource up by **name** rather than as `R.raw.ambience`, which
means a clone without it still compiles and just runs silent. A hard resource
reference would have turned "no music" into "no build".

## The Imports archive, and two picker bugs — 9 Sep 2026

27 classic QTVR files arrived in `Imports/`. Eleven read as "not a QuickTime file"
and the reason turned out to matter: **the `moov` is in the resource fork**. The data
fork holds only `mdat`. `adb push` drops resource forks, so these could never have
worked on a headset. `reference/flatten.py` appends resource 'moov' #128 to the data
fork — no offset rewriting, since a dual-fork movie's chunk offsets already address
the data fork from its start. All 13 dual-fork files became valid QTVR 1.0 after
flattening; nothing was skipped.

What the archive actually holds, after flattening: 16 single-node cylindrical
panoramas (all Cinepak, all the legacy rotated form), 4 multi-node scenes (9, 13, 33
and 35 nodes — refused), 4 object movies (refused), and 3 ordinary `rpza` movies with
no panorama track. So 16 of 27 are viewable today, and the multi-node refusal is now
the limit that costs the most.

Also worth knowing: most of these files have **no extension at all**, because classic
Mac files carried type/creator codes instead. The picker filters on `.mov` and would
not see them; `flatten.py` writes `.mov` names, which is what makes the import
workflow work.

Two bugs reported alongside:

**Duplicates in the picker.** The search directories overlap, and dedupe was keyed on
absolute path — which only ever collapses a directory against itself. Keyed on name
and size now, with the first search directory winning so the app-private copy is the
one opened. Extracted to `FileList.dedupe` and tested on the JVM (5 tests).

**Picking a second file did nothing.** `VrActivity` is `launchMode="singleTask"`, so
the second launch does not call `onCreate` — it delivers to `onNewIntent`, which was
not overridden. The path was dropped and the old panorama stayed up, which read as
the picker being dead. Now overridden, with a generation counter so a decode that
finishes after a newer one started cannot seize the session, and so a pending
"give up and finish" from a failed open cannot close the activity out from under a
later success. The old panorama is deliberately left running during the decode —
native's `claimSession` hands over at the moment the new one is ready, which beats
staring into a black void for several seconds.

28 tests pass. **The switching fix has not been flown** — no headset was connected.

## Format coverage, checked against the spec — 9 Sep 2026

Went looking for what QuickTime VR can be, rather than what these three files happen
to be. Two silent-wrong-output paths fell out, both of which the samples hid.

**`panoType` has four states.** `'hcyl'`, `'vcyl'`, `'cube'`, or blank with the low
bit of `flags` (offset 72, immediately before `panoType` at 76) carrying it instead.
Everything before QuickTime 5 stored panoramas rotated 90° CCW; QuickTime 5 stopped
requiring it. The app rotated unconditionally. Dumping the real bytes: chapel is blank
with the bit **clear** (rotated), street is `'cube'` with the bit **set** (not
rotated, and the cube path never rotates anyway). So both fixtures are the legacy
form, and the unconditional rotation was correct by luck. An upright `'hcyl'` file
would have rendered lying on its side with no error at all. Now parsed and refused —
refused rather than fixed, because there is no sample of that form to test a fix
against, and this is exactly the case the project's style rule is about.

**Multi-node scenes composite into nonsense.** One `pano` sample per node, but the
image track holds every node's tiles. Taking node one's descriptor and then decoding
every sample stacks all the nodes into one very tall column. All three fixtures are
single-node (`qtvr` and `pano` tracks: one sample each), so this had never shown. Now
detected by sample count and refused.

Also confirmed from the spec that the descriptor offsets already in `parseV2` match
`QTVRPanoSampleAtom` field for field, which is worth knowing — they were derived by
inspection originally.

18 tests now, both ffmpeg byte-exact comparisons still passing, so neither guard fires
on a good file.

Sources: Apple, *Inside QuickTime VR*, chapters 5 and 6.

**Then went looking for an `'hcyl'` file to test against, and there isn't one.**
Nine files, three archives, 1995 to 2007: the ffmpeg sample archive (`ff_romscene`,
2004, Sorenson), panoramas.dk via the Wayback Machine (six files, 2005-2007), and
the two originals. Every cylindrical one is blank-`panoType` and rotated; every cubic
one sets the flags bit exactly as documented. `'hcyl'` is in the specification and,
as far as this survey goes, nowhere else - plausibly because QuickTime 5-era tools
kept writing the rotated form for QuickTime 4 compatibility, and put their new work
into cubic instead.

So the refusal is tested by taking the chapel and flipping the one bit that
distinguishes the two forms, plus a variant that writes `'hcyl'` into panoType. That
tests the app's reading of the descriptor, which is the part that exists - not what a
real upright file's pixels look like, which is the part that cannot be known without
one.

The downloads were kept as a **wild corpus** (`reference/testdata/wild/`,
`fetch_wild.sh`, `WildFilesTest`). Worth more than the hcyl hunt in the end: files
nobody chose for this project, including one that is not QTVR at all (`arounder4`,
plain `mp4v`) and one with a codec we refuse (`SVQ1`). 23 tests now.
`reference/panotype.py` classifies a file without decoding it.

## Tripwire sweep before publishing — 9 Sep 2026

Six defects, none of which had shown up in a headset yet. Found by reading rather
than by hitting them.

The one that mattered: the four-arc split had a guard that reduced `arcs` until it
divided the texture width evenly. Any width not a multiple of four fell through to
`arcs = 1` — the single near-360° arc that blanks half the cylinder, the bug that
guard was sitting next to. Nothing constrains the width to be even: `downscaleToFit`
does `(width * scale).toInt()`. Slices are now `i * W / arcs`, differing by a texel
where they must, each with `centralAngle` and `aspectRatio` scaled by its own `w/W`
so the ratio the runtime derives the horizon from is unchanged. Checked over every
width 1..8192 against arc counts 1..16: slices tile exactly, and every arc's vertical
extent agrees to 1e-6.

The rest:

- `run()` returned early on any bring-up failure without calling `shutdown()`, so a
  failed attempt leaked the EGL display and made the *next* attempt fail for a
  different reason. Bring-up and teardown are now paired.
- Relaunching while the previous detached render thread was still winding down hit
  `g_running.exchange(true)` and returned silently. Kotlin sat on "entering VR" over
  a black screen with nothing running to report why. `claimSession()` now asks the
  old session to stop and waits up to 3s; `nativeStart` returns a boolean so Kotlin
  surfaces the failure immediately rather than after its 2.5s poll.
- The Activity global ref was only released when the *next* session replaced it, so
  the last session of a process kept an Activity alive. Released with the thread now.
- `g_error` was written by the render thread and read from JNI with no lock.
- Sample-table counts were trusted straight off disk. A truncated file reached the
  user as an array index; now the counts are clamped to what the atom holds and a
  sample pointing past the end says so in a sentence.

Also dropped the 64 MB `ByteBuffer` the Activity was holding for "the lifetime of the
session" — native copies it before `nativeStart` returns, so it was a second copy
kept for nothing.

Verified: 16 tests still pass, both ffmpeg byte-exact comparisons among them, so the
parser hardening changed no decoded pixel. Native compiles clean; not yet flown on
the headset.

Also decided, ahead of publishing: **the sample panoramas do not go in the repo** —
they are other people's photographs, and a screen recording will carry the demo
instead. `reference/README.md` records each one's size, hash and geometry so the
right file can be recognised, and `make_truth.sh` now regenerates all four
ground-truth files rather than just the 1.0 one, which had no recipe at all for the
chapel or the cube.

That exposed a sharper problem: the tests skip on a JUnit assumption when a fixture
is missing, so a clone without them reports a *green* 16 tests having checked
nothing. Skipping is still the right behaviour, but the test task now names every
skipped test and warns. Confirmed by hiding the fixtures: 16 of 16 skipped, warning
shown, build still green — which is exactly why the warning had to exist.

## Cubic gradient fill — 4 Sep 2026

Cubic panoramas had no gradient treatment at all, so looking down gave a hard black
dome with ragged triangles around its edge. The triangles were never a seam bug: a
cubic file converted from a ±40.5° cylindrical original has its capture edge cutting
diagonally across the cube's faces, and face corners that fall inside the captured
band while their neighbours do not read as triangles.

`CubeCaps` fills by **angle**, not per face — any direction outside the captured tilt
range gets the gradient, whichever face it lands on, which makes the fill continuous
across face boundaries by construction. Edge colour is sampled as a ring around the
horizon at the capture edge, low-passed to 12 control points, and blended toward a
shared pole mean.

Moved the face permutation and horizontal mirror out of the renderer into
`Qtvr.toGlOrder`, so the fill can reason about real directions and so the geometry is
testable without a headset. Native now uploads faces straight into their cubemap
slots.

Verified: `CubeTest.noBlackVoidsRemain` (0.23% near-black left, all genuine shadow),
plus a desktop render through `reference/cubemap.py`. 16 tests passing.

## Cubic panorama support — 4 Sep 2026

`street-1.mov` turned out to be a genuine cubic panorama, not something to refuse.
Six 696×696 Photo-JPEG faces, pan 0→360°, tilt ±40.5°, with a `cuvw` atom beside
`pdat`. Rendered via `XR_KHR_composition_layer_cube` with a real cubemap swapchain.

Orientation was wrong first try — every side face mirrored, shop signs reading
backwards. Cause: GL's cubemap convention is left-handed. An attempt to find the
right permutation automatically, by scoring seam discontinuity, **failed** — top
candidates tied and several scored below 1, because this panorama's face joins land
on plain sky and road. Threw the metric away, reasoned about the sampling math, and
confirmed by rendering on the desktop.

Also fixed: track selection was picking whichever video track came first, which for
cubic files is a coin flip between the image and the `smc` hot-spot mask.

## QuickTime VR 2.x — 4 Sep 2026

Found `chapel_hi.mov` by scanning the machine rather than writing a parser blind.

2.x moves the descriptor from the sample description into the pano track's sample,
wrapped in a QuickTime atom container (`sean` → `pdat`), with Float32 angles. The
image pipeline is identical to 1.0, so it was all reuse.

Two things this shook out: a fixed cap height gave ±68.8° on the narrow 1995 pano but
only ±36° on the wide chapel, because a cylinder's pixel radius is `width / 2π`; and
panoramas wider than 8192 exceed the swapchain limit even though textures allow
16384.

Verified byte-exact against ffmpeg.

## Gradient caps and edge feather — 4 Sep 2026

Extended panoramas vertically with a gradient drawn from their own edges — a cylinder
layer covers more angle simply by having more rows, and the original pixels do not
move. Then polar cap quads for the disc a cylinder can never reach.

Caps were initially submitted *after* the arcs and painted over them as a visible
square. Composition layers composite in submission order, not by depth.

A visible disc remained after that, most likely `imageRect` origin convention. Rather
than guess, both poles now converge on one shared mean colour, which makes the
convention stop mattering.

## First light — 3 Sep 2026

Rendered for the first time on a Quest 3. Four bugs stood between "810 frames
rendered, no errors" and anything actually appearing:

1. `centralAngle` passed as exactly 2π — out of the spec's half-open `[0, 2π)`.
2. Swapchain filled once at startup and never re-acquired.
3. `radius = 0` (spec-legal infinite cylinder) drawing nothing on this runtime.
4. Texture uploaded without the vertical flip a GL 2D texture needs.

The frame counter reported 100% success throughout, which is why the FBO readback
diagnostic mattered — proving the texture was byte-correct is what moved the search
to the layer.

Then: one near-360° arc blanks half the cylinder depending on which way you face the
seam. Four 90° arcs fixed it.

## App scaffolding — 3 Sep 2026

Self-contained toolchain under `toolchain/`. Kotlin decoder ported from a Python
reference that had already been diffed against ffmpeg to byte equality. First
on-device launch never started the process at all — Horizon OS intercepts apps that
do not declare hand tracking and shows a controllers dialog instead.

## Extraction — 3 Sep 2026

`eciqtvr_hr1.mov`, a QuickTime VR 1.0 node from 5 September 1995. 24 Cinepak tiles of
768×104, stacked and rotated 90° clockwise into a 2496×768 cylinder covering 360° ×
85°.

Two Cinepak traps: codebooks are inherited from the previous strip before selective
updates apply, and the green channel truncates toward zero rather than flooring.
Fixing both took the decoder from 84% to 100% channel-exact against ffmpeg.

---

## Open

- **Hand tracking / in-VR menu.** `aimValid=1`, `strength=0.00`, all joints `0x0`.
  Believed to be controllers being powered; unconfirmed. Everything downstream — menu
  panel, file switching, passthrough — is blocked on this.
- **Passthrough** needs `XR_FB_passthrough`. The runtime enumerates only
  `OPAQUE`, so it is not reachable by switching blend mode.
- **Hotspots and multi-node scenes.** The street sample carries a real hot-spot track,
  unused. Restoring node-to-node navigation is the feature that would make this more
  than a photo viewer.
- **No in-app exit.**
