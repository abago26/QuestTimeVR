"""Classify .mov files: is it QTVR, which version, which projection, which codec."""
import sys, os, struct, qtvr

def classify(path):
    try:
        data = open(path, 'rb').read()
    except Exception as e:
        return ("unreadable", str(e), "")
    if len(data) < 16:
        return ("tiny", "", "")
    try:
        tracks = qtvr.parse_tracks(data)
    except Exception as e:
        return ("not-quicktime", str(e)[:40], "")

    fmts = [t.fmt for t in tracks]
    handlers = [t.handler for t in tracks]
    vids = [t for t in tracks if t.handler == 'vide']
    codecs = ",".join(sorted({t.fmt for t in vids})) or "-"
    dims = ",".join(sorted({f"{t.width}x{t.height}" for t in vids})) or "-"

    kind = "plain movie"
    detail = ""
    if 'qtvr' in fmts:
        kind = "QTVR 2.x"
        # the qtvr track's sample is a VRWorld atom container; look for pdat/panoType
        for t in tracks:
            if t.fmt != 'qtvr':
                continue
            for off, size in t.sample_ranges() if hasattr(t, 'sample_ranges') else t.sample_offsets():
                blob = data[off:off+size]
                for tag in (b'pdat', b'vrsc', b'nloc', b'ndhd', b'impn', b'obji'):
                    if tag in blob:
                        detail += tag.decode() + " "
                i = blob.find(b'pdat')
                if i >= 0 and i + 40 < len(blob):
                    # panoType fourcc sits in the pdat payload
                    detail += "types:" + " ".join(
                        repr(blob[j:j+4].decode('latin-1')) for j in range(i+8, min(i+40, len(blob)-4), 4)
                        if blob[j:j+4].isalpha())
                break
        if 'obji' in detail:
            kind = "QTVR 2.x object movie"
    elif 'pano' in fmts:
        kind = "QTVR 1.0"
        pt = next(t for t in tracks if t.fmt == 'pano')
        info = qtvr.parse_pano(pt.sample_description if hasattr(pt,'sample_description') else pt.stsd_entry)
        detail = f"hPan {info.h_pan_start:g}->{info.h_pan_end:g} vPan {info.v_pan_top:g}/{info.v_pan_bottom:g} {info.pano_width}x{info.pano_height}"
    return (kind, codecs + " " + dims, detail)

roots = sys.argv[1:]
found = 0
for root in roots:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith('.') and d not in ('node_modules','Library')]
        for fn in filenames:
            if not fn.lower().endswith(('.mov','.qtvr','.qt')):
                continue
            p = os.path.join(dirpath, fn)
            try:
                if os.path.getsize(p) > 400*1024*1024: continue
            except OSError: continue
            kind, media, detail = classify(p)
            if kind in ("plain movie","not-quicktime","tiny","unreadable"):
                continue
            found += 1
            print(f"{kind:22} {os.path.getsize(p)//1024:>7} KB  {media:22} {detail}")
            print(f"{'':22} {p}")
print(f"\n{found} QuickTime VR file(s) found")
