"""
Check the reference decoder against ffmpeg's own Cinepak output, pixel for pixel.

Ground truth is reference/truth/stacked.rgb - ffmpeg's decode of the same 24 tiles
stacked with tile=1x24, i.e. before the rotation, so this compares decoding alone.
"""

import sys
import qtvr


def stats(a, b):
    n = min(len(a), len(b))
    diff = 0
    worst = 0
    exact = 0
    for i in range(n):
        d = a[i] - b[i]
        if d < 0:
            d = -d
        diff += d
        if d > worst:
            worst = d
        if d == 0:
            exact += 1
    return diff / n, worst, exact / n * 100.0


def main():
    mov = "testdata/eciqtvr_hr1.mov"
    data = open(mov, "rb").read()
    tracks = qtvr.parse_tracks(data)

    print("== tracks ==")
    for t in tracks:
        print(f"  handler={t.handler!r} fmt={t.fmt!r} {t.width}x{t.height} "
              f"depth={t.depth} samples={len(t.sample_sizes)}")

    pano = next(t for t in tracks if t.fmt == "pano")
    info = qtvr.parse_pano(pano.stsd_entry)
    print("\n== pano descriptor ==")
    print(f"  hPan {info.h_pan_start} -> {info.h_pan_end}")
    print(f"  vPan {info.v_pan_top} -> {info.v_pan_bottom}")
    print(f"  scene {info.scene_size_x}x{info.scene_size_y}  frames={info.num_frames}"
          f" ({info.frames_x}x{info.frames_y})")
    print(f"  panorama when upright: {info.pano_width}x{info.pano_height}")

    video = next(t for t in tracks if t.handler == "vide")
    dec = qtvr.Cinepak(video.width, video.height)
    tiles = [bytes(dec.decode(data[o:o + s])) for o, s in video.sample_offsets()]
    mine, w, h = qtvr.stack_tiles(tiles, video.width, video.height)
    print(f"\n== decoded == {len(tiles)} tiles -> {w}x{h}")

    truth = open("truth/stacked.rgb", "rb").read()
    if len(truth) != len(mine):
        print(f"SIZE MISMATCH mine={len(mine)} ffmpeg={len(truth)}")
        return 1

    mean, worst, exact = stats(mine, truth)
    print("\n== vs ffmpeg ==")
    print(f"  mean abs error : {mean:.4f} / 255")
    print(f"  worst channel  : {worst}")
    print(f"  exact channels : {exact:.2f}%")

    open("out_stacked.rgb", "wb").write(mine)
    ok = mean < 1.0 and worst <= 2
    print("\n" + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
