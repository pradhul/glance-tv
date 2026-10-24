"""v26/v30: the "-soft.jpg" (glass / floating) and "-light.jpg" (tall posters) backgrounds of every bundled wallpaper.

v30 lowered both so the photo stays recognisable: "-soft" is GaussianBlur(3) of the 1080p crop (was 6), "-light"
GaussianBlur(1.5) (was 2.5), same size and JPEG q80. The launcher uses the same radii for the Bing photo (Bing.java).
Re-run after add_wallpapers.py.
Usage: python3 docs/design/make_light_wallpapers.py
"""
import glob
import os
import re

from PIL import Image, ImageFilter

SOFT_RADIUS = 3
LIGHT_RADIUS = 1.5
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../launcher/app/src/main/assets/wallpapers")
for path in sorted(glob.glob(os.path.join(ASSETS, "*.jpg"))):
    if not re.fullmatch(r"\d+\.jpg", os.path.basename(path)):
        continue
    im = Image.open(path).convert("RGB")
    for suffix, radius in (("-soft", SOFT_RADIUS), ("-light", LIGHT_RADIUS)):
        out = path.replace(".jpg", suffix + ".jpg")
        im.filter(ImageFilter.GaussianBlur(radius)).save(out, quality=80, optimize=True)
        print(os.path.basename(out), os.path.getsize(out))
