#!/system/bin/sh
# HDMI 输出编码格式看护：强制 Stereo PCM
# ---------------------------------------------------------------
# 背景：这台电视的 EDID 里报了 MAT（Dolby TrueHD 的传输容器）能力，
# 平台 HAL 据此会把 "Audio I2S to HDMITX Format" 自动设成 Dolby TrueHD。
# 但电视实际解不了 TrueHD，收到 TrueHD 标记却满是 PCM 数据 → 解不出来 → 整机无声。
# 所以必须持续把它压回 Stereo PCM（索引 0）。
# 名字枚举不能用文字设（PCM/LPCM 都报 invalid enum value），只能按索引。
#   0=Stereo PCM  1=DTS RAW Mode  2=Dolby Digital  3=DTS
#   4=Dolby Digital Plus  5=DTS-HD  6=Multi-channel LPCM  7=Dolby TrueHD ...
# ---------------------------------------------------------------
LOG=/data/audiofix/keep-pcm.log
[ -f "$LOG" ] || echo "$(date '+%F %T') keep-pcm started" > "$LOG"

while true; do
  cur="$(tinymix 70 2>/dev/null)"
  case "$cur" in
    *">Stereo PCM"*)
      : # 已经是 PCM，什么都不做
      ;;
    *)
      tinymix 70 0 >/dev/null 2>&1
      echo "$(date '+%F %T') forced Stereo PCM (was: ${cur##*: })" >> "$LOG"
      ;;
  esac
  sleep 1
done
