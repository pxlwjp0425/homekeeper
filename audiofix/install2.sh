#!/system/bin/sh
tr -d '\r' < /data/local/tmp/boot-new.sh > /data/local/tmp/bn.sh
cat /data/local/tmp/bn.sh > /data/audiofix/boot.sh
chmod 755 /data/audiofix/boot.sh
echo "installed"
wc -l /data/audiofix/boot.sh
echo "--- tail ---"
tail -16 /data/audiofix/boot.sh
