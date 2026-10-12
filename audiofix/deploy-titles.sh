#!/system/bin/sh
# 把 titles.json 放进 homekeeper 私有目录，供 App 读取显示标题
B=/data/data/com.xiaopaitech.wallpaperlauncher/files/bing
SRC=/data/local/tmp/titles.json

if [ ! -f "$SRC" ]; then echo "!! $SRC 不存在"; exit 1; fi
[ -d "$B" ] || { echo "!! 私有目录不存在: $B"; exit 1; }

U=$(stat -c %u "$B" 2>/dev/null)
echo "app uid=$U  dir=$B"

# 备份旧文件（若有）
[ -f "$B/titles.json" ] && cp -f "$B/titles.json" /data/local/tmp/titles.json.bak

cp -f "$SRC" "$B/titles.json"
chown "$U:$U" "$B/titles.json" 2>/dev/null
chmod 600 "$B/titles.json"
restorecon -RF "$B" 2>/dev/null

echo "=== installed ==="
ls -laZ "$B/titles.json" 2>/dev/null || ls -la "$B/titles.json"
echo "bytes: $(wc -c < "$B/titles.json")"
echo "head:  $(head -c 120 "$B/titles.json")"
