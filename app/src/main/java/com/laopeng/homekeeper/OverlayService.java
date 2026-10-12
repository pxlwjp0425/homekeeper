package com.laopeng.homekeeper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 悬浮信息条：把桌面左上角那串「时间 + 日期/农历/天气」浮在<b>其它 App 之上</b>。
 *
 * <p>外观跟桌面顶栏同源（大时间 + 一行小字），但自成一块半透明圆角卡片：白字 + 投影，
 * 落在任何画面上都读得清。内容随 config.json 里 {@code topbar} 段的
 * {@code showLunar} / {@code showWeather} 走。</p>
 *
 * <p><b>为什么用普通 Service 而不是前台 Service</b>：本 App 是常驻桌面（HOME），进程
 * 基本不会被回收，Service 跟着进程活；这样就省掉了通知渠道和 Android 11 对前台服务类型的
 * 限制。万一进程真被清掉，回到桌面时 MainActivity 会把它重新拉起来。</p>
 *
 * <p><b>权限</b>：{@code SYSTEM_ALERT_WINDOW}（悬浮窗）。盒子没有授权界面，用 adb 授予：<br>
 * {@code appops set com.xiaopaitech.wallpaperlauncher SYSTEM_ALERT_WINDOW allow}</p>
 *
 * <p><b>桌面在前台时自动隐藏</b>：免得跟桌面顶栏叠成两份。由 MainActivity 的
 * onResume / onPause 通过 {@link #setHomeFront(boolean)} 告知。</p>
 *
 * <p>配置见 config.json 的 {@code overlay} 段，改完 15 秒内自动生效（Service 自己热重载）。</p>
 */
public class OverlayService extends Service {

    private static final String TAG = "HKDBG";
    private static final long TICK_MS = 15_000L;

    /** 当前活着的实例（同进程内供 MainActivity 直接调，免得起 binder） */
    private static volatile OverlayService INSTANCE;
    /** 桌面是否在最前。存成静态量，Service 后于桌面启动时也能拿到正确初值 */
    private static volatile boolean HOME_FRONT = false;

    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);

    private WindowManager wm;
    private LinearLayout panel;
    private TextView tvClock;
    private TextView tvDate;
    private GradientDrawable panelBg;
    private WindowManager.LayoutParams lp;

    private Handler ui;
    private ThemeStore cfg;
    private Sky sky;

    private volatile boolean homeFront = false;
    private boolean dead = false;

    // ==================== 生命周期 ====================

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        ui = new Handler(Looper.getMainLooper());
        homeFront = HOME_FRONT;
        cfg = ThemeStore.load(this);
        sky = Sky.load(this);
        try {
            buildPanel();
        } catch (Throwable t) {
            // 多半是没给 SYSTEM_ALERT_WINDOW —— 记一笔然后自己退，别每 15 秒撞一次
            Log.w(TAG, "overlay add failed: " + t);
            dead = true;
        }
        if (dead) {
            stopSelf();
            return;
        }
        Log.i(TAG, "overlay up: margin=" + cfg.ovMarginDp + "dp clock=" + cfg.ovClockSizeSp
                + "sp date=" + cfg.ovDateSizeSp + "sp bg=" + cfg.ovBgAlpha + "%");
        ui.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!dead) {
            ui.post(() -> {
                refreshCfg();
                render();
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        dead = true;
        INSTANCE = null;
        try {
            ui.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        if (wm != null && panel != null) {
            try {
                wm.removeView(panel);
            } catch (Throwable ignored) {
            }
        }
        panel = null;
        super.onDestroy();
    }

    // ==================== 对外静态接口 ====================

    /** 启动（幂等：已在跑就只刷一次） */
    static void start(Context c) {
        try {
            c.startService(new Intent(c, OverlayService.class));
        } catch (Throwable t) {
            Log.w(TAG, "overlay start failed: " + t);
        }
    }

    /** 停止 */
    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, OverlayService.class));
        } catch (Throwable ignored) {
        }
    }

    /** 是否已在运行 */
    static boolean running() {
        return INSTANCE != null;
    }

    /**
     * 悬浮窗能不能画出来：{@code SYSTEM_ALERT_WINDOW} 已被授予
     * （侧载授予方式 {@code appops set <pkg> SYSTEM_ALERT_WINDOW allow}）。
     */
    static boolean canDraw(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(c);
            }
            return c.checkCallingOrSelfPermission("android.permission.SYSTEM_ALERT_WINDOW")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 桌面切前台/后台时调用：前台时把悬浮条藏起来（避免与顶栏重复） */
    static void setHomeFront(boolean front) {
        HOME_FRONT = front;
        final OverlayService s = INSTANCE;
        if (s != null) {
            s.ui.post(() -> {
                s.homeFront = front;
                s.render();
            });
        }
    }

    /** 配置热重载后催一次刷新 */
    static void refresh() {
        final OverlayService s = INSTANCE;
        if (s != null) {
            s.ui.post(() -> {
                s.refreshCfg();
                s.render();
            });
        }
    }

    // ==================== 内部 ====================

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (dead) {
                return;
            }
            try {
                refreshCfg();
                render();
                refreshWeather();
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    private void refreshCfg() {
        try {
            cfg = ThemeStore.load(this);
        } catch (Throwable ignored) {
        }
    }

    /** 天气过期就在后台拉一次，拉完回调 render() */
    private void refreshWeather() {
        final ThemeStore c = cfg;
        final Sky s = sky;
        if (c == null || s == null || !c.showWeather) {
            return;
        }
        if (!s.stale(c.weatherRefreshMin, c.weatherCity)) {
            return;
        }
        s.refreshAsync(this, c.weatherCity, this::render);
    }

    private void buildPanel() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            throw new IllegalStateException("no WindowManager");
        }

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(10));

        tvClock = new TextView(this);
        tvClock.setTextColor(0xFFFFFFFF);
        tvClock.setTypeface(Typeface.DEFAULT_BOLD);
        tvClock.setIncludeFontPadding(false);
        panel.addView(tvClock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        tvDate = new TextView(this);
        tvDate.setTextColor(0xE0FFFFFF);
        tvDate.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        panel.addView(tvDate, dlp);

        panelBg = new GradientDrawable();
        panelBg.setShape(GradientDrawable.RECTANGLE);
        panelBg.setCornerRadius(dp(16));
        panelBg.setColor(0x8C000000);
        panel.setBackground(panelBg);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(24);
        lp.y = dp(24);

        wm.addView(panel, lp);
    }

    private void render() {
        if (dead || panel == null) {
            return;
        }
        final ThemeStore c = cfg;
        if (c == null) {
            return;
        }

        // 时间
        tvClock.setText(clockFmt.format(new Date()));
        tvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.ovClockSizeSp);

        // 日期 + 农历 + 天气（跟桌面顶栏同一套开关）
        StringBuilder sb = new StringBuilder(dateFmt.format(new Date()));
        if (c.showLunar) {
            String lunar = Lunar.today();
            if (!lunar.isEmpty()) {
                sb.append(' ').append(lunar);
            }
        }
        Sky s = sky;
        if (c.showWeather && s != null && s.summary != null && !s.summary.isEmpty()) {
            sb.append(' ').append(s.summary);
        }
        tvDate.setText(sb.toString());
        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.ovDateSizeSp);

        // 投影（浅色画面上也能看清）
        if (c.ovShadow) {
            tvClock.setShadowLayer(6f, 0f, 2f, 0xCC000000);
            tvDate.setShadowLayer(5f, 0f, 2f, 0xCC000000);
        } else {
            tvClock.setShadowLayer(0f, 0f, 0f, 0);
            tvDate.setShadowLayer(0f, 0f, 0f, 0);
        }

        // 底色
        int a = Math.round(255f * Math.max(0, Math.min(100, c.ovBgAlpha)) / 100f);
        panelBg.setColor(a << 24);

        // 位置
        final int m = dp(c.ovMarginDp);
        if (lp.x != m || lp.y != m) {
            lp.x = m;
            lp.y = m;
            try {
                wm.updateViewLayout(panel, lp);
            } catch (Throwable ignored) {
            }
        }

        // 可见性：配置关了、或桌面自己在前台 → 藏起来
        panel.setVisibility((c.ovEnabled && !homeFront) ? View.VISIBLE : View.GONE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
