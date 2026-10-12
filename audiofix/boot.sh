#!/system/bin/sh
# 开机自愈：HDMI 音频 + 显示设置
# 详见 zte-smarthome/AUDIO-TV.md
#
# 问题：中兴智慧生活进实时预览后，音频走 STREAM_VOICE_CALL + MODE_IN_COMMUNICATION，
#       而本机 audio_policy 的 defaultOutputDevice = Speaker（物理不存在）→ 电视无声。
# 手段：用"HDMI Out 作 defaultOutputDevice、且 Speaker/Earpiece 不再 attached"的
#       audio_policy_configuration.xml 覆盖原文件，通话流就会回落到 HDMI。
#       /vendor 是 dm-verity 只读，只能用 bind mount，所以每次开机都要重挂。
#
# 注意：本脚本由 /data/xpclouddrive/run_clouddrive.sh 在 nsenter **之前** 调起，
#       执行时机可能偏早，所以下面先等 audioserver 就绪再重启它。
LOG=/data/audiofix/audiofix.log
SRC=/data/audiofix/audio_policy_configuration.xml
DST=/vendor/etc/audio_policy_configuration.xml

{
  echo "===== $(date) audiofix boot ====="

  # ---- 常驻看护：最先启动 ----
  # 系统/固件会在开机后清掉 display_density_forced（实测 boot 后设成功、随后被清），
  # 所以真正的兜底是 keep-disp.sh 每 5 秒补一遍；它还顺带看护 keep-pcm.sh。
  # 放在最前面：即使下面的音频流程卡住（等 audioserver 最长 90s），显示也不会被落下。
  _p=$(for q in /proc/[0-9]*; do
         [ -r "$q/cmdline" ] || continue
         tr -d '\0' < "$q/cmdline" 2>/dev/null | grep -q keep-disp.sh && echo "$q" && break
       done)
  if [ -n "$_p" ]; then
    echo "keep-disp already running (pid ${_p##*/})"
  else
    nohup sh /data/audiofix/keep-disp.sh >/dev/null 2>&1 &
    echo "keep-disp started (pid $!)"
  fi

  if [ ! -f "$SRC" ]; then
    echo "!! $SRC 不存在，跳过音频部分"
    exit 0
  fi

  # 等 audioserver 就绪（最多 90 秒）
  i=0
  st=""
  while [ "$i" -lt 90 ]; do
    st=$(getprop init.svc.audioserver)
    [ "$st" = "running" ] && break
    i=$((i+1))
    sleep 1
  done
  echo "audioserver svc=$st (waited ${i}s)"

  if mount | grep -q "on $DST "; then
    echo "已处于挂载状态，跳过 mount"
  else
    mount -o bind "$SRC" "$DST" && echo "bind mount ok" || echo "!! bind mount 失败"
  fi
  sleep 2
  setprop ctl.restart audioserver
  echo "audioserver restarted"

  # ---- 显示设置 ----
  # density=220：让竖屏手机 App（中兴/米家）切进内置平板横屏布局并铺满
  #   （320→220 使 smallestWidthDp 540→785dp，越过 600dp 平板阈值）
  # user_rotation=0 + accelerometer_rotation=0：锁死横屏（本机实测 0=横屏 / 1=竖屏）
  # 等 boot_completed 再设，避免被 SystemServer 初始化覆盖
  j=0
  while [ "$j" -lt 120 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    j=$((j+1)); sleep 1
  done
  echo "boot_completed after ${j}s"
  wm density 220
  settings put system user_rotation 0
  settings put system accelerometer_rotation 0
  echo "display applied: $(wm density | tr '\n' ' ')"

  # ---- HDMI 输出编码格式：强制 Stereo PCM ----
  # 电视 EDID 报了 MAT（TrueHD 容器）能力 → AML HAL 会把格式设成 "Dolby TrueHD"，
  # 但实际送的是 PCM 数据、电视解不了 → 整机无声。
  # 索引 0 = "Stereo PCM"（tinymix 只认索引，不认名字）。HAL 会反复改回，故需守护压制。
  tinymix 70 0
  echo "hdmi format -> $(tinymix 70 | sed 's/ DTS RAW.*//')"
} >> "$LOG" 2>&1
