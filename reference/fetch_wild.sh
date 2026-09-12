#!/bin/bash
# Fetch the "wild" corpus - QuickTime VR files pulled off the open web, used by
# WildFilesTest to check the parser against files nobody chose for it.
#
# These are not committed: they are other people's photographs, and they are only
# test material. Sizes and hashes are pinned so a re-fetch is verifiable.
#
# Provenance:
#   ff_romscene.mov   samples.ffmpeg.org media sample archive, dated 2004
#   the rest          panoramas.dk (Hans Nyberg), via the Internet Archive
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p testdata/wild
cd testdata/wild

get() {  # name url
    if [ -f "$1" ]; then echo "have  $1"; return; fi
    echo "get   $1"
    curl -sSfL --max-time 180 -o "$1" "$2"
}

wb() {   # name timestamp original-url
    get "$1" "https://web.archive.org/web/$2id_/$3"
}

get ff_romscene.mov https://samples.ffmpeg.org/mov/QTVR/ff_romscene.mov

wb p31.mov          20051228223854 http://www.panoramas.dk:80/fullscreen/p31.mov
wb taj_mahal.mov    20130330160043 http://www.panoramas.dk/fullscreen/taj_mahal.mov
wb apollo12.mov     20070226191807 http://www.panoramas.dk:80/moon/apollo12.mov
wb MonaLisa.mov     20060710045949 http://www.panoramas.dk:80/da-vinci-code/MonaLisa.mov
wb arounder4.mov    20070606215846 http://panoramas.dk/admov/arounder4.mov
wb chichen-itza.mov 20071009014222 http://www.panoramas.dk/7-wonders/chichen-itza.mov

echo
echo "What came back:"
cd ../..
python3 panotype.py testdata/wild/*.mov
