#!/usr/bin/env python3
"""
Flatten classic dual-fork QuickTime movies into single-fork files.

Mac QuickTime files of the QTVR era often keep only the media data (`mdat`) in the
data fork and the movie header (`moov`) in the *resource* fork, as resource
'moov' #128. Nothing outside macOS can see a resource fork - `adb push` silently
drops it - so such a file arrives on a Quest as headerless media and reads as "not a
QuickTime file".

Flattening appends the moov resource to a copy of the data fork. No offset rewriting
is needed: chunk offsets in a dual-fork movie already address the data fork from its
start, and appending leaves every existing byte where it was.

    ./flatten.py ../Imports/*            # writes *.mov beside each source
    ./flatten.py -o out/ ../Imports/*

Files that already parse are left alone.
"""
import os
import struct
import sys


def resource_fork(path):
    try:
        with open(path + '/..namedfork/rsrc', 'rb') as f:
            return f.read()
    except OSError:
        return b''


def find_resource(rf, want):
    """Return the bytes of the first resource of type `want`, or None."""
    if len(rf) < 16:
        return None
    data_off, map_off = struct.unpack('>II', rf[:8])
    if map_off + 30 > len(rf):
        return None
    type_list_off = struct.unpack('>H', rf[map_off + 24:map_off + 26])[0]
    tl = map_off + type_list_off
    if tl + 2 > len(rf):
        return None
    n_types = struct.unpack('>H', rf[tl:tl + 2])[0] + 1
    for i in range(n_types):
        e = tl + 2 + i * 8
        if e + 8 > len(rf):
            break
        if rf[e:e + 4].decode('latin-1') != want:
            continue
        n_res = struct.unpack('>H', rf[e + 4:e + 6])[0] + 1
        ref_off = struct.unpack('>H', rf[e + 6:e + 8])[0]
        for r in range(n_res):
            ref = tl + ref_off + r * 12
            if ref + 12 > len(rf):
                break
            off = struct.unpack('>I', b'\x00' + rf[ref + 5:ref + 8])[0]
            start = data_off + off
            if start + 4 > len(rf):
                continue
            length = struct.unpack('>I', rf[start:start + 4])[0]
            if start + 4 + length > len(rf):
                continue
            return rf[start + 4:start + 4 + length]
    return None


def has_moov(data):
    """Walk top-level atoms looking for a moov, without a full parse."""
    pos = 0
    while pos + 8 <= len(data):
        size = struct.unpack('>I', data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        if size == 1:
            if pos + 16 > len(data):
                return False
            size = struct.unpack('>Q', data[pos + 8:pos + 16])[0]
        elif size == 0:
            size = len(data) - pos
        if typ == b'moov':
            return True
        if size < 8:
            return False
        pos += size
    return False


def out_name(path, outdir):
    base = os.path.basename(path)
    stem = base[:-4] if base.lower().endswith('.mov') else base
    # Keep the name recognisable but make it something a file picker will show.
    safe = stem.replace('/', '-').strip()
    name = safe + '.mov'
    return os.path.join(outdir or os.path.dirname(path) or '.', name)


def main(argv):
    outdir = None
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == '-o':
            outdir = argv[i + 1]
            i += 2
        else:
            args.append(argv[i])
            i += 1
    if outdir:
        os.makedirs(outdir, exist_ok=True)

    flattened = already = skipped = 0
    for path in args:
        if not os.path.isfile(path):
            continue
        with open(path, 'rb') as f:
            data = f.read()
        name = os.path.basename(path)

        if has_moov(data):
            already += 1
            dest = out_name(path, outdir)
            if outdir and os.path.abspath(dest) != os.path.abspath(path):
                with open(dest, 'wb') as f:
                    f.write(data)
            print(f"  ok        {name}  (already single-fork)")
            continue

        moov = find_resource(resource_fork(path), 'moov')
        if moov is None:
            skipped += 1
            print(f"  SKIP      {name}  (no moov in data or resource fork)")
            continue

        dest = out_name(path, outdir)
        with open(dest, 'wb') as f:
            f.write(data)
            f.write(moov)
        flattened += 1
        print(f"  flattened {name}  (+{len(moov)} B moov) -> {os.path.basename(dest)}")

    print(f"\n{flattened} flattened, {already} already fine, {skipped} skipped")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
