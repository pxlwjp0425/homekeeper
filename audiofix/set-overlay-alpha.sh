#!/system/bin/sh
# 把悬浮信息条的底色改成全透明（bgAlpha: 55 -> 0）
F=/sdcard/homekeeper/config.json

echo "=== before ==="
grep -A8 '"overlay"' $F

sed 's/"bgAlpha": 55/"bgAlpha": 0/' $F > /data/local/tmp/cfg.new
if [ -s /data/local/tmp/cfg.new ]; then
  cat /data/local/tmp/cfg.new > $F     # 保 inode 覆盖写，避免改属主/丢失 App 权限
fi
rm -f /data/local/tmp/cfg.new

echo "=== after ==="
grep -A8 '"overlay"' $F
echo "=== done ==="
