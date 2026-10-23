"""Contact sheet of the bundled wallpapers grouped by set: python3 docs/design/wallpaper_sheet.py out.jpg"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFont
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
A = os.path.join(ROOT, 'launcher/app/src/main/assets/wallpapers/')
T = json.load(open(A + 'themes.json'))
try:
    f = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 20)
    hf = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 30)
except Exception:
    f = hf = ImageFont.load_default()
tw, th, cols = 480, 270, 4
rows = [(st, [t for t in T if t['set'] == st]) for st in ('day', 'night')]
H = sum(60 + ((len(i) + cols - 1) // cols) * (th + 40) for _, i in rows)
sheet = Image.new('RGB', (cols * tw + (cols + 1) * 12, H + 12), (18, 18, 18))
d = ImageDraw.Draw(sheet)
y = 12
for st, items in rows:
    d.text((12, y + 10), f"{st.upper()}  ({'06:00-22:00' if st == 'day' else '22:00-06:00'})  {len(items)} photos",
           font=hf, fill=(255, 226, 184) if st == 'night' else (240, 240, 240))
    y += 60
    for i, t in enumerate(items):
        x = 12 + (i % cols) * (tw + 12)
        yy = y + (i // cols) * (th + 40)
        sheet.paste(Image.open(A + t['file']).resize((tw, th)), (x, yy))
        src = t['source']
        lab = src[src.find('(') + 1:-1] if '(' in src else 'owner photo ' + src
        d.text((x, yy + th + 6), f"{t['file'][:2]} · {st} · {lab}", font=f, fill=(220, 220, 220))
    y += ((len(items) + cols - 1) // cols) * (th + 40)
sheet.save(sys.argv[1], quality=85)
