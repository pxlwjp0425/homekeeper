#!/system/bin/sh
echo "=== before ==="
wm density
echo "=== clearing override (wm density reset) ==="
wm density reset
echo "--- immediately after reset ---"
wm density
echo "--- waiting 10s ---"
sleep 10
echo "=== after 10s (expect Override 220) ==="
wm density
echo "forced=$(settings get secure display_density_forced)"
echo "=== keep-disp.log tail ==="
tail -n 8 /data/audiofix/keep-disp.log
echo "=== DONE ==="
