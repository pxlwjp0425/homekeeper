#!/system/bin/sh
tr -d '\r' < /data/local/tmp/rc-new.sh > /data/local/tmp/rc-new2.sh
cat /data/local/tmp/rc-new2.sh > /data/xpclouddrive/run_clouddrive.sh
tr -d '\r' < /data/local/tmp/boot-new.sh > /data/local/tmp/boot-new2.sh
cat /data/local/tmp/boot-new2.sh > /data/audiofix/boot.sh
chmod 755 /data/audiofix/boot.sh
echo "APPLIED"
echo "--- run_clouddrive.sh (first 6) ---"
head -6 /data/xpclouddrive/run_clouddrive.sh
echo "--- run_clouddrive.sh (last 2) ---"
tail -2 /data/xpclouddrive/run_clouddrive.sh
echo "--- boot.sh head ---"
head -3 /data/audiofix/boot.sh
echo "--- perms ---"
ls -la /data/xpclouddrive/run_clouddrive.sh /data/audiofix/boot.sh
