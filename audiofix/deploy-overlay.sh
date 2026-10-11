#!/system/bin/sh
# 悬浮信息条上线：授予 SYSTEM_ALERT_WINDOW（appop），顺带确认存储权限还在
PKG=com.xiaopaitech.wallpaperlauncher

echo "--- SYSTEM_ALERT_WINDOW ---"
appops set $PKG SYSTEM_ALERT_WINDOW allow
appops get $PKG SYSTEM_ALERT_WINDOW 2>&1

echo "--- MANAGE_EXTERNAL_STORAGE (keep) ---"
appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null
appops get $PKG MANAGE_EXTERNAL_STORAGE 2>&1

echo "--- current overlay config ---"
grep -A8 '"overlay"' /sdcard/homekeeper/config.json 2>/dev/null

echo "--- done ---"
