#!/system/bin/sh
echo "=== install audiofix ==="
mkdir -p /data/audiofix
cp /data/local/tmp/apc-hdmi.xml /data/audiofix/audio_policy_configuration.xml
cp /data/local/tmp/apc.orig.xml /data/audiofix/audio_policy_configuration.orig.xml 2>/dev/null
cp /data/local/tmp/audiofix-boot.sh /data/audiofix/boot.sh
tr -d '\r' < /data/audiofix/boot.sh > /data/audiofix/boot2.sh && mv /data/audiofix/boot2.sh /data/audiofix/boot.sh
chmod 755 /data/audiofix/boot.sh
chmod 644 /data/audiofix/audio_policy_configuration.xml
ls -la /data/audiofix/

echo "=== append boot hook to run_clouddrive.sh ==="
if grep -q 'audiofix-boot-hook' /data/xpclouddrive/run_clouddrive.sh; then
  echo "hook already present"
else
  echo '[ -x /data/audiofix/boot.sh ] && (sh /data/audiofix/boot.sh >/dev/null 2>&1 &) # audiofix-boot-hook' >> /data/xpclouddrive/run_clouddrive.sh
  echo "hook appended"
fi
echo "--- tail run_clouddrive.sh ---"
tail -4 /data/xpclouddrive/run_clouddrive.sh
