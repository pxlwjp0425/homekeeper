#!/system/bin/sh
# 对照实验：撤掉自改的 audio_policy，回到原厂，看整机 HDMI 声音是否恢复
echo "===== revert-test $(date) ====="

# 1) 停格式看护
k=$(cat /data/audiofix/keep-pcm.pid 2>/dev/null)
if [ -n "$k" ]; then kill "$k" 2>/dev/null; echo "killed keep-pcm pid=$k"; fi
rm -f /data/audiofix/keep-pcm.pid

# 2) 暂时禁用开机钩子（防止重启后又挂上）
chmod 000 /data/audiofix/boot.sh && echo "boot.sh disabled"

# 3) 卸掉 bind mount -> 回到 /vendor 原厂 policy
echo "--- before ---"
mount | grep audio_policy_configuration.xml || echo "(no bind mount)"
umount /vendor/etc/audio_policy_configuration.xml
echo "umount rc=$?"
echo "--- after ---"
mount | grep audio_policy_configuration.xml || echo "FACTORY_POLICY_NOW"

# 4) 重启 audioserver 让它按原厂配置重新初始化
setprop ctl.restart audioserver
sleep 6
echo "audioserver=$(getprop init.svc.audioserver)"
echo "===== done ====="
