#!/system/bin/sh
P=com.xiaopaitech.wallpaperlauncher
D=/data/data/$P/files/bing
echo "=== bing dir ==="
ls -la $D 2>&1 | head -50
echo "=== count ==="
ls $D 2>/dev/null | wc -l
echo "=== files/ ==="
ls -la /data/data/$P/files/ 2>&1 | head -20
echo "=== pkg owner ==="
ls -ld /data/data/$P
echo "=== config.json ==="
tail -n 40 /sdcard/homekeeper/config.json 2>&1
echo "=== prefs ==="
PREF=/data/data/$P/shared_prefs
ls -la $PREF 2>&1
echo "--- hk.xml ---"
cat $PREF/hk.xml 2>&1 | tr -d '\r'
echo DONE
