#!/system/bin/sh
wm density 220
settings put system user_rotation 0
settings put system accelerometer_rotation 0
echo "--- after ---"
wm density
echo -n 'user_rotation='; settings get system user_rotation
echo -n 'accel='; settings get system accelerometer_rotation
echo -n 'display_density_forced='; settings get secure display_density_forced
