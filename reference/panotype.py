#!/usr/bin/env python3
"""
Say what flavour of QuickTime VR a file is, without decoding a pixel.

Written to answer one question: are there QuickTime VR panoramas in the wild that
are stored *upright* rather than rotated 90 degrees counter-clockwise? Every file
before QuickTime 5 was rotated, and the app rotates unconditionally, so an upright
one would render lying on its side.

    ./panotype.py testdata/*.mov

Reports, per file: version, node count, geometry, stored orientation, and codecs.
"""
import struct
import sys

# QTVRPanoSampleAtom, from Inside QuickTime VR. The offsets the Kotlin parser uses.
FLAGS = 72
PANO_TYPE = 76


def atoms(d, start, end):
    pos = start
    while pos + 8 <= end:
        size = struct.unpack('>I', d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8].decode('latin-1')
        body = pos + 8
        if size == 1:
            size = struct.unpack('>Q', d[pos + 8:pos + 16])[0]
            body = pos + 16
        elif size == 0:
            size = end - pos
        if size < 8 or pos + size > end:
            break
        yield typ, body, pos + size
        pos += size


def find(d, s, e, path):
    for t, b, en in atoms(d, s, e):
        if t == path[0]:
            return (b, en) if len(path) == 1 else find(d, b, en, path[1:])
    return None


def tracks(d):
    moov = find(d, 0, len(d), ['moov'])
    if not moov:
        return []
    out = []
    for t, b, e in atoms(d, *moov):
        if t != 'trak':
            continue
        h = find(d, b, e, ['mdia', 'hdlr'])
        handler = d[h[0] + 8:h[0] + 12].decode('latin-1') if h else '?'
        stsd = find(d, b, e, ['mdia', 'minf', 'stbl', 'stsd'])
        fmt = d[stsd[0] + 12:stsd[0] + 16].decode('latin-1') if stsd else '?'
        stsz = find(d, b, e, ['mdia', 'minf', 'stbl', 'stsz'])
        n = struct.unpack('>I', d[stsz[0] + 8:stsz[0] + 12])[0] if stsz else 0
        out.append((handler, fmt, n, (b, e)))
    return out


def describe(path):
    d = open(path, 'rb').read()
    tr = tracks(d)
    if not tr:
        return f"{path}: not a QuickTime file"

    v1 = [t for t in tr if t[1] == 'pano']
    v2 = [t for t in tr if t[0] == 'pano']
    codecs = sorted({t[1].strip() for t in tr if t[0] == 'vide'})
    obj = [t for t in tr if t[1] in ('obji', 'obje') or t[0] == 'obje']

    if obj:
        return f"{path}: OBJECT MOVIE (not a panorama)"

    if v1 and not v2:
        # 1.0 keeps it in the sample description, and is always stored rotated.
        nodes = v1[0][2]
        return (f"{path}: QTVR 1.0  nodes={nodes}  cylindrical  "
                f"stored=rotated (pre-QT5, always)  codecs={codecs}")

    if not v2:
        return f"{path}: no panorama track (ordinary movie?)  codecs={codecs}"

    i = d.find(b'pdat')
    if i < 0:
        return f"{path}: pano track but no 'pdat' descriptor"
    p = i + 16
    ver = struct.unpack('>H', d[p:p + 2])[0]
    flags = struct.unpack('>I', d[p + FLAGS:p + FLAGS + 4])[0]
    ptype = d[p + PANO_TYPE:p + PANO_TYPE + 4]
    name = ptype.decode('latin-1').strip('\x00 ') or '(blank)'

    if ptype == b'cube':
        geometry, rotated = 'cubic', False
    elif ptype == b'hcyl':
        geometry, rotated = 'cylindrical', False
    elif ptype == b'vcyl':
        geometry, rotated = 'cylindrical', True
    else:
        # Blank: the low bit of flags carries it. Set means upright.
        geometry, rotated = 'cylindrical', (flags & 1) == 0

    nodes = v2[0][2]
    stored = 'rotated' if rotated else 'UPRIGHT'
    return (f"{path}: QTVR {ver}.x  nodes={nodes}  {geometry}  "
            f"panoType={name} flags=0x{flags:08x}  stored={stored}  codecs={codecs}")


if __name__ == '__main__':
    for a in sys.argv[1:]:
        try:
            print(describe(a))
        except Exception as e:
            print(f"{a}: could not read - {e}")
