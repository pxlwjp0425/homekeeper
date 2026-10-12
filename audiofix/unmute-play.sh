#!/system/bin/sh
# tinyplay 绕过 HAL，HAL 不会替它解 Audio hdmi-out mute，必须手动解除后再测
for i in 1 2 3; do
  tinymix 17 0     # Audio hdmi-out mute -> Off
  tinymix 70 0     # Audio I2S to HDMITX Format -> Stereo PCM
  tinyplay /data/local/tmp/tone20.wav -D 0 -d 0
done
