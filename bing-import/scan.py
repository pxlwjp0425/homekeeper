import os, random, re, sys
from PIL import Image

BASE = r"D:\User\Pictures\BingWallpapers"
files = []
for root, dirs, fs in os.walk(BASE):
    for f in fs:
        if f.lower().endswith(".jpg"):
            files.append(os.path.join(root, f))

print("total jpg:", len(files))

# 命名规范统计
ok8 = 0
nomatch = []
for p in files:
    name = os.path.basename(p)
    m = re.match(r"^(\d{8})", name)
    if m:
        ok8 += 1
    else:
        nomatch.append(name)
print("leading-8-digit OK:", ok8, " not-match:", len(nomatch))
for n in nomatch[:10]:
    print("   NOMATCH:", n)

# 样本尺寸
random.seed(42)
sample = random.sample(files, min(10, len(files)))
print("--- sample ---")
sizes = {}
for p in sample:
    try:
        im = Image.open(p)
        kb = os.path.getsize(p) // 1024
        print(f"{os.path.basename(p)[:50]:52s} {im.size}  {kb}KB  {im.mode}")
        sizes[im.size] = sizes.get(im.size, 0) + 1
    except Exception as e:
        print(p, "ERR", e)

# 全量尺寸分布（只读 header，快）
print("--- size distribution (all) ---")
dist = {}
for p in files:
    try:
        with Image.open(p) as im:
            dist[im.size] = dist.get(im.size, 0) + 1
    except Exception:
        pass
for k, v in sorted(dist.items(), key=lambda x: -x[1])[:12]:
    print(f"   {k}: {v}")
