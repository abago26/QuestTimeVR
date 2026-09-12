"""Reproduce GL cubemap sampling so face order/flip can be settled by eye, on a
desktop, instead of by trial and error in the headset."""
import math, struct, sys

N = 696
faces = [open(f'truth/face_{i}.rgb','rb').read() for i in range(1, 7)]

def render(perm, flip, W=1400, H=700, out='cube_check.rgb'):
    # perm maps GL face index (+X,-X,+Y,-Y,+Z,-Z) -> QuickTime face index
    buf = bytearray(W*H*3)
    for py in range(H):
        lat = (0.5 - (py + 0.5)/H) * math.pi           # +pi/2 .. -pi/2
        for px in range(W):
            lon = ((px + 0.5)/W - 0.5) * 2*math.pi     # -pi .. +pi
            x = math.cos(lat)*math.sin(lon)
            y = math.sin(lat)
            z = -math.cos(lat)*math.cos(lon)           # OpenXR: -Z is forward
            ax, ay, az = abs(x), abs(y), abs(z)
            # GL ES cubemap face selection and s/t derivation
            if ax >= ay and ax >= az:
                if x > 0: f, sc, tc, ma = 0, -z, -y, ax
                else:     f, sc, tc, ma = 1,  z, -y, ax
            elif ay >= az:
                if y > 0: f, sc, tc, ma = 2,  x,  z, ay
                else:     f, sc, tc, ma = 3,  x, -z, ay
            else:
                if z > 0: f, sc, tc, ma = 4,  x, -y, az
                else:     f, sc, tc, ma = 5, -x, -y, az
            s = (sc/ma + 1)/2
            t = (tc/ma + 1)/2                          # t=0 is the TOP row
            if flip & 1: s = 1 - s
            if flip & 2: t = 1 - t
            u = min(N-1, max(0, int(s*N)))
            v = min(N-1, max(0, int(t*N)))
            src = faces[perm[f]]
            o = (v*N + u)*3
            d = (py*W + px)*3
            buf[d:d+3] = src[o:o+3]
    open(out,'wb').write(bytes(buf))
    return W, H

if __name__ == '__main__':
    perm = [int(c) for c in sys.argv[1]]
    flip = int(sys.argv[2])
    W, H = render(perm, flip, out=sys.argv[3])
    print(f"wrote {sys.argv[3]} {W}x{H} perm={sys.argv[1]} flip={flip}")
