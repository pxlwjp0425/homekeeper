#!/system/bin/sh
echo "=== tinymix help ==="
tinymix --help 2>&1 | head -25
echo "=== show control 70 ==="
tinymix 70 2>&1 | head -30
echo "=== try LPCM ==="
tinymix 70 LPCM 2>&1; echo "rc=$?"
echo "=== try PCM (idx) ==="
tinymix 70 0 2>&1; echo "rc=$?"
echo "=== try I2S ==="
tinymix 70 I2S 2>&1; echo "rc=$?"
echo "=== current 70 ==="
tinymix 70 2>&1 | head -5
