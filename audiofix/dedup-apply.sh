#!/system/bin/sh
D=/data/data/com.xiaopaitech.wallpaperlauncher/files/bing
cd "$D" || exit 1
echo "before: $(ls bing_*.jpg | wc -l)"
# bing_20210722.jpg 与 bing_20210804.jpg 内容完全相同 -> 保留较早的 0722，移除 0804
if [ -f bing_20210804.jpg ] && [ "$(md5sum bing_20210722.jpg | cut -d' ' -f1)" = "$(md5sum bing_20210804.jpg | cut -d' ' -f1)" ]; then
  rm -f bing_20210804.jpg
  echo "removed bing_20210804.jpg (dup of bing_20210722.jpg)"
else
  echo "skip: 条件不满足"
fi
echo "after:  $(ls bing_*.jpg | wc -l)"
