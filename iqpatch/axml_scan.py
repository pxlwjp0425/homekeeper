# -*- coding: utf-8 -*-
"""
扫描 AndroidManifest.xml（二进制 AXML），定位 HomeActivity 的 launchMode 属性。
只读，不修改任何东西。
"""
import struct
import zipfile
import sys

APK = sys.argv[1] if len(sys.argv) > 1 else r'C:\Users\pxlwj\WorkBuddy\2026-10-03-10-18-30\homekeeper\tmp\iq.apk'

zf = zipfile.ZipFile(APK)
data = zf.read('AndroidManifest.xml')
print('manifest 解压后大小 =', len(data))

u8 = lambda p: data[p]
u16 = lambda p: struct.unpack_from('<H', data, p)[0]
u32 = lambda p: struct.unpack_from('<I', data, p)[0]

magic, hs, fsize = u16(0), u16(2), u32(4)
print('magic=0x%04x headerSize=%d fileSize=%d' % (magic, hs, fsize))

# ---- 定位 string pool ----
pos = hs
sp = None
chunks = []
while pos + 8 <= len(data):
    ctype = u16(pos)
    chsize = u32(pos + 4)
    if chsize <= 0:
        break
    chunks.append((pos, ctype, chsize))
    if ctype == 0x0001 and sp is None:
        sp = pos
    pos += chsize
print('顶层 chunk 数 =', len(chunks), ' string pool @', sp)

scount = u32(sp + 8)
styleCount = u32(sp + 12)
flags = u32(sp + 16)
stringsStart = u32(sp + 20)
utf8 = bool(flags & 0x100)
print('stringCount=%d utf8=%s' % (scount, utf8))

offs = [u32(sp + 28 + 4 * i) for i in range(scount)]


def getstr(i):
    if i < 0 or i >= scount:
        return None
    p = sp + stringsStart + offs[i]
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
    else:
        n = u16(p)
        p += 2
        if n & 0x8000:
            n = ((n & 0x7fff) << 16) | u16(p)
            p += 2
        return data[p:p + n * 2].decode('utf-16-le', 'replace')


strings = [getstr(i) for i in range(scount)]

lm_idx = {i for i, s in enumerate(strings) if s == 'launchMode'}
ha_idx = {i for i, s in enumerate(strings) if s and s.endswith('HomeActivity')}
print('launchMode 的 string 索引 =', sorted(lm_idx))
print('HomeActivity 的 string 索引 =', sorted(ha_idx))

# ---- 遍历 start element ----
TY = {0x02: '?', 0x03: 'NULL', 0x10: 'INT_DEC', 0x11: 'INT_HEX', 0x12: 'INT_BOOL', 0x01: 'REF'}
found = []
pos = hs
while pos + 8 <= len(data):
    ctype = u16(pos)
    chsize = u32(pos + 4)
    if chsize <= 0:
        break
    if ctype == 0x0102:  # START_ELEMENT
        name_idx = u32(pos + 20)
        ename = getstr(name_idx)
        if ename in ('activity', 'activity-alias'):
            attr_start = u16(pos + 24)
            attr_size = u16(pos + 26)
            attr_count = u16(pos + 28)
            apos = pos + 16 + attr_start
            attrs = {}
            for i in range(attr_count):
                a = apos + i * attr_size
                aname = getstr(u32(a + 4))
                attrs[aname] = (a, u8(a + 15), u32(a + 16))
            nm = attrs.get('name')
            if nm and nm[1] == 0x03:
                cls = getstr(nm[2])
                if cls and cls.endswith('HomeActivity'):
                    print('\n[元素] <%s android:name="%s">  chunk@%d 属性数=%d'
                          % (ename, cls, pos, attr_count))
                    for k, (a, tt, td) in attrs.items():
                        extra = ''
                        if tt == 0x03:
                            extra = '  -> "%s"' % getstr(td)
                        mark = ''
                        if k == 'launchMode':
                            mark = '   <<<<<< 要改的就是这里'
                            found.append((a + 16, tt, td))
                        print('   attr@%-7d %-16s type=0x%02x(%s) data=%-6d%s%s'
                              % (a, k, tt, TY.get(tt, '?'), td, extra, mark))
    pos += chsize

print('\n===== 结论 =====')
for off, ttype, tdata in found:
    print('launchMode 值的文件偏移 = %d (0x%x)，类型=0x%02x，当前值=%d' % (off, off, ttype, tdata))
print('（2=singleTask，0=standard；把这个 4 字节改成 0 即可）')
