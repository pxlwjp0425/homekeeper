import os, re, sys
from multiprocessing import Pool
from PIL import Image

BASE = r"D:\User\Pictures\BingWallpapers"
OUT = r"C:\Users\pxlwj\WorkBuddy\2026-10-03-10-18-30\homekeeper\bing-import\out"
TARGET = (1920, 1080)
Q = 85


def collect():
    out = {}
    conflicts = 0
    for root, dirs, fs in os.walk(BASE):
        for f in fs:
            if not f.lower().endswith(".jpg"):
                continue
            m = re.match(r"^(\d{8})", f)
            if not m:
                continue
            d = m.group(1)
            p = os.path.join(root, f)
            if d in out:
                conflicts += 1
                # 同日期保留体积更大的一张
                if os.path.getsize(p) <= os.path.getsize(out[d]):
                    continue
            out[d] = p
    return out, conflicts


def work(args):
    date, src = args
    dst = os.path.join(OUT, "bing_%s.jpg" % date)
    try:
        with Image.open(src) as im0:
            im = im0.convert("RGB")
        w, h = im.size
        ar_img = w / h
        ar_t = TARGET[0] / TARGET[1]
        if ar_img > ar_t:          # 更宽 -> 裁两侧
            nw = int(h * ar_t)
            left = (w - nw) // 2
            im = im.crop((left, 0, left + nw, h))
        elif ar_img < ar_t:        # 更高 -> 裁上下
            nh = int(w / ar_t)
            top = (h - nh) // 2
            im = im.crop((0, top, w, top + nh))
        if im.size != TARGET:
            im = im.resize(TARGET, Image.LANCZOS)
        im.save(dst, "JPEG", quality=Q, optimize=True, progressive=True)
        return (date, os.path.getsize(dst))
    except Exception as e:
        return (date, "ERR %s" % e)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    d, conflicts = collect()
    items = sorted(d.items())
    print("unique dates: %d  (same-date conflicts: %d)" % (len(items), conflicts), flush=True)
    done = 0
    total = 0
    fails = []
    with Pool(8) as pool:
        for date, sz in pool.imap_unordered(work, items):
            done += 1
            if isinstance(sz, int):
                total += sz
            else:
                fails.append((date, sz))
            if done % 200 == 0:
                print("  %d/%d   %.0f MB" % (done, len(items), total / 1048576.0), flush=True)
    print("DONE %d files, %.0f MB, fails=%d" % (done, total / 1048576.0, len(fails)), flush=True)
    for f in fails[:10]:
        print("  FAIL", f, flush=True)
