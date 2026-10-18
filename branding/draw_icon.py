# Draws the FSL letter icon: layered chamfered-octagon frames in violet-blue tones with bold white "FSL".
# Run from this folder: python -I draw_icon.py
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 2048  # supersampled canvas, downscaled later for smooth edges
C = S / 2
HERE = Path(__file__).resolve().parent
IMG_DIR = HERE.parent / "HMCL" / "src" / "main" / "resources" / "assets" / "img"


def octagon(r, cut=0.30):
    # Square with chamfered corners, centered; cut = corner size relative to r
    k = r * cut
    return [(C - r + k, C - r), (C + r - k, C - r), (C + r, C - r + k), (C + r, C + r - k),
            (C + r - k, C + r), (C - r + k, C + r), (C - r, C + r - k), (C - r, C - r + k)]


def gradient(c1, c2, angle_deg=135):
    # Diagonal linear gradient, computed small then upscaled
    small = 256
    a = math.radians(angle_deg)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0, dx) + min(0, dy)
    span = abs(dx) + abs(dy)
    sm = Image.new("RGBA", (small, small))
    sp = sm.load()
    for y in range(small):
        for x in range(small):
            t = ((x / small) * dx + (y / small) * dy - lo) / span
            sp[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(4))
    return sm.resize((S, S), Image.BICUBIC)


def layer(poly, fill_img):
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).polygon(poly, fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(fill_img, (0, 0), m)
    return out


icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))

# Soft drop shadow
sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(sh).polygon([(x, y + 40) for x, y in octagon(900)], fill=(10, 8, 50, 150))
icon = Image.alpha_composite(icon, sh.filter(ImageFilter.GaussianBlur(50)))

# Outer frame (deep violet), gap ring (dark navy), main plate (violet-to-blue gradient)
icon = Image.alpha_composite(icon, layer(octagon(900), gradient((150, 120, 255, 255), (52, 40, 170, 255))))
icon = Image.alpha_composite(icon, layer(octagon(820), gradient((26, 20, 70, 255), (12, 10, 38, 255))))
plate = octagon(770)
icon = Image.alpha_composite(icon, layer(plate, gradient((138, 108, 255, 255), (64, 96, 235, 255))))

# Facet highlight: lighter upper-left half of the plate, like a struck flint face
hl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(hl).polygon([(C - 800, C - 800), (C + 800, C - 800), (C - 800, C + 800)], fill=(255, 255, 255, 34))
plate_mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(plate_mask).polygon(plate, fill=255)
icon = Image.alpha_composite(icon, Image.composite(hl, Image.new("RGBA", (S, S)), plate_mask))

# Accent bars on two chamfers of the outer frame
d = ImageDraw.Draw(icon)
of = octagon(900)
d.line([of[1], of[2]], fill=(196, 206, 255, 255), width=46)
d.line([of[5], of[6]], fill=(196, 206, 255, 255), width=46)

# Letters with a soft shadow
font = ImageFont.truetype(r"C:\Windows\Fonts\seguibl.ttf", 560)
text = "FSL"
bbox = d.textbbox((0, 0), text, font=font)
tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
tx, ty = C - tw / 2 - bbox[0], C - th / 2 - bbox[1]
ts = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(ts).text((tx, ty + 26), text, font=font, fill=(20, 12, 80, 170))
icon = Image.alpha_composite(icon, ts.filter(ImageFilter.GaussianBlur(14)))
d = ImageDraw.Draw(icon)
d.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))

# Small four-point spark above the "L"
sx, sy, r = C + tw / 2 - 40, C - th / 2 - 150, 120
pts = []
for i in range(8):
    rr = r if i % 2 == 0 else r * 0.28
    a = -math.pi / 2 + i * math.pi / 4
    pts.append((sx + rr * math.cos(a), sy + rr * math.sin(a)))
d.polygon(pts, fill=(236, 242, 255, 255))

icon.save(HERE / "fsl-icon-2048.png")
icon.resize((512, 512), Image.LANCZOS).save(HERE / "preview-512.png")
icon.resize((256, 256), Image.LANCZOS).save(
    HERE / "fsl.ico",
    sizes=[(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (96, 96), (128, 128), (256, 256)])
for name, size in [("icon-title.png", 24), ("icon-title@2x.png", 48), ("icon.png", 32),
                   ("icon@2x.png", 64), ("icon@4x.png", 128), ("icon@8x.png", 256), ("icon-mac.png", 512)]:
    icon.resize((size, size), Image.LANCZOS).save(IMG_DIR / name)
print("done")
