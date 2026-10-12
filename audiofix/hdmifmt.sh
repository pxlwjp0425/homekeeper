#!/system/bin/sh
echo "=== HDMI related mixers (BEFORE) ==="
tinymix | grep -iE "hdmi|i2s|spdif"
echo "=== EDID audio caps ==="
cat /sys/class/amhdmitx/amhdmitx0/aud_cap 2>/dev/null | head -20
echo "=== SET format=PCM ==="
tinymix "Audio I2S to HDMITX Format" PCM
echo "rc=$?"
echo "=== AFTER ==="
tinymix | grep -iE "I2S to HDMITX|hdmi-out mute"
