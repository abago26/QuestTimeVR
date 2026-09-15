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

# -ignore_editlist is on every decode below, and it is load-bearing rather than
# tidiness. A QuickTime edit list remaps a track's timeline, and ffmpeg honours it:
# Joshua Tree's shared image tracks carry lists that alternate empty edits with the
# four node segments, so by default ffmpeg pads the gaps and emits the first node
# twice while dropping the last. That produced ground truth that disagreed with a
# decoder which was in fact correct, and cost an afternoon. Reading samples in
# storage order - what QuickTime VR itself does, since a node addresses tiles by
# index and not by time - is what this has to compare against.
#
# The other four samples carry a single trivial edit, so the flag changes nothing
# for them; it is applied uniformly so no future fixture can be caught by it.

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
ffmpeg -v error -y -ignore_editlist 1 -i testdata/eciqtvr_hr1.mov -map 0:v -vf "tile=1x24" \
    -frames:v 1 truth/stacked.png
ffmpeg -v error -y -i truth/stacked.png -f rawvideo -pix_fmt rgb24 truth/stacked.rgb

# --- QuickTime VR 2.x: chapel_hi.mov, 96 Photo-JPEG tiles of 1508x92 ----------
# Two files. The tiles stand in for BitmapFactory, which the JVM test classpath
# does not have - feeding the decoder seam ffmpeg's own tiles factors the codec
# out, so assembly and rotation have nothing to hide behind. The flat panorama is
# what the pipeline must then reproduce: stacked into one column, rotated 90
# degrees clockwise (transpose=1).
chapel=$(image_stream testdata/chapel_hi.mov)
ffmpeg -v error -y -ignore_editlist 1 -i testdata/chapel_hi.mov -map "0:$chapel" \
    -f rawvideo -pix_fmt rgb24 truth/chapel_tiles.rgb
ffmpeg -v error -y -ignore_editlist 1 -i testdata/chapel_hi.mov -map "0:$chapel" \
    -vf "tile=1x96,transpose=1" -frames:v 1 \
    -f rawvideo -pix_fmt rgb24 truth/chapel_flat.rgb

# --- Cubic: street-1.mov, six Photo-JPEG faces of 696x696 --------------------
# Faces only; the orientation is settled by reference/cubemap.py, not by ffmpeg.
street=$(image_stream testdata/street-1.mov)
ffmpeg -v error -y -ignore_editlist 1 -i testdata/street-1.mov -map "0:$street" \
    -f rawvideo -pix_fmt rgb24 truth/cube_tiles.rgb

# --- Multi-node: lincoln9.mov, nine nodes of 24 Cinepak tiles each ------------
# One panorama per node, assembled and rotated exactly as chapel_flat.rgb is, so
# the comparison covers the node partition *and* the assembly in one image rather
# than dumping all 216 frames.
#
# First, middle and last. An off-by-one in the partition hides in the middle and a
# stride error shows at the ends, and three 9 MB images is a great deal less than
# the 84 MB every frame would cost.
#
# Note the track is picked as stream 0 rather than by codec: this file has TWO
# Cinepak tracks - 768x168 and a 192x84 low-resolution copy - and image_stream
# would take whichever came first. The app tells them apart by the descriptor's
# scene size; here the stream index is simply pinned.
if [ -f testdata/lincoln9.mov ]; then
    for k in 0 4 8; do
        start=$((k * 24))
        ffmpeg -v error -y -ignore_editlist 1 -i testdata/lincoln9.mov -map 0:0 \
            -vf "select='gte(n\,$start)*lt(n\,$((start + 24)))',tile=1x24,transpose=1" \
            -frames:v 1 -f rawvideo -pix_fmt rgb24 "truth/lincoln_node$k.rgb"
    done
else
    echo "testdata/lincoln9.mov absent - SceneTest will skip" >&2
fi

# --- QuickTime VR 2.x scene: joshua25.mov, 25 nodes over 15 image tracks -------
# A different arrangement from 1.0: each node names its own image track, and nodes
# may share one, which is then split between them in node order. The three chosen
# cover each way a node can sit in that: node 0 first in a shared track, node 9
# *second* of four sharing a 96-frame track, node 24 last in the scene and second
# of two. Streams are pinned by index because a track id is not a stream index.
if [ -f testdata/joshua25.mov ]; then
    #        node stream first-frame
    for spec in "0 3 0" "9 7 24" "24 33 24"; do
        set -- $spec
        ffmpeg -v error -y -ignore_editlist 1 -i testdata/joshua25.mov -map "0:$2" \
            -vf "trim=start_frame=$3:end_frame=$(($3 + 24)),tile=1x24,transpose=1" \
            -frames:v 1 -f rawvideo -pix_fmt rgb24 "truth/joshua_node$1.rgb"
    done
else
    echo "testdata/joshua25.mov absent - the 2.x half of SceneTest will skip" >&2
fi

for f in stacked.rgb chapel_tiles.rgb chapel_flat.rgb cube_tiles.rgb \
         lincoln_node0.rgb lincoln_node4.rgb lincoln_node8.rgb \
         joshua_node0.rgb joshua_node9.rgb joshua_node24.rgb; do
    [ -f "truth/$f" ] || continue
    printf 'truth/%-20s %10d bytes\n' "$f" "$(wc -c < "truth/$f")"
done
