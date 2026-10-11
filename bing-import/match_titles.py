# -*- coding: utf-8 -*-
"""
把电脑 Camera Roll 里带中文名的壁纸，按「图片内容」匹配到设备图池（out/），
生成 日期(yyyyMMdd) -> 标题 的映射 titles.json。
不用日期匹配：两边的日期约定差一天（Bing startdate 按 UTC）。
"""
import os, sys, json, io

CR  = r"D:\User\Pictures\Camera Roll"
OUT = r"C:\Users\pxlwj\WorkBuddy\2026-10-03-10-18-30\homekeeper\bing-import\out"
DST = r"C:\Users\pxlwj\WorkBuddy\2026-10-03-10-18-30\homekeeper\bing-import"
THRESHOLD = 10   # 128 位里允许的最大汉明距离

from PIL import Image

def dh(img):
    g = img.convert("L").resize((9, 8), Image.LANCZOS)
    p = list(g.getdata())
    h = 0
    for y in range(8):
        base = y * 9
        for x in range(8):
            h = (h << 1) | (1 if p[base + x] < p[base + x + 1] else 0)
    return h

def ah(img):
    g = img.convert("L").resize((8, 8), Image.LANCZOS)
    p = list(g.getdata())
    avg = sum(p) / 64.0
    h = 0
    for v in p:
        h = (h << 1) | (1 if v >= avg else 0)
    return h

def hashes(path):
    with Image.open(path) as im:
        im.load()
        return dh(im), ah(im)

def title_of(name):
    stem = name[:-4]  # strip .jpg
    if stem.startswith("日期未知 "):
        return stem[len("日期未知 "):].strip()
    # "YYYY-MM-DD 标题"
    if len(stem) > 11 and stem[:4].isdigit() and stem[10] == " ":
        return stem[11:].strip()
    return stem.strip()

# ---- 读 Camera Roll ----
cr = []
skipped = 0
for n in sorted(os.listdir(CR)):
    if not n.lower().endswith((".jpg", ".jpeg", ".png")):
        continue
    if n.startswith("."):
        continue
    p = os.path.join(CR, n)
    try:
        h = hashes(p)
        cr.append((n, h[0], h[1]))
    except Exception as e:
        skipped += 1
print(f"Camera Roll 可用图片: {len(cr)}  (跳过 {skipped})", flush=True)

# ---- 读 out/ ----
outs = []
for n in sorted(os.listdir(OUT)):
    if not n.lower().endswith(".jpg"):
        continue
    if not n.startswith("bing_") or len(n) != 17:   # bing_YYYYMMDD.jpg = 5+8+4
        continue
    p = os.path.join(OUT, n)
    try:
        h = hashes(p)
        outs.append((n[5:13], n, h[0], h[1]))
    except Exception:
        pass
print(f"设备图池(out)图片: {len(outs)}", flush=True)

# ---- 逐张找最近邻 ----
mapping = {}
dist_hist = {}
samples = []
for i, (date, name, odh, oah) in enumerate(outs):
    bestd = 999
    bestn = None
    for (cn, cdh, cah) in cr:
        d = (odh ^ cdh).bit_count() + (oah ^ cah).bit_count()
        if d < bestd:
            bestd = d
            bestn = cn
    dist_hist[bestd] = dist_hist.get(bestd, 0) + 1
    if bestd <= THRESHOLD:
        mapping[date] = title_of(bestn)
        if len(samples) < 25:
            samples.append((date, bestd, bestn))
    if (i + 1) % 400 == 0:
        print(f"  ...{i+1}/{len(outs)}", flush=True)

print(f"\n匹配成功: {len(mapping)} / {len(outs)}")
print("距离分布(距离:张数):", dict(sorted(dist_hist.items())[:20]))
print("\n样例(日期,距离,匹配到的CR文件):")
for s in samples:
    print("  ", s[0], s[1], s[2])

with open(os.path.join(DST, "titles.json"), "w", encoding="utf-8") as f:
    json.dump(mapping, f, ensure_ascii=False, indent=1)
print(f"\n已写出 {os.path.join(DST,'titles.json')}  ({len(mapping)} 条)")
