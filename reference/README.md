# reference/ — the desktop side

The Python reference decoder, the ffmpeg ground truth, and the sample panoramas the
JVM tests run against.

## What is not in this repository

Neither `testdata/` nor `truth/` is checked in. The sample panoramas are other
people's photographs, and the ground truth is 193 MB that regenerates exactly.

**The tests skip rather than fail when a fixture is missing.** That is deliberate —
without the samples you can still run what can be run — but it means a clone with no
fixtures reports a green build having verified almost nothing. The Gradle test task
warns when anything was skipped; believe the warning over the green.

## Restoring the fixtures

Put the three files below in `testdata/`, then:

```bash
./reference/make_truth.sh
```

That writes the four files the tests actually read — `truth/stacked.rgb`,
`truth/chapel_tiles.rgb`, `truth/chapel_flat.rgb`, `truth/cube_tiles.rgb` — and needs
`ffmpeg` on PATH. All four regenerate byte-identically; if one changes, ffmpeg changed,
and that is worth understanding before the tests are trusted again.

## The three samples

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

## The tools

| | |
|---|---|
| `qtvr.py` | the Python reference decoder the Kotlin one was ported from, already diffed to byte equality against ffmpeg |
| `cubemap.py` | reimplements GL's cubemap sampling on the desktop — how the face order and mirroring were settled. Use it before guessing at cube orientation; it is far faster than cycling properties in a headset |
| `scan.py` | finds QuickTime VR files on a machine |
| `verify.py` | diffs a decode against ffmpeg output |
| `make_truth.sh` | regenerates all four ground-truth files |
| `panotype.py` | says what flavour a QuickTime VR file is - version, nodes, geometry, stored orientation, codecs - without decoding a pixel |
| `fetch_wild.sh` | downloads the wild corpus above |
