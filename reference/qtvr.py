"""
Reference implementation of the QuickTime VR extraction pipeline.

This exists to be checked against ffmpeg pixel-for-pixel before the same logic is
ported to Kotlin for the Android app. Pure stdlib, no dependencies.

Covers QTVR 1.0 cylindrical panoramas: QuickTime atom parsing, the 'pano' sample
description, Cinepak (cvid) decoding, tile assembly and the 90 degree rotation.
"""

import struct
from dataclasses import dataclass, field

u16 = lambda b, o: struct.unpack_from(">H", b, o)[0]
u32 = lambda b, o: struct.unpack_from(">I", b, o)[0]
s32 = lambda b, o: struct.unpack_from(">i", b, o)[0]
fixed = lambda b, o: s32(b, o) / 65536.0


# --------------------------------------------------------------------------
# QuickTime container
# --------------------------------------------------------------------------

@dataclass
class Track:
    handler: str = ""
    fmt: str = ""
    width: int = 0
    height: int = 0
    depth: int = 0
    stsd_entry: bytes = b""
    sample_sizes: list = field(default_factory=list)
    chunk_offsets: list = field(default_factory=list)
    stsc: list = field(default_factory=list)          # (first_chunk, per_chunk, desc_idx)

    def sample_offsets(self):
        """Walk the sample-to-chunk table into a flat list of (offset, size)."""
        out, sample = [], 0
        for i, (first, per_chunk, _) in enumerate(self.stsc):
            last = self.stsc[i + 1][0] - 1 if i + 1 < len(self.stsc) else len(self.chunk_offsets)
            for chunk in range(first, last + 1):
                if chunk - 1 >= len(self.chunk_offsets):
                    break
                pos = self.chunk_offsets[chunk - 1]
                for _ in range(per_chunk):
                    if sample >= len(self.sample_sizes):
                        break
                    size = self.sample_sizes[sample]
                    out.append((pos, size))
                    pos += size
                    sample += 1
        return out


def _atoms(data, start, end):
    """Yield (type, payload_start, payload_end) for atoms in [start, end)."""
    pos = start
    while pos + 8 <= end:
        size = u32(data, pos)
        typ = data[pos + 4:pos + 8].decode("latin-1")
        if size == 1:                      # 64-bit extended size
            size = struct.unpack_from(">Q", data, pos + 8)[0]
            body = pos + 16
        elif size == 0:                    # extends to end of file
            size = end - pos
            body = pos + 8
        else:
            body = pos + 8
        if size < 8 or pos + size > end:
            break
        yield typ, body, pos + size
        pos += size


def _find(data, start, end, path):
    """Descend a slash-separated atom path, returning (start, end) or None."""
    want, rest = path[0], path[1:]
    for typ, b, e in _atoms(data, start, end):
        if typ == want:
            return (b, e) if not rest else _find(data, b, e, rest)
    return None


def parse_tracks(data):
    moov = _find(data, 0, len(data), ["moov"])
    if not moov:
        raise ValueError("no moov atom - not a QuickTime file")

    tracks = []
    for typ, tb, te in _atoms(data, *moov):
        if typ != "trak":
            continue
        t = Track()

        hdlr = _find(data, tb, te, ["mdia", "hdlr"])
        if hdlr:
            # 8 bytes version/flags + component type, then subtype
            t.handler = data[hdlr[0] + 8:hdlr[0] + 12].decode("latin-1")

        stbl = _find(data, tb, te, ["mdia", "minf", "stbl"])
        if not stbl:
            continue

        stsd = _find(data, *stbl, ["stsd"])
        if stsd:
            base = stsd[0] + 8                       # skip version/flags + entry count
            if base + 16 <= stsd[1]:
                size = u32(data, base)
                t.stsd_entry = data[base:base + size]
                t.fmt = data[base + 4:base + 8].decode("latin-1")
                if t.handler == "vide" and size >= 86:
                    t.width = u16(data, base + 32)
                    t.height = u16(data, base + 34)
                    t.depth = u16(data, base + 82)

        stsz = _find(data, *stbl, ["stsz"])
        if stsz:
            uniform, count = u32(data, stsz[0] + 4), u32(data, stsz[0] + 8)
            if uniform:
                t.sample_sizes = [uniform] * count
            else:
                t.sample_sizes = [u32(data, stsz[0] + 12 + 4 * i) for i in range(count)]

        for tag, width in (("stco", 4), ("co64", 8)):
            box = _find(data, *stbl, [tag])
            if box:
                n = u32(data, box[0] + 4)
                rd = u32 if width == 4 else (lambda b, o: struct.unpack_from(">Q", b, o)[0])
                t.chunk_offsets = [rd(data, box[0] + 8 + width * i) for i in range(n)]

        stsc = _find(data, *stbl, ["stsc"])
        if stsc:
            n = u32(data, stsc[0] + 4)
            t.stsc = [tuple(u32(data, stsc[0] + 8 + 12 * i + 4 * k) for k in range(3))
                      for i in range(n)]

        tracks.append(t)
    return tracks


# --------------------------------------------------------------------------
# QTVR 1.0 'pano' sample description
# --------------------------------------------------------------------------

@dataclass
class PanoInfo:
    h_pan_start: float = 0.0
    h_pan_end: float = 360.0
    v_pan_top: float = 0.0
    v_pan_bottom: float = 0.0
    scene_size_x: int = 0          # stored (rotated) width
    scene_size_y: int = 0          # stored (rotated) height
    num_frames: int = 0
    frames_x: int = 0
    frames_y: int = 0
    hotspot_size_x: int = 0
    hotspot_size_y: int = 0

    @property
    def pano_width(self):
        return self.scene_size_y     # after the 90 degree rotation

    @property
    def pano_height(self):
        return self.scene_size_x


def parse_pano(entry):
    """Decode a 'pano' sample description entry (QTVR 1.0)."""
    p = PanoInfo()
    p.h_pan_start = fixed(entry, 92)
    p.h_pan_end = fixed(entry, 96)
    p.v_pan_top = fixed(entry, 100)
    p.v_pan_bottom = fixed(entry, 104)
    p.scene_size_x = u32(entry, 116)
    p.scene_size_y = u32(entry, 120)
    p.num_frames = u32(entry, 124)
    p.frames_x = u16(entry, 130)
    p.frames_y = u16(entry, 132)
    p.hotspot_size_x = u32(entry, 136)
    p.hotspot_size_y = u32(entry, 140)
    return p


# --------------------------------------------------------------------------
# Cinepak (cvid)
# --------------------------------------------------------------------------

def _clamp(v):
    return 0 if v < 0 else (255 if v > 255 else v)


class Cinepak:
    """
    Cinepak decoder. Codebooks persist across strips and frames, which inter-coded
    frames rely on, so one instance decodes a whole track in order.

    The YUV to RGB coefficients are the ones ffmpeg uses; verify.py checks the
    output against ffmpeg's to confirm.
    """

    def __init__(self, width, height):
        self.width, self.height = width, height
        self.rgb = bytearray(width * height * 3)
        # 2 strips max in practice, but codebooks are per-strip-index
        self.v1 = [[(0, 0, 0, 0, 0, 0)] * 256 for _ in range(32)]
        self.v4 = [[(0, 0, 0, 0, 0, 0)] * 256 for _ in range(32)]

    # -- codebook ---------------------------------------------------------
    def _codebook(self, book, chunk_id, data):
        gray = bool(chunk_id & 0x0400)
        selective = bool(chunk_id & 0x0100)
        n = 4 if gray else 6
        pos = 0
        if selective:
            idx = 0
            while pos < len(data) and idx < 256:
                mask = u32(data, pos)
                pos += 4
                for bit in range(32):
                    if idx >= 256:
                        break
                    if mask & (0x80000000 >> bit):
                        if pos + n > len(data):
                            return
                        book[idx] = self._entry(data, pos, gray)
                        pos += n
                    idx += 1
        else:
            idx = 0
            while pos + n <= len(data) and idx < 256:
                book[idx] = self._entry(data, pos, gray)
                pos += n
                idx += 1

    @staticmethod
    def _entry(data, pos, gray):
        y0, y1, y2, y3 = data[pos], data[pos + 1], data[pos + 2], data[pos + 3]
        if gray:
            return (y0, y1, y2, y3, 0, 0)
        u = data[pos + 4] - 256 if data[pos + 4] > 127 else data[pos + 4]
        v = data[pos + 5] - 256 if data[pos + 5] > 127 else data[pos + 5]
        return (y0, y1, y2, y3, u, v)

    # -- pixel writing ----------------------------------------------------
    def _put(self, x, y, luma, u, v):
        if x < 0 or y < 0 or x >= self.width or y >= self.height:
            return
        o = (y * self.width + x) * 3
        self.rgb[o] = _clamp(luma + (v * 2))
        self.rgb[o + 1] = _clamp(luma - int(u / 2) - v)
        self.rgb[o + 2] = _clamp(luma + (u * 2))

    def _block_v1(self, x, y, cb):
        y0, y1, y2, y3, u, v = cb
        for dy in range(2):
            for dx in range(2):
                self._put(x + dx, y + dy, y0, u, v)
                self._put(x + 2 + dx, y + dy, y1, u, v)
                self._put(x + dx, y + 2 + dy, y2, u, v)
                self._put(x + 2 + dx, y + 2 + dy, y3, u, v)

    def _block_v4(self, x, y, c0, c1, c2, c3):
        for cb, ox, oy in ((c0, 0, 0), (c1, 2, 0), (c2, 0, 2), (c3, 2, 2)):
            y0, y1, y2, y3, u, v = cb
            self._put(x + ox, y + oy, y0, u, v)
            self._put(x + ox + 1, y + oy, y1, u, v)
            self._put(x + ox, y + oy + 1, y2, u, v)
            self._put(x + ox + 1, y + oy + 1, y3, u, v)

    # -- vectors ----------------------------------------------------------
    def _vectors(self, chunk_id, data, v1, v4, y_top, y_bot):
        pos = 0
        flag = 0
        mask = 0

        def next_bit():
            nonlocal flag, mask, pos
            if mask == 0:
                if pos + 4 > len(data):
                    return None
                flag = u32(data, pos)
                pos += 4
                mask = 0x80000000
            bit = bool(flag & mask)
            mask >>= 1
            return bit

        for y in range(y_top, y_bot, 4):
            for x in range(0, self.width, 4):
                if chunk_id == 0x3200:                 # intra, V1 only
                    use_v4 = False
                elif chunk_id == 0x3000:               # intra, mixed
                    b = next_bit()
                    if b is None:
                        return
                    use_v4 = b
                else:                                  # 0x3100 inter
                    b = next_bit()
                    if b is None:
                        return
                    if not b:
                        continue                       # block unchanged
                    b2 = next_bit()
                    if b2 is None:
                        return
                    use_v4 = b2

                if use_v4:
                    if pos + 4 > len(data):
                        return
                    self._block_v4(x, y, v4[data[pos]], v4[data[pos + 1]],
                                   v4[data[pos + 2]], v4[data[pos + 3]])
                    pos += 4
                else:
                    if pos + 1 > len(data):
                        return
                    self._block_v1(x, y, v1[data[pos]])
                    pos += 1

    # -- frame ------------------------------------------------------------
    def decode(self, frame):
        if len(frame) < 10:
            return self.rgb
        length = int.from_bytes(frame[1:4], "big")
        if 0 < length <= len(frame):
            frame = frame[:length]
        num_strips = u16(frame, 8)

        pos = 10
        y_top = 0
        for s in range(num_strips):
            if pos + 12 > len(frame):
                break
            strip_size = u16(frame, pos + 2)
            if strip_size < 12:
                break
            # y0/y1 in the strip header are frequently relative; track the running
            # offset and use the header only for the strip's height.
            y0f, y1f = u16(frame, pos + 4), u16(frame, pos + 8)
            rows = (y1f - y0f) if y1f > y0f else self.height
            y_bot = min(y_top + rows, self.height)

            si = min(s, 31)
            # A strip after the first commonly sends only selective codebook
            # updates (0x2100/0x2300), which apply on top of the previous
            # strip's books. Inherit them first, exactly as ffmpeg does.
            if s > 0 and not (frame[0] & 0x01):
                self.v1[si] = list(self.v1[si - 1])
                self.v4[si] = list(self.v4[si - 1])
            v1, v4 = self.v1[si], self.v4[si]

            body_end = min(pos + strip_size, len(frame))
            cpos = pos + 12
            while cpos + 4 <= body_end:
                cid = u16(frame, cpos)
                csize = u16(frame, cpos + 2)
                if csize < 4:
                    break
                cdata = frame[cpos + 4:min(cpos + csize, body_end)]
                if (cid & 0xF000) == 0x2000:
                    # bit 0x0200 selects V1 over V4; 0x0100 = selective update,
                    # 0x0400 = greyscale (4-byte entries)
                    self._codebook(v1 if (cid & 0x0200) else v4, cid, cdata)
                elif cid in (0x3000, 0x3100, 0x3200):
                    self._vectors(cid, cdata, v1, v4, y_top, y_bot)
                cpos += csize

            y_top = y_bot
            pos += strip_size
        return self.rgb


# --------------------------------------------------------------------------
# Assembly
# --------------------------------------------------------------------------

def stack_tiles(tiles, tile_w, tile_h):
    """Stack decoded tiles vertically -> (rgb, width, height)."""
    h = tile_h * len(tiles)
    out = bytearray(tile_w * h * 3)
    row = tile_w * 3
    for i, t in enumerate(tiles):
        out[i * tile_h * row:(i + 1) * tile_h * row] = t
    return bytes(out), tile_w, h


def rotate_cw(rgb, w, h):
    """Rotate 90 degrees clockwise -> (rgb, h, w). QTVR stores panoramas sideways."""
    out = bytearray(w * h * 3)
    nw = h
    for y in range(h):
        for x in range(w):
            si = (y * w + x) * 3
            di = (x * nw + (h - 1 - y)) * 3
            out[di:di + 3] = rgb[si:si + 3]
    return bytes(out), h, w


def extract(path):
    """Full pipeline -> (rgb, width, height, PanoInfo)."""
    data = open(path, "rb").read()
    tracks = parse_tracks(data)

    video = next((t for t in tracks if t.handler == "vide"), None)
    pano_trk = next((t for t in tracks if t.fmt == "pano"), None)
    if video is None:
        raise ValueError("no video track")
    if video.fmt != "cvid":
        raise ValueError(f"unsupported codec {video.fmt!r} (only cvid here)")

    info = parse_pano(pano_trk.stsd_entry) if pano_trk else PanoInfo()

    dec = Cinepak(video.width, video.height)
    tiles = []
    for off, size in video.sample_offsets():
        tiles.append(bytes(dec.decode(data[off:off + size])))

    rgb, w, h = stack_tiles(tiles, video.width, video.height)
    rgb, w, h = rotate_cw(rgb, w, h)
    return rgb, w, h, info
