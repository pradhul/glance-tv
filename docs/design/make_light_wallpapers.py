"""v26: a lightly blurred "-light.jpg" for every bundled wallpaper (tall posters style shows it).

The "-soft.jpg" background is GaussianBlur(6) of the 1080p crop; "-light" is GaussianBlur(2.5) of the same crop
(about 40% of the radius), same size and JPEG q80. Re-run after add_wallpapers.py.
Usage: python3 docs/design/make_light_wallpapers.py
"""
import glob
import os
import re

from PIL import Image, ImageFilter

LIGHT_RADIUS = 2.5
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../launcher/app/src/main/assets/wallpapers")
for path in sorted(glob.glob(os.path.join(ASSETS, "*.jpg"))):
    if not re.fullmatch(r"\d+\.jpg", os.path.basename(path)):
        continue
    out = path.replace(".jpg", "-light.jpg")
    Image.open(path).convert("RGB").filter(ImageFilter.GaussianBlur(LIGHT_RADIUS)).save(out, quality=80, optimize=True)
    print(os.path.basename(out), os.path.getsize(out))
