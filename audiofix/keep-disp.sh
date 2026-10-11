#!/system/bin/sh
# keep-disp.sh —— 显示参数常驻看护（density / 屏幕方向）
# ---------------------------------------------------------------
# 背景：density override（Settings.Secure.display_density_forced）在开机后会被
#       系统/固件清掉；boot.sh 里"开机设一次"实测不可靠（日志显示设成功，
#       但随后被清除）。本脚本每 5 秒检查一次，掉了立即补回。
#   density=220         → 中兴/米家等竖屏手机 App 切进内置平板横屏布局并铺满
#   user_rotation=0     → 锁横屏（本机实测 0=横屏 / 1=竖屏）
#   accelerometer_rotation=0 → 关重力感应自动旋转
# 顺带兜底：keep-pcm.sh（HDMI 格式守护）若被杀则重新拉起。
LOG=/data/audiofix/keep-disp.log
PIDF=/data/audiofix/keep-disp.pid

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

# 用 /proc 扫描判存活（不用 pid 文件：实测 pid 文件会莫名丢失）
proc_running() {
  _pat="$1"
  for p in /proc/[0-9]*; do
    [ -r "$p/cmdline" ] || continue
    c=$(tr -d '\0' < "$p/cmdline" 2>/dev/null)
    case "$c" in *"$_pat"*) return 0 ;; esac
  done
  return 1
}

echo $$ > "$PIDF"
log "keep-disp started (pid $$)"

while true; do
  d=$(settings get secure display_density_forced 2>/dev/null)
  if [ "$d" != "220" ]; then
    wm density 220 >/dev/null 2>&1
    log "density: '$d' -> 220"
  fi

  r=$(settings get system user_rotation 2>/dev/null)
  if [ "$r" != "0" ]; then
    settings put system user_rotation 0 >/dev/null 2>&1
    log "user_rotation: '$r' -> 0"
  fi

  a=$(settings get system accelerometer_rotation 2>/dev/null)
  if [ "$a" != "0" ]; then
    settings put system accelerometer_rotation 0 >/dev/null 2>&1
    log "accelerometer_rotation: '$a' -> 0"
  fi

  if ! proc_running keep-pcm.sh; then
    nohup sh /data/audiofix/keep-pcm.sh >/dev/null 2>&1 &
    log "keep-pcm (re)started (pid $!)"
  fi

  sleep 5
done
