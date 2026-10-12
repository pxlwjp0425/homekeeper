#!/system/bin/sh
# 清理 /data/local/tmp 一次性中间产物
# 保留：memclean_run.sh(.boot) —— 内存清理 App 在用；zte_data_bak/ —— 中兴应用数据备份
T=/data/local/tmp
cd "$T" || exit 1

echo "=== BEFORE ==="
du -sh "$T" 2>/dev/null
df -h /data | tail -1

# 大安装包（占 800M+）：线上跑的就是原厂版，均可从设备/网络重新取得
rm -f zte-patched.apk zte-fix2.apk zte-original.apk hm_installed.apk fw-res.apk
# 测试音频
rm -f tone.wav tone20.wav hk-tone.wav
# 截图残留
rm -f ./*.png
# dumpsys / 配置导出等文本
rm -f af.txt u.xml ui.xml sys_fb.xml settings_system.xml sys.xml scan.txt apc.orig.xml roles.xml.bak
# 暂存的 boot 副本
rm -f boot.sh.new
# 一次性脚本（保留 memclean_run.sh）
for f in ./*.sh; do
  [ "$f" = "./memclean_run.sh" ] && continue
  rm -f "$f"
done

echo "=== KEPT ==="
ls -la "$T"
echo "=== AFTER ==="
du -sh "$T" 2>/dev/null
df -h /data | tail -1
