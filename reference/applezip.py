#!/usr/bin/env python3
"""
Recover resource forks from a Mac-made .zip, and flatten what they unlock.

This is the reference implementation for zip import in the upload server, and the
evidence behind the claim that it is worth building. Run it before porting anything
to Kotlin.

The problem it solves: a browser upload cannot carry a resource fork, so the 13
classic Mac files in `Imports/` that keep their `moov` there arrive headerless and are
refused. That is nearly half the archive, failing for a reason that has nothing to do
with the files.

The way through: when a Mac user right-click Compresses a selection, the archive
stores each resource fork as an AppleDouble sidecar at `__MACOSX/._Name`. That sidecar
is byte-identical to `path/..namedfork/rsrc`, so `flatten.py`'s existing resource
parser reads it unchanged and the flattened output matches `flatten.py` exactly.

    ./applezip.py ../Imports.zip                 # report only
    ./applezip.py -o out/ ../Imports.zip         # write the recovered .mov files

Make a test archive the same way Finder does:

    ditto -c -k --sequesterRsrc --keepParent ../Imports /tmp/imports.zip

AppleDouble layout: magic 0x00051607, then a count at offset 24 and that many
12-byte (id, offset, length) entries from offset 26. Entry id 2 is the resource fork.
"""
import argparse
import importlib.util
import os
import posixpath
import struct
import sys
import zipfile

_spec = importlib.util.spec_from_file_location(
    'flatten', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'flatten.py'))
flatten = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(flatten)

APPLEDOUBLE_MAGIC = 0x00051607
ENTRY_RESOURCE_FORK = 2


def resource_fork_from_appledouble(blob):
    """The resource fork inside an AppleDouble sidecar, or None."""
    if len(blob) < 26 or struct.unpack('>I', blob[:4])[0] != APPLEDOUBLE_MAGIC:
        return None
    count = struct.unpack('>H', blob[24:26])[0]
    for i in range(count):
        base = 26 + i * 12
        if base + 12 > len(blob):
            return None
        entry_id, off, length = struct.unpack('>III', blob[base:base + 12])
        if entry_id == ENTRY_RESOURCE_FORK and length and off + length <= len(blob):
            return blob[off:off + length]
    return None


def entry_name(info):
    """The member's real name, working around Finder's missing UTF-8 flag.

    `ditto` writes filenames as UTF-8 but does not set the general-purpose flag bit
    that says so, and zipfile then falls back to cp437 as the spec requires. That
    turns 'Bryce(tm)' into 'BryceGamma-a-o' and the name reaches the picker mangled.
    Re-encoding to cp437 recovers the original bytes; if they are not valid UTF-8 the
    archive really was cp437, so keep what zipfile gave us.
    """
    if info.flag_bits & 0x800:
        return info.filename
    try:
        return info.filename.encode('cp437').decode('utf-8')
    except (UnicodeEncodeError, UnicodeDecodeError):
        return info.filename


def read_archive(path):
    """Split a Mac zip into real members and their AppleDouble sidecars.

    Keyed on basename rather than full path: the sidecar lives under a parallel
    `__MACOSX/` tree, so the two only line up once the prefix is dropped.
    """
    members, sidecars = {}, {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            full = entry_name(info)
            name = posixpath.basename(full)
            if full.startswith('__MACOSX/'):
                if name.startswith('._'):
                    sidecars[name[2:]] = z.read(info)
            elif not name.startswith('.'):
                members[name] = z.read(info)
    return members, sidecars


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('archive')
    ap.add_argument('-o', '--outdir', help='write recovered .mov files here')
    args = ap.parse_args(argv[1:])

    members, sidecars = read_archive(args.archive)
    if args.outdir:
        os.makedirs(args.outdir, exist_ok=True)

    intact, rescued, lost = [], [], []
    for name, data in sorted(members.items()):
        if flatten.has_moov(data):
            intact.append(name)
            continue
        sidecar = sidecars.get(name)
        rf = resource_fork_from_appledouble(sidecar) if sidecar else None
        moov = flatten.find_resource(rf, 'moov') if rf else None
        if not moov:
            lost.append(name)
            print(f"  lost      {name}  (no moov in the data fork or any sidecar)")
            continue
        rescued.append(name)
        print(f"  rescued   {name}  (+{len(moov)} B moov from its sidecar)")
        if args.outdir:
            dest = os.path.join(args.outdir, name if name.lower().endswith('.mov')
                                else name + '.mov')
            with open(dest, 'wb') as f:
                f.write(data)
                f.write(moov)

    print(f"\n{len(members)} in archive: {len(intact)} already fine, "
          f"{len(rescued)} rescued, {len(lost)} unrecoverable")
    # Anything unrecoverable is a genuine dead end for browser upload, so say so
    # loudly rather than letting it sit in a count.
    return 1 if lost else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
