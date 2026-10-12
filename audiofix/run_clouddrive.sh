#!/bin/sh
# jdxb autostart hook
[ -x /data/jdxb/boot.sh ] && (sh /data/jdxb/boot.sh >/dev/null 2>&1 &)
# audiofix autostart hook —— 必须放在 nsenter 之前：nsenter 前台阻塞，后面所有行都不会执行
[ -x /data/audiofix/boot.sh ] && (sh /data/audiofix/boot.sh >/dev/null 2>&1 &) # audiofix-boot-hook
#export READ_FILE_TIMEOUT_SEC=10
export DOWNLOAD_FILE_RETRY_TIMES=1
export CLOUDDRIVE_HOME=/data/xpclouddrive/CloudDrive2
export PATH=$PATH:/data/xpclouddrive
export LOCAL_ROOT_PATH=/mnt
export MANUFACTURER_ID=0009
export PARTNER_DEVICE_TYPE=sysprop.persist.sys.hwconfig.seq_id
export DISABLE_AUTO_REGISTER_DEVICE_ON_LOGOUT=true
mkdir -p /mnt/CloudDrive
chown -R system:system /data/xpclouddrive
chown -R system:system /data/xpclouddrive/*
touch /data/xpclouddrive/CloudDrive2/clouddriveconf.json
chmod 777 /data/xpclouddrive/CloudDrive2/clouddriveconf.json
chmod 777 /data/xpclouddrive/CloudDrive2/device_token.txt
#nsenter -t 1 -m -- /data/xpclouddrive/clouddrive  >/dev/null 2>&1
nsenter -t 1  -- /data/xpclouddrive/clouddrive  >/dev/null 2>&1
echo "Waiting for 500 milliseconds..."
sleep 0.5
chmod 777 /data/xpclouddrive/CloudDrive2/device_token.txt


[ -f /data/local/tmp/memclean_run.sh ] && (sleep 120; sh /data/local/tmp/memclean_run.sh >/dev/null 2>&1 &) # memclean-boot-hook
