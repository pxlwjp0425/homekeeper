# -*- coding: utf-8 -*-
"""
爱奇艺「回桌面后播放页被销毁」修复补丁。

做的事：把 AndroidManifest.xml 里 HomeActivity 的 launchMode 由
singleTask(2) 改成 standard(0) —— 只改那 4 个字节，APK 内其它条目字节级原样保留。

为什么不用 apktool 重建：这个包有 6 个 dex + 大量 so，重建会重排压缩方式、
还要处理 doNotCompress 列表，风险大；而 launchMode 是定长 INT_DEC，原地改不动长度，
不影响任何偏移。

用法：
    python iqpatch.py <原始.apk> <输出-未签名.apk> [入口类名后缀，默认 HomeActivity]
之后：
    zipalign -p -f 4 <输出-未签名.apk> aligned.apk
    apksigner sign --ks iq.keystore ... --out final.apk aligned.apk
    adb uninstall com.gitvvideo.skyworthopen && adb install final.apk
"""
import os
import struct
import sys
import zipfile


def scan_launchmode(data, suffix):
    """在二进制 AXML 里找 <activity android:name="*<suffix>"> 的 android:launchMode。"""
    u8 = lambda p: data[p]
    u16 = lambda p: struct.unpack_from('<H', data, p)[0]
    u32 = lambda p: struct.unpack_from('<I', data, p)[0]

    hs = u16(2)

    pos, sp = hs, None
    while pos + 8 <= len(data):
        t, sz = u16(pos), u32(pos + 4)
        if sz <= 0:
            break
        if t == 0x0001:
            sp = pos
            break
        pos += sz
    if sp is None:
        raise SystemExit('找不到 string pool，manifest 可能不是二进制 AXML')

    scount = u32(sp + 8)
    flags = u32(sp + 16)
    sstart = u32(sp + 20)
    utf8 = bool(flags & 0x100)
    offs = [u32(sp + 28 + 4 * i) for i in range(scount)]

    def gs(i):
        if i < 0 or i >= scount:
            return None
        p = sp + sstart + offs[i]
        if utf8:
            n = data[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7f) << 8) | data[p]
                p += 1
            bl = data[p]
            p += 1
            if bl & 0x80:
                bl = ((bl & 0x7f) << 8) | data[p]
                p += 1
            return data[p:p + bl].decode('utf-8', 'replace')
        n = u16(p)
        p += 2
        if n & 0x8000:
            n = ((n & 0x7fff) << 16) | u16(p)
            p += 2
        return data[p:p + 2 * n].decode('utf-16-le', 'replace')

    hits = []
    pos = hs
    while pos + 8 <= len(data):
        t, sz = u16(pos), u32(pos + 4)
        if sz <= 0:
            break
        if t == 0x0102:  # START_ELEMENT
            if gs(u32(pos + 20)) in ('activity', 'activity-alias'):
                astart, asize, acount = u16(pos + 24), u16(pos + 26), u16(pos + 28)
                base = pos + 16 + astart
                attrs = {}
                for i in range(acount):
                    a = base + i * asize
                    attrs[gs(u32(a + 4))] = (a, u8(a + 15), u32(a + 16))
                nm = attrs.get('name')
                if nm and nm[1] == 0x03:          # TYPE_STRING
                    cls = gs(nm[2])
                    if cls and cls.endswith(suffix) and 'launchMode' in attrs:
                        a, tt, td = attrs['launchMode']
                        hits.append((a + 16, tt, td, cls))   # +16 = typed value 的 data
        pos += sz
    return hits


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    suffix = sys.argv[3] if len(sys.argv) > 3 else 'HomeActivity'

    if os.path.exists(dst):
        os.remove(dst)

    zin = zipfile.ZipFile(src)
    man = zin.read('AndroidManifest.xml')
    print('manifest 大小 =', len(man))

    hits = scan_launchmode(man, suffix)
    if not hits:
        raise SystemExit('没有找到 %s 的 launchMode 属性' % suffix)
    for off, tt, td, cls in hits:
        print('  命中 %s  launchMode=%d  值偏移=%d (0x%x) 类型=0x%02x' % (cls, td, off, off, tt))
    if len(hits) != 1:
        raise SystemExit('命中 %d 处，为保证安全要求唯一，已中止' % len(hits))

    off, tt, td, cls = hits[0]
    if td == 0:
        print('  ⚠ 当前已经是 standard(0)，无需修改')
    b = bytearray(man)
    struct.pack_into('<I', b, off, 0)
    man_new = bytes(b)
    print('  已改：launchMode %d -> 0（standard），长度不变，其它偏移不受影响' % td)

    SIG = ('.SF', '.RSA', '.DSA', '.EC', '.MF')
    zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
    kept = 0
    for it in zin.infolist():
        up = it.filename.upper()
        if up.startswith('META-INF/') and (up == 'META-INF/MANIFEST.MF' or up.endswith(SIG)):
            continue                      # 旧签名要剔除，否则和新签名打架
        d = man_new if it.filename == 'AndroidManifest.xml' else zin.read(it.filename)
        zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
        zi.compress_type = it.compress_type
        zi.external_attr = it.external_attr
        zi.internal_attr = it.internal_attr
        zi.create_system = it.create_system
        zi.comment = it.comment
        zout.writestr(zi, d)
        kept += 1
    zout.close()
    zin.close()
    print('  已写出 %s（%d 个条目，原始压缩方式保持不变）%d bytes'
          % (dst, kept, os.path.getsize(dst)))
    print('  下一步：zipalign -p -f 4 → apksigner sign → 卸载重装')


if __name__ == '__main__':
    main()
