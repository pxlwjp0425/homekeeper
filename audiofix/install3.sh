#!/system/bin/sh
echo "=== verify tinymix set (index 0 = Stereo PCM) ==="
tinymix "Audio I2S to HDMITX Format" 0
echo "rc=$?"
echo "value: $(tinymix -v "Audio I2S to HDMITX Format")"
echo "=== install boot.sh ==="
tr -d '\r' < /data/local/tmp/boot-new3.sh > /data/local/tmp/bn3.sh
cat /data/local/tmp/bn3.sh > /data/audiofix/boot.sh
chmod 755 /data/audiofix/boot.sh
echo "installed, lines:"
wc -l /data/audiofix/boot.sh
echo "--- tail 10 ---"
tail -10 /data/audiofix/boot.sh
