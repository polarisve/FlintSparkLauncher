# Draws FSL built-in wallpapers (dark violet-blue with soft glows and sparks), replacing HMCL's.
# Same file names as upstream so existing settings keep working. Run: python -I draw_wallpapers.py
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "HMCL" / "src" / "main" / "resources" / "assets" / "img" / "wallpapers"
W, H = 1920, 1080

def wallpaper(seed, top, bottom, glows):
    rnd = random.Random(seed)
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        d.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    g = ImageDraw.Draw(glow)
    for (cx, cy, r, col) in glows:
        g.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
    img = Image.blend(img, Image.composite(glow, img, glow.convert("L").point(lambda v: 255 if v else 0)).filter(ImageFilter.GaussianBlur(220)), 0.55)
    sp = ImageDraw.Draw(img)
    for _ in range(140):
        x, y, r = rnd.randrange(W), rnd.randrange(H), rnd.choice([1, 1, 1.5, 2, 2.5])
        c = rnd.choice([(200, 205, 255), (170, 160, 255), (150, 200, 255)])
        sp.ellipse([x - r, y - r, x + r, y + r], fill=c)
    return img

specs = {
    "2021-08-26": (1, (12, 11, 24), (26, 22, 64), [(1500, 1080, 520, (110, 90, 255)), (200, -50, 380, (60, 140, 255))]),
    "2016-02-25": (2, (10, 12, 30), (20, 30, 72), [(400, 1000, 480, (70, 110, 255)), (1700, 100, 360, (140, 100, 255))]),
    "2015-06-22": (3, (16, 10, 30), (40, 20, 70), [(960, 1150, 600, (150, 90, 255)), (1800, 0, 300, (90, 160, 255))]),
}
for name, (seed, top, bottom, glows) in specs.items():
    wallpaper(seed, top, bottom, glows).save(OUT / f"{name}.jpg", quality=90)
print("done")
