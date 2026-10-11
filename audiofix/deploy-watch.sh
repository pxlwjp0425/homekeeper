#!/system/bin/sh
D=/data/audiofix
tr -d '\r' < /data/local/tmp/keep-disp.sh > $D/keep-disp.sh
tr -d '\r' < /data/local/tmp/boot.sh.new > $D/boot.sh
chmod 755 $D/keep-disp.sh $D/boot.sh
echo "=== files ==="
ls -la $D/keep-disp.sh $D/boot.sh

running=$(for q in /proc/[0-9]*; do [ -r "$q/cmdline" ] || continue; tr -d '\0' < "$q/cmdline" 2>/dev/null | grep -q keep-disp.sh && echo yes && break; done)
if [ "$running" = "yes" ]; then
  echo "keep-disp already running"
else
  nohup sh $D/keep-disp.sh >/dev/null 2>&1 &
  echo "keep-disp started pid $!"
fi

sleep 12
echo "=== running procs ==="
for q in /proc/[0-9]*; do
  [ -r "$q/cmdline" ] || continue
  c=$(tr -d '\0' < "$q/cmdline" 2>/dev/null)
  case "$c" in *keep-disp*|*keep-pcm*) echo "${q##*/}  $c" ;; esac
done
echo "=== density ==="
wm density
echo "forced=$(settings get secure display_density_forced)"
echo "rot=$(settings get system user_rotation) accel=$(settings get system accelerometer_rotation)"
echo "=== keep-disp.log ==="
cat $D/keep-disp.log 2>&1
echo "=== keep-pcm.log tail ==="
tail -n 5 $D/keep-pcm.log 2>&1
echo "=== DONE ==="
