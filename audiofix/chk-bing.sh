#!/system/bin/sh
echo "=== density ==="; wm density
echo "=== watch procs ==="
for q in /proc/[0-9]*; do
  [ -r "$q/cmdline" ] || continue
  c=$(tr -d '\0' < "$q/cmdline" 2>/dev/null)
  case "$c" in *keep-disp*|*keep-pcm*) echo "${q##*/} $c" ;; esac
done
echo "=== pool count ==="
ls /data/data/com.xiaopaitech.wallpaperlauncher/files/bing | wc -l
echo "=== disk ==="
df -h /data | tail -1
echo "=== /data/local/tmp leftover ==="
ls -la /data/local/tmp/ 2>/dev/null | head
echo "=== keep-disp.log tail ==="
tail -n 5 /data/audiofix/keep-disp.log 2>&1
echo DONE
