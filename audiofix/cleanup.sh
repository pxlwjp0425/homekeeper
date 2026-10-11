#!/system/bin/sh
# 清理临时文件与残留监控进程
for p in $(ps -A -o PID,ARGS | grep -E 'audio-mon\.sh|tone\.sh|ar\.sh|am\.sh' | grep -v grep | awk '{print $1}'); do
  kill "$p" 2>/dev/null && echo "killed $p"
done
rm -f /data/local/tmp/rc-new.sh /data/local/tmp/rc-new2.sh \
      /data/local/tmp/boot-new.sh /data/local/tmp/boot-new2.sh \
      /data/local/tmp/apply.sh /data/local/tmp/apply2.sh \
      /data/local/tmp/install2.sh /data/local/tmp/i2.sh \
      /data/local/tmp/restore-disp.sh /data/local/tmp/rd.sh \
      /data/local/tmp/am.sh /data/local/tmp/ar.sh \
      /data/local/tmp/audio-mon.sh /data/local/tmp/audio-run.sh \
      /data/local/tmp/audio-mon.log /data/local/tmp/mon.log \
      /data/local/tmp/rot-fix.sh /data/local/tmp/rot-fix2.sh \
      /data/local/tmp/rot0.sh /data/local/tmp/rot0c.sh \
      /data/local/tmp/densfix.sh /data/local/tmp/densfixc.sh \
      /data/local/tmp/dev-audio-off.sh /data/local/tmp/dao.sh \
      /data/local/tmp/dev-audio-on.sh /data/local/tmp/dao2.sh \
      /data/local/tmp/phfix.sh /data/local/tmp/phfix2.sh \
      /data/local/tmp/pv.sh /data/local/tmp/pv2.sh \
      /data/local/tmp/phloop.sh /data/local/tmp/phloop2.sh \
      /data/local/tmp/apc-test.sh /data/local/tmp/apct.sh \
      /data/local/tmp/audiofix-boot.sh /data/local/tmp/audiofix-install.sh \
      /data/local/tmp/ai2.sh /data/local/tmp/apc-hdmi.xml \
      /data/local/tmp/fix.jar /data/local/tmp/fixloop.jar
rm -f /sdcard/a.png /sdcard/b.png /sdcard/c.png /sdcard/s1.png /sdcard/s2.png \
      /sdcard/pre.png /sdcard/post.png /sdcard/p2.png /sdcard/ctrl.png \
      /sdcard/mon1.png /sdcard/h.png /sdcard/d1.png /sdcard/z.png \
      /sdcard/fl.txt /sdcard/fl2.txt /sdcard/ap.txt /sdcard/ui.xml
echo "cleanup done"
echo "--- /data/local/tmp ---"
ls /data/local/tmp/
echo "--- remain procs ---"
ps -A | grep -E 'audio-mon|tone\.sh|app_process' | grep -v grep || echo "(none)"
