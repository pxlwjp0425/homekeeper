#!/system/bin/sh
tr -d '\r' < /data/local/tmp/boot.sh.new > /data/audiofix/boot.sh
chmod 755 /data/audiofix/boot.sh
if sh -n /data/audiofix/boot.sh; then echo "SYNTAX_OK"; else echo "SYNTAX_FAIL"; fi
ls -la /data/audiofix/boot.sh
echo "=== first 20 lines ==="
head -n 20 /data/audiofix/boot.sh
echo "=== watch procs alive ==="
for q in /proc/[0-9]*; do
  [ -r "$q/cmdline" ] || continue
  c=$(tr -d '\0' < "$q/cmdline" 2>/dev/null)
  case "$c" in *keep-disp*|*keep-pcm*) echo "${q##*/}  $c" ;; esac
done
echo "=== density ==="
wm density
echo DONE
