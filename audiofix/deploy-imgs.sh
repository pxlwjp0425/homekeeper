#!/system/bin/sh
# 把 /data/local/tmp/bing.tar（平铺的 bing_YYYYMMDD.jpg）直接解到 App 私有图池
TB=/data/local/tmp/bing.tar
DST=/data/data/com.xiaopaitech.wallpaperlauncher/files/bing
U=u0_a52

if [ ! -f "$TB" ]; then echo "NO_TAR"; exit 1; fi
mkdir -p "$DST"

before=$(ls "$DST" | grep -c '^bing_.*\.jpg$')
echo "pool before=$before"

echo "extracting..."
if ! tar -xf "$TB" -C "$DST"; then echo "TAR_FAIL"; exit 1; fi

after=$(ls "$DST" | grep -c '^bing_.*\.jpg$')
echo "pool after=$after"

echo "fixing owner/mode..."
chown -R $U:$U "$DST"
find "$DST" -maxdepth 1 -name 'bing_*.jpg' -exec chmod 600 {} +
restorecon -RF "$DST" 2>/dev/null

echo "on disk: $(du -sh "$DST" 2>/dev/null | cut -f1)"
echo "sample:"
ls -la "$DST" | head -3
ls -la "$DST" | tail -2

rm -f "$TB"
echo DONE
