#!/bin/bash
# Regenerate the ffmpeg ground truth the JVM tests compare against.
#
# These files are ~193 MB and are not in the repository - run this once after
# cloning, or the tests will quietly *skip* rather than fail (they use JUnit
# assumptions, so a missing fixture is not an error). Needs ffmpeg on PATH.
#
# Everything here regenerates byte-identically; if a file changes, ffmpeg changed
# and the difference is worth understanding before the tests are trusted again.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p truth

command -v ffmpeg >/dev/null || { echo "ffmpeg not on PATH" >&2; exit 1; }

# Cubic files carry a second video track for the hot-spot mask, at the same size
# as the image. Pick by codec, exactly as the app does - the order is a coin flip.
image_stream() {
    ffprobe -v error -select_streams v \
        -show_entries stream=index,codec_name -of csv=p=0 "$1" |
        awk -F, '$2=="mjpeg" || $2=="cinepak" { print $1; exit }'
}

# --- QuickTime VR 1.0: eciqtvr_hr1.mov, 24 Cinepak tiles of 768x104 -----------
# Compared before rotation, so container parsing and Cinepak decoding are
# isolated from the geometry work.
ffmpeg -v error -y -i testdata/eciqtvr_hr1.mov -map 0:v -vf "tile=1x24" \
    -frames:v 1 truth/stacked.png
ffmpeg -v error -y -i truth/stacked.png -f rawvideo -pix_fmt rgb24 truth/stacked.rgb

# --- QuickTime VR 2.x: chapel_hi.mov, 96 Photo-JPEG tiles of 1508x92 ----------
# Two files. The tiles stand in for BitmapFactory, which the JVM test classpath
# does not have - feeding the decoder seam ffmpeg's own tiles factors the codec
# out, so assembly and rotation have nothing to hide behind. The flat panorama is
# what the pipeline must then reproduce: stacked into one column, rotated 90
# degrees clockwise (transpose=1).
chapel=$(image_stream testdata/chapel_hi.mov)
ffmpeg -v error -y -i testdata/chapel_hi.mov -map "0:$chapel" \
    -f rawvideo -pix_fmt rgb24 truth/chapel_tiles.rgb
ffmpeg -v error -y -i testdata/chapel_hi.mov -map "0:$chapel" \
    -vf "tile=1x96,transpose=1" -frames:v 1 \
    -f rawvideo -pix_fmt rgb24 truth/chapel_flat.rgb

# --- Cubic: street-1.mov, six Photo-JPEG faces of 696x696 --------------------
# Faces only; the orientation is settled by reference/cubemap.py, not by ffmpeg.
street=$(image_stream testdata/street-1.mov)
ffmpeg -v error -y -i testdata/street-1.mov -map "0:$street" \
    -f rawvideo -pix_fmt rgb24 truth/cube_tiles.rgb

for f in stacked.rgb chapel_tiles.rgb chapel_flat.rgb cube_tiles.rgb; do
    printf 'truth/%-20s %10d bytes\n' "$f" "$(wc -c < "truth/$f")"
done
