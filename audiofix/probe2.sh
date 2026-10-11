#!/system/bin/sh
echo "=== DENSITY ==="
wm density
echo "=== SIZE ==="
wm size
echo "=== user_rotation ==="
settings get system user_rotation
echo "=== accelerometer_rotation ==="
settings get system accelerometer_rotation
echo "=== display_density_forced ==="
settings get secure display_density_forced
echo "=== audiofix dir ==="
ls -la /data/audiofix/ 2>&1
echo "=== keep-pcm.pid ==="
cat /data/audiofix/keep-pcm.pid 2>&1
echo "=== pids ==="
ls /proc | grep -E '^[0-9]+$' | while read p; do
  if [ -r /proc/$p/cmdline ]; then
    c=$(tr -d '\0' < /proc/$p/cmdline 2>/dev/null)
    case "$c" in *keep-pcm*|*tinymix*) echo "$p $c";; esac
  fi
done
echo "=== boot.sh ==="
cat /data/audiofix/boot.sh 2>&1
echo "=== run_clouddrive.sh ==="
cat /data/xpclouddrive/run_clouddrive.sh 2>&1
echo "=== audiofix logs ==="
ls -la /data/audiofix/*.log 2>&1
echo "--- boot.log tail ---"
tail -n 40 /data/audiofix/boot.log 2>&1
echo "--- keep-pcm.log tail ---"
tail -n 10 /data/audiofix/keep-pcm.log 2>&1
echo "=== DONE ==="
