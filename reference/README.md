# reference/ — the desktop side

The Python reference decoder, the ffmpeg ground truth, and the sample panoramas the
JVM tests run against.

## What is not in this repository

Neither `testdata/` nor `truth/` is checked in. The sample panoramas are other
people's photographs, and the ground truth is about 195 MB that regenerates exactly.

**The tests skip rather than fail when a fixture is missing.** That is deliberate —
without the samples you can still run what can be run — but it means a clone with no
fixtures reports a green build having verified almost nothing. The Gradle test task
warns when anything was skipped; believe the warning over the green.

## Restoring the fixtures

Put the four files below in `testdata/`, then:

```bash
./reference/make_truth.sh
```

That writes the seven files the tests actually read — `truth/stacked.rgb`,
`truth/chapel_tiles.rgb`, `truth/chapel_flat.rgb`, `truth/cube_tiles.rgb` and
`truth/lincoln_node{0,4,8}.rgb` — and needs `ffmpeg` on PATH. All of them regenerate
byte-identically; if one changes, ffmpeg changed, and that is worth understanding
before the tests are trusted again.

## The four samples

Enough detail to confirm you have the right file. Provenance is deliberately blank
rather than guessed — fill it in if you know it.

### `eciqtvr_hr1.mov` — QuickTime VR 1.0, cylindrical

| | |
|---|---|
| size / sha256 | 539,106 bytes · `434b5bf15b9a7c3405c9037b2b059aade6c4534b81472d6e7a6f4bc9a71941a7` |
| dated | 5 September 1995 |
| tiles | 24 × Cinepak (`cvid`), 768×104 |
| panorama | 2496×768 after stacking and rotating, 360° × 85° |
| exercises | `DecoderTest` — the Cinepak path, all 5,750,784 channels against ffmpeg |
| provenance | *unrecorded* |

The one file whose descriptor and pixels disagree: `vPan` claims ±42.5° where the
pixel geometry implies ±44.03°. The app trusts the pixels.

### `chapel_hi.mov` — QuickTime VR 2.x, cylindrical

| | |
|---|---|
| size / sha256 | 8,399,880 bytes · `19f86d4de36306ea64785919e189f3c2d515288f6d50d8665012d47cc92d91f7` |
| tiles | 96 × Photo-JPEG (`jpeg`), 1508×92 |
| panorama | 8832×1508, 360° × ±28.2093° |
| exercises | `Qtvr2Test` — the 2.x container, atom-container descriptor, assembly and rotation, byte for byte |
| provenance | *unrecorded* |

Wide enough to exceed the Quest's 8192 swapchain limit, so it is also the case that
proves `downscaleToFit` preserves angles.

### `street-1.mov` — QuickTime VR 2.x, cubic

| | |
|---|---|
| size / sha256 | 327,519 bytes · `5cc6bb9b2f9b0d460d35ea4d37d2fef041552552ee287ef9ab2d3e37706aa863` |
| faces | 6 × Photo-JPEG (`jpeg`), 696×696 |
| geometry | pan 0→360°, tilt ±40.5°, with a `cuvw` atom beside `pdat` |
| second track | `smc` hot-spot mask, same dimensions and sample count — pick by codec, not by order |
| exercises | `CubeTest` — cubic detection, angles, and that the gradient fill leaves no voids |
| provenance | *unrecorded* |

Carries a real hot-spot track that nothing currently uses.

### `lincoln9.mov` — QuickTime VR 1.0, cylindrical, nine nodes

| | |
|---|---|
| size / sha256 | 8,068,969 bytes · `b068dd2df3aafa21ba9925f481a1521f64275d929112436db58361fe70f7bb59` |
| tiles | 216 × Cinepak (`cvid`), 768×168 — 24 per node, back to back in node order |
| each node | 4032×768 after stacking and rotating |
| nodes | `DCwalk.01` … `DCwalk.09`, ids 1–9, linked as a walk |
| second track | a 192×84 Cinepak *low-resolution copy*, 12 tiles per node |
| third track | `smc` hot-spot mask, same dimensions and sample count as the image |
| exercises | `SceneTest` — the node partition against ffmpeg, and image-track choice |
| provenance | `Imports/Lincoln Memorial (9 nodes)`, flattened |

Derived rather than found, because the original is a classic dual-fork Mac file whose
`moov` lives in the resource fork:

```bash
reference/flatten.py -o /tmp/flat "Imports/Lincoln Memorial (9 nodes)"
cp "/tmp/flat/Lincoln Memorial (9 nodes).mov" reference/testdata/lincoln9.mov
```

The low-resolution second track is why this file is the fixture and not
`WHouseVR.MOV`, which has the same arrangement. Both Cinepak tracks decode perfectly
well, so nothing but the descriptor's scene size distinguishes the real image track
from the scrubbing copy — and for months the right one was picked only because it
happens to be stored first.

`NodeTableTest` reads the node table from `Imports/` directly instead, resource fork
and all, because the thing it is checking is the names and ids as the author wrote
them — and `WHouseVR.MOV`'s ids skip 6, 11 and 13, which is the case that stops an
index ever being used as an id.

## The wild corpus

`testdata/wild/` holds QuickTime VR files pulled off the open web — files nobody
chose for this project, which is the whole point. `WildFilesTest` checks that each is
either classified correctly or refused by name, and the class skips when they are
absent.

```bash
./reference/fetch_wild.sh      # download them, then print what each one is
```

What is in there, and what it proves:

| file | what it is | what it exercises |
|---|---|---|
| `p31.mov`, `MonaLisa.mov`, `chichen-itza.mov` | cubic, `panoType='cube'` | cubic detection on three unrelated authoring eras |
| `apollo12.mov`, `taj_mahal.mov` | cylindrical, blank `panoType`, rotated | the legacy storage form, from files we did not curate |
| `ff_romscene.mov` | cylindrical, Sorenson `SVQ1` | an unsupported codec, refused *by name* |
| `arounder4.mov` | not QTVR at all — plain `mp4v` | a file that only looks like ours because it shares the container |

The survey behind these: nine files, three archives, 1995 to 2007. Every cylindrical
one is stored rotated. Not one `'hcyl'` panorama turned up, which is why that case is
refused rather than implemented.

## The Mac archive fixture

`testdata/mac-archive.zip` is what `AppleZipTest` checks the resource-fork recovery
against, and it has to be made on a Mac because the thing being tested is what Finder
puts in a zip. Three files: one rescuable with a non-ASCII name, one rescuable plain,
one that already carries its own `moov`.

```bash
mkdir -p /tmp/qtvr_fix
cp -p "Imports/Green Spiky Land (KPT Bryce™)" \
      "Imports/Radio City Music Hall" \
      "Imports/Monument Valley" /tmp/qtvr_fix/
ditto -c -k --sequesterRsrc --keepParent /tmp/qtvr_fix reference/testdata/mac-archive.zip
```

`ditto` is what right-click Compress runs, which is the point — an archive made with
`zip(1)` carries no `__MACOSX/` sidecars and the test would prove nothing. The SHA-256
values the test asserts come from running `applezip.py` over the same archive, so the
Kotlin and the Python are held to each other rather than both to my say-so.

The other twelve tests in that class build their own archives in memory and run on a
bare clone.

## The tools

| | |
|---|---|
| `qtvr.py` | the Python reference decoder the Kotlin one was ported from, already diffed to byte equality against ffmpeg |
| `cubemap.py` | reimplements GL's cubemap sampling on the desktop — how the face order and mirroring were settled. Use it before guessing at cube orientation; it is far faster than cycling properties in a headset |
| `scan.py` | finds QuickTime VR files on a machine |
| `verify.py` | diffs a decode against ffmpeg output |
| `make_truth.sh` | regenerates every ground-truth file the tests read |
| `panotype.py` | says what flavour a QuickTime VR file is - version, nodes, geometry, stored orientation, codecs - without decoding a pixel |
| `fetch_wild.sh` | downloads the wild corpus above |
| `flatten.py` | appends the resource-fork `moov` to a copy of the data fork, turning a classic dual-fork Mac movie into one an `adb push` can carry |
| `applezip.py` | recovers resource forks from a Mac-made `.zip` via its `__MACOSX/._Name` AppleDouble sidecars, then flattens. The reference implementation for zip import in the upload server - it turns 13 of the 27 files in `Imports/` from refused into openable |
