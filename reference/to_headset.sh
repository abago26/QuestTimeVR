#!/bin/bash
# Put a build and a useful set of test files on the headset, in one command.
#
#   reference/to_headset.sh            # stage, install, push
#   reference/to_headset.sh --stage    # stage only, no device needed
#
# The staged set is chosen to exercise what a headset session is actually for -
# see the note on each file below. It comes from Imports/, which is not in the
# repository; without it this stages nothing and says so.
#
# The one piece of real work here is the AppleDouble sidecar. WHouseVR keeps its
# header in a resource fork, and a fork does not survive `adb push` - so this
# writes the header out as a `._Name` file beside the data fork, which is exactly
# what macOS does when those files touch a FAT stick or an SMB share. Pushing both
# halves is how the picker's pairing gets tested on the device rather than only on
# the desktop.
set -euo pipefail
cd "$(dirname "$0")/.."

ADB=toolchain/android-sdk/platform-tools/adb
APK=android/app/build/outputs/apk/debug/app-debug.apk
STAGE=/tmp/qtvr-headset
DEST=/sdcard/Android/data/com.questtime.vr/files/

[ -d Imports ] || { echo "Imports/ is not here - nothing to stage" >&2; exit 1; }

rm -rf "$STAGE"; mkdir -p "$STAGE"
python3 - "$STAGE" <<'PY'
import os, shutil, struct, sys
dst = sys.argv[1]
src = 'Imports'

# Self-contained files, copied across as they are. Four of the five have no
# filename extension, which is the point: that is what the picker has to cope with.
plain = [
    'Lincoln Memorial (9 nodes)',   # 1.0, 9 nodes, one hot spot each - the main event
    'Joshua Tree',                  # 2.x, 25 nodes, object movies alongside
    'Monument Valley',              # single node - the control
    'Maranello.05.mov',             # 2.x, refused outright until recently
]
for n in plain:
    p = os.path.join(src, n)
    if os.path.isfile(p):
        shutil.copy(p, os.path.join(dst, n))
        print(f"  {n}")
    else:
        print(f"  (missing) {n}")

def appledouble(rsrc):
    """A resource fork wrapped as macOS writes it beside a data fork."""
    finder = bytes(32)
    hlen = 26 + 2 * 12
    b = bytearray()
    b += struct.pack('>II', 0x00051607, 0x00020000) + bytes(16)
    b += struct.pack('>H', 2)
    b += struct.pack('>III', 9, hlen, len(finder))
    b += struct.pack('>III', 2, hlen + len(finder), len(rsrc))
    return bytes(b) + finder + rsrc

n = 'WHouseVR.MOV'
data = os.path.join(src, n)
fork = os.path.join(src, n, '..namedfork', 'rsrc')
if os.path.isfile(data) and os.path.exists(fork):
    shutil.copy(data, os.path.join(dst, n))
    rsrc = open(fork, 'rb').read()
    open(os.path.join(dst, '._' + n), 'wb').write(appledouble(rsrc))
    print(f"  {n}  + ._{n}  ({len(rsrc)} bytes of resource fork)")
else:
    print(f"  (missing or no fork) {n}")
PY

echo "staged in $STAGE ($(du -sh "$STAGE" | cut -f1))"
[ "${1:-}" = "--stage" ] && exit 0

if [ "$("$ADB" devices | tail -n +2 | grep -c 'device$' || true)" -eq 0 ]; then
    echo
    echo "No headset attached. Plug it in, put it on once to accept the USB prompt," >&2
    echo "then run this again - the staging above is already done." >&2
    "$ADB" devices >&2
    exit 1
fi

[ -f "$APK" ] || { echo "no APK - run ./build.sh first" >&2; exit 1; }
echo "installing $(ls -la "$APK" | awk '{printf "%.1f MB", $5/1048576}')"

# An install can fail for one reason that is not a mistake: the copy on the headset
# was signed with a different ~/.android/debug.keystore. Both certificates say
# "Android Debug" and only their digests differ, so the message is more alarming
# than the situation - but the only way past it is to uninstall, which takes the
# app's data with it. Say so rather than uninstalling behind the user's back.
if ! "$ADB" install -r "$APK" 2>&1 | tee /tmp/qtvr-install.log | tail -2; then :; fi
if grep -q "signatures do not match" /tmp/qtvr-install.log; then
    cat >&2 <<'MSG'

The copy on the headset was signed with a different debug key. Installing over it is
impossible; uninstalling is the only way, and it deletes the app's data. Save both
first - they are usually already on the Mac, but check:

  adb exec-out run-as com.questtime.vr cat files/background-music > /tmp/music.mp3
  adb pull /sdcard/Android/data/com.questtime.vr/files/ /tmp/qtvr-backup/

then:

  adb uninstall com.questtime.vr
  adb shell am start -n com.questtime.vr/.MainActivity   # AFTER installing, so the
                                                         # app makes its own folder
Do NOT mkdir that folder yourself - a folder made from adb shell is owned by shell
and the app cannot read its own files.
MSG
    exit 1
fi

# Launch once so the app creates its external folder with its own ownership. Pushing
# into a folder made by `adb shell mkdir` gives every file EACCES.
"$ADB" shell am start -n com.questtime.vr/.MainActivity >/dev/null 2>&1 || true
for _ in 1 2 3 4 5 6; do
    "$ADB" shell ls -d "$DEST" >/dev/null 2>&1 && break
    "$ADB" shell sleep 1
done
"$ADB" push "$STAGE/." "$DEST"
echo
echo "on the headset:"
"$ADB" shell ls "$DEST"
echo
echo "now start the log BEFORE launching - the buffer rotates in about 30 seconds:"
echo "  $ADB logcat -c && $ADB logcat -s QuestTimeVR:V"
