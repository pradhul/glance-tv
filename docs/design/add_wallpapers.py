"""Append downloaded photos to the bundled wallpapers without renumbering the existing ones.

Same crop, blur and region sampling as prepare_wallpapers.py (imported from it), JPEG q82, plus a "set"
("day" or "night") and the photo's source URL in themes.json. Existing entries without a set get one from
their luminance (mean of the three regions >= 0.5 is day).

Usage: python3 docs/design/add_wallpapers.py <manifest.json>
manifest: [{"path": "local.jpg", "set": "night", "source": "https://unsplash.com/photos/..."}]
"""
import json
import os
import sys
import importlib.util

from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("pw", os.path.join(HERE, "prepare_wallpapers.py"))
# prepare_wallpapers.py runs on import; borrow its constants and helpers by exec'ing only the top part.
src = open(os.path.join(HERE, "prepare_wallpapers.py")).read().split("\nsrc = sorted(")[0]
pw = {"__file__": os.path.join(HERE, "prepare_wallpapers.py")}
exec(src, pw)
W, H, ASSETS, REGIONS, DARK_BELOW = pw["W"], pw["H"], pw["ASSETS"], pw["REGIONS"], pw["DARK_BELOW"]
cover, luminance = pw["cover"], pw["luminance"]

themes_path = os.path.join(ASSETS, "themes.json")
themes = json.load(open(themes_path))
for t in themes:
    if "set" not in t:
        lum = t["luminance"]
        t["set"] = "day" if (lum["top"] + lum["pills"] + lum["dock"]) / 3 >= 0.5 else "night"

n = max(int(t["file"][:2]) for t in themes)
for item in json.load(open(sys.argv[1])):
    n += 1
    name = f"{n:02d}"
    im = cover(Image.open(item["path"]).convert("RGB"))
    blur = im.filter(ImageFilter.GaussianBlur(28))
    im.save(os.path.join(ASSETS, f"{name}.jpg"), quality=82, optimize=True)
    blur.resize((W // 2, H // 2), Image.LANCZOS).save(os.path.join(ASSETS, f"{name}-blur.jpg"), quality=80, optimize=True)
    gray = blur.convert("L")
    regions = {k: luminance(gray, box) for k, box in REGIONS.items()}
    themes.append({
        "file": f"{name}.jpg",
        "source": item["source"],
        "set": item["set"],
        "top": "dark" if regions["top"] < DARK_BELOW else "light",
        "pills": "dark" if regions["pills"] < DARK_BELOW else "light",
        "dock": "dark" if regions["dock"] < DARK_BELOW else "light",
        "luminance": {k: round(v, 2) for k, v in regions.items()},
    })
    print(name, item["set"], {k: round(v, 2) for k, v in regions.items()}, item["source"])
json.dump(themes, open(themes_path, "w"), indent=1)
