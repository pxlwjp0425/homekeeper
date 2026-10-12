#!/system/bin/sh
# 安装 keep-pcm 看护 + 更新 boot.sh（以 root 执行）
set -x
cp /data/local/tmp/keep-pcm-new.sh /data/audiofix/keep-pcm.sh
chmod 755 /data/audiofix/keep-pcm.sh
cat /data/local/tmp/boot-new4.sh > /data/audiofix/boot.sh
chmod 755 /data/audiofix/boot.sh
echo "boot.sh lines: $(wc -l < /data/audiofix/boot.sh)"

# 立即启动看护（幂等）
if [ -f /data/audiofix/keep-pcm.pid ] && kill -0 "$(cat /data/audiofix/keep-pcm.pid)" 2>/dev/null; then
  echo "keep-pcm already running pid=$(cat /data/audiofix/keep-pcm.pid)"
else
  nohup sh /data/audiofix/keep-pcm.sh >/dev/null 2>&1 &
  echo $! > /data/audiofix/keep-pcm.pid
  echo "keep-pcm started pid=$!"
fi

tinymix 70 0
echo "fmt=$(tinymix 70 | sed 's/ DTS RAW.*//')"
