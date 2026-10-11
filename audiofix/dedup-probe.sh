#!/system/bin/sh
D=/data/data/com.xiaopaitech.wallpaperlauncher/files/bing
cd "$D" || exit 1
echo "=== count ==="
ls bing_*.jpg 2>/dev/null | wc -l
echo "=== dup filenames (same date twice) ==="
ls bing_*.jpg | sed 's/^bing_//; s/\.jpg$//' | sort | uniq -d
echo "=== 2026-09/10 entries (name + size) ==="
ls -la bing_202609*.jpg bing_202610*.jpg 2>/dev/null
echo "=== same-size groups (>=2) among ALL ==="
# size:name  -> group by size
ls -l bing_*.jpg | awk '{print $5}' | sort -n | uniq -c | awk '$1>1'
echo "=== md5 scan (may take ~1-2 min) ==="
md5sum bing_*.jpg 2>/dev/null | sort > /data/local/tmp/md5all.txt
echo "hashed: $(wc -l < /data/local/tmp/md5all.txt)"
echo "=== duplicate CONTENT (same md5, different files) ==="
awk '{print $1}' /data/local/tmp/md5all.txt | uniq -d > /data/local/tmp/duphash.txt
echo "dup-hash count: $(wc -l < /data/local/tmp/duphash.txt)"
while read h; do
  echo "--- $h"
  grep "^$h" /data/local/tmp/md5all.txt | awk '{print "    "$2}'
done < /data/local/tmp/duphash.txt
echo "=== done ==="
