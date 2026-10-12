#!/system/bin/sh
echo "=== audiofix.log ==="
cat /data/audiofix/audiofix.log 2>&1
echo "=== keep-pcm.log ==="
cat /data/audiofix/keep-pcm.log 2>&1
echo "=== try wm density 220 ==="
wm density 220 2>&1
echo "rc=$?"
echo "--- after ---"
wm density 2>&1
echo "=== display_density_forced ==="
settings get secure display_density_forced 2>&1
echo "=== id ==="
id
echo "=== getprop sys.boot_completed ==="
getprop sys.boot_completed
echo "=== mount check audio_policy ==="
mount | grep audio_policy 2>&1
echo "=== DONE ==="
