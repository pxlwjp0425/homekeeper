package com.laopeng.homekeeper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 TV 桌面。
 *
 * 包名沿用固件 HOME 白名单里"已登记未安装"的 com.xiaopaitech.wallpaperlauncher，
 * 因此无论云端策略怎么刷，系统都会放行本 App 作为 HOME —— 但内容不再转发给别人，
 * 而是自己画一个应用网格，避免第三方桌面（当贝）动辄 400MB+ 的图片缓存开销。
 *
 * 两屏（v2.6 起）：
 *   首页 = 自选应用页（5 列 × 2 行，10 个自选应用，纵向平均分布铺满全屏）——开机与按 HOME 都落在这里；
 *   次屏 = 全部应用（7 列，按使用频率）——首页按右键进入，按返回键回到首页。
 *   两屏在左右方向上连成一个环，怎么按都不会卡在边界。
 *
 * 背景（v2.7 起，v4.6 起支持图片壁纸）：
 *   纯色/渐变：5 种常用底色可选（深空黑 / 石墨灰 / 午夜蓝 / 松林绿 / 云母白），
 *   选中云母白这类浅色底时，文字与焦点框自动切成深色，保证可读。
 *   Bing 每日一图：在线取 www.bing.com 的每日壁纸（1920×1080），铺满桌面并自动压暗，
 *   图片存在 App 私有目录、离线可用。入口：顶部「背景」按钮里的「Bing 壁纸」，或长按时钟的隐藏菜单。
 *
 * 排序：默认「按使用频率」——
 *   频率分 = 本桌面点击次数 × 100 + 系统近 30 天前台时长(秒/10)
 * 即优先看"你从桌面点开它的次数"（最贴合习惯），其次看系统记录的实际使用时长。
 * 可在长按时钟的隐藏菜单里切换为「按名称」。
 */
public class MainActivity extends Activity {

    private static final String SELF = "com.xiaopaitech.wallpaperlauncher";
    private static final String LEANBACK = "android.intent.category.LEANBACK_LAUNCHER";

    private static final String PREFS = "hk";
    private static final String K_SORT = "sortMode";
    private static final int SORT_FREQ = 0;
    private static final int SORT_NAME = 1;
    /** Bing 壁纸：本地已选图片的绝对路径（设备本地，故存 prefs 而非配置文件） */
    private static final String K_WP = "wpPath";
    /** （v4.7）自动换图记录：上次「自动更新」那天是本地几号（yyyyMMdd），跨天才会再换 */
    private static final String K_WP_DAY = "wpAutoDay";
    /** （v4.7）自动换图记录：上次应用的那张图的 Bing {@code startdate}，用来判断有没有新图 */
    private static final String K_WP_KEY = "wpAutoKey";
    /** （v4.7）轮播记录：下次换列表里的第几张 */
    private static final String K_WP_ROT = "wpRotateIdx";
    /** （v4.7）池子热度记录：上次「把 Bing 新图补进本地池」那天（yyyyMMdd），每天最多补一次 */
    private static final String K_WP_POOLD = "wpPoolDay";
    /** 一次预热最多补几张（Bing 官方接口翻页上限就是 16） */
    private static final int POOL_WARM = 16;
    /** 自动换图失败/未到时最短重试间隔，免得每 15 秒打一次网 */
    private static final long WP_RETRY_MS = 20 * 60 * 1000L;
    // ---- 背景配色 / 界面参数：全部来自配置文件，主程序不再硬编码 ----
    // 文件位置 /sdcard/homekeeper/config.json，格式见 ThemeStore.DEFAULT_JSON。
    // 启动时读取；onResume 时若文件被外部改过（mtime 变化）会自动重载。
    private static final String K_THEME = "themeIdx";
    /** 当前配色表（文件驱动） */
    private volatile List<Theme> themes = new ArrayList<>();
    /** 当前界面参数（文件驱动） */
    private volatile ThemeStore cfg;
    /** 上次加载配置时文件的修改时间，用于检测外部改动 */
    private long cfgMtime = 0L;
    /** 顶栏天气（含上次结果的缓存，onCreate 时装载） */
    private Sky sky;

    // ---- 两屏：自定义页（首屏，5 列 × 2 行 = 10 槽，纵向均分）/ 全部应用（次屏）----
    private static final int PAGE_ALL = 0;
    private static final int PAGE_FAV = 1;
    /** 开机/按 HOME 默认落在哪一屏：自定义页 */
    private static final int PAGE_HOME = PAGE_FAV;
    /** 10 个槽位的持久化键前缀：c_0 .. c_9 */
    private static final String K_FAV_PREFIX = "c_";
    /** 是否已做过首次自动填充 */
    private static final String K_FAV_INIT = "c_init";

    private static final String TAG = "HKDBG";

    /** 根布局：背景色的作用对象 */
    private View root;
    /** 顶部「背景」按钮：背景色设置入口 */
    private TextView btnBg;
    private GridView grid;
    /** 首页：自选应用页（5 列 × 2 行，纵向均分） */
    private GridView gridFav;
    private AppAdapter adapter;
    private FavAdapter favAdapter;
    private TextView clock;
    private TextView date;

    /**
     * 录屏状态（来自录屏 App 的广播）。
     *
     * <p>MediaProjection 抓的是屏幕合成后的画面 —— 顶栏只要还画着就一定会被录进视频，而系统
     * 没有 API 能单独把某一层挖掉。所以录屏期间把时钟/天气调成<b>全透明</b>：肉眼看没有、录像里也没有，
     * 但布局与焦点链原封不动（用 alpha 而不是 GONE，免得遥控器操作断层）。
     * 页签不动，否则录制中回到桌面就没法翻页了。
     */
    private final BroadcastReceiver recStateRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyRecState(intent != null && intent.getBooleanExtra("on", false));
        }
    };

    private void applyRecState(boolean recording) {
        final float a = recording ? 0f : 1f;
        if (clock != null) clock.setAlpha(a);
        if (date != null) date.setAlpha(a);
    }
    /** 顶部页签指示（纯展示，不可聚焦） */
    private TextView tabAll;
    private TextView tabFav;

    private final List<AppEntry> apps = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean loading = false;
    private volatile boolean resorting = false;
    private volatile String lastSig = "";
    /** 首次焦点是否已成功钉到网格上 */
    private boolean firstFocusDone = false;

    private SharedPreferences prefs;
    /** 当前排序模式：SORT_FREQ（默认）/ SORT_NAME */
    private volatile int sortMode = SORT_FREQ;
    /** 最后打开的 App，用于重排后让选中框跟着它走 */
    private String lastLaunchedPkg;

    /** 当前背景下标（THEME_FROM / THEME_TO / THEME_NAME 索引） */
    private volatile int themeIdx = 0;

    /** 当前页：PAGE_ALL / PAGE_FAV（初始 = 首页 = 自选应用页） */
    private volatile int page = PAGE_HOME;
    /** 自定义页的真源：槽位包名，null = 空槽。长度随配置列数变化，见 {@link #resizeFavs} */
    private volatile String[] favPkgs = new String[FavAdapter.DEFAULT_SLOTS];
    /** 自定义页的渲染数据，size 恒等于当前槽位数，null = 空槽 */
    private final List<AppEntry> favs = new ArrayList<>();
    /** 临时诊断：前 3 个渲染出的图标落盘，用完删掉 */
    private static int DUMP_ICON = 0;

    // ---- 首页「整理顺序」模式（v4.1）----
    /** 是否正在整理首页顺序：此时左右/上下键搬动当前格，OK 放下、返回取消 */
    private volatile boolean arranging = false;
    /** 正在被搬动的槽位下标 */
    private int arrPos = -1;
    /** 进入整理模式时的槽位快照，用于「返回=取消」还原 */
    private String[] arrBackup = null;

    /** 滚轮切屏的去抖器（本机鼠标源会持续吐幽灵滚轮事件，必须滤） */
    private final ScrollPager pager = new ScrollPager();

    // ---- Bing 每日壁纸（v4.6）----
    /** 本地已选的壁纸文件路径（空 = 没有），持久化在 prefs */
    private volatile String wpPath = "";
    /** 已解码的壁纸 Drawable（懒加载） */
    private volatile Drawable wpDrawable;
    /** {@link #wpDrawable} 对应的路径，用来判断缓存是否还有效 */
    private volatile String wpDrawablePath = "";
    /** 上次自动换图成功时「本地今天」的 yyyyMMdd；等于今天就不再重复抓 */
    private volatile String wpAutoDay = "";
    /** 上次应用的图在 Bing 侧的 {@code startdate}；接口给出的还是这个值就说明没出新图 */
    private volatile String wpAutoKey = "";
    /** 轮播：下次该用列表里的第几张 */
    private volatile int wpRotateIdx = 0;
    /** 上次轮播的时刻（不持久化 —— 重启后先换一张，立刻能看到生效） */
    private volatile long wpRotateMs = 0L;
    /** 上次把 Bing 新图补进本地池的那天（yyyyMMdd），每天最多补一次 */
    private volatile String wpPoolDay = "";
    /** 上次尝试自动换图的时刻（节流用） */
    private volatile long wpLastTry = 0L;

    /** 当前自定义页槽位数（由配置列数 × 2 行决定） */
    private int favSlots() {
        FavAdapter fa = favAdapter;
        return fa != null ? fa.getSlots() : FavAdapter.DEFAULT_SLOTS;
    }

    /** 当前自定义页列数（整理模式下上下移动用） */
    private int favSpan() {
        FavAdapter fa = favAdapter;
        return fa != null ? fa.getSpan() : FavAdapter.DEFAULT_SPAN;
    }

    /** 「全部应用」页列数（上下跨屏判边界用） */
    private int allSpan() {
        final ThemeStore c = cfg;
        return c != null && c.allColumns > 0 ? c.allColumns : 7;
    }

    /**
     * 把 favPkgs / favs 调整到 {@code slots} 个槽位：
     * 变长则补 null，变短则截断（截掉的槽位包名会丢失 —— 这是列数变小时无法避免的）。
     */
    private void resizeFavs(int slots) {
        if (slots <= 0) {
            slots = FavAdapter.DEFAULT_SLOTS;
        }
        String[] np = new String[slots];
        for (int i = 0; i < slots; i++) {
            np[i] = i < favPkgs.length ? favPkgs[i] : null;
        }
        favPkgs = np;
        while (favs.size() < slots) {
            favs.add(null);
        }
        while (favs.size() > slots) {
            favs.remove(favs.size() - 1);
        }
    }

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
    /** 只用来跟 Bing 的 {@code startdate} 比日期（yyyyMMdd），故用 ROOT，别受中文区域影响 */
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);

    /** 每秒只刷时钟文本（对齐整秒）；重活仍旧走下面 15 秒的 tick，别混在一起 */
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            try {
                clock.setText(timeFmt.format(new Date()));
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L));
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // 外部改了 config.json → 15 秒内自动重载（不必切走再回来）
            reloadConfigIfChanged();
            clock.setText(timeFmt.format(new Date()));
            // 整理模式下 date 位置被借去显示操作提示，renderTopDate() 自己会跳过
            renderTopDate();
            refreshWeatherIfNeeded();
            autoBingWallpaperIfNeeded();
            rotateBingWallpaperIfNeeded();
            syncOverlay();
            ui.postDelayed(this, 15000L);
        }
    };

    /**
     * 顶栏那行小字：{@code 10月10日 星期六 农历八月十九 晴 21℃}。
     * 农历与天气两段都能在 config.json 的 {@code topbar} 段里单独关掉。
     */
    private void renderTopDate() {
        if (arranging) {
            return;
        }
        try {
            final ThemeStore c = cfg;
            StringBuilder sb = new StringBuilder(dateFmt.format(new Date()));
            if (c == null || c.showLunar) {
                String lunar = Lunar.today();
                if (!lunar.isEmpty()) {
                    sb.append(' ').append(lunar);
                }
            }
            if (c == null || c.showWeather) {
                Sky s = sky;
                if (s != null && !s.summary.isEmpty()) {
                    sb.append(' ').append(s.summary);
                }
            }
            date.setText(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 天气过期就后台拉一次（同一时刻只会有一个请求在跑，见 {@link Sky#refreshAsync}）。
     * 拉完在主线回调 {@link #renderTopDate()}，界面自动补上；失败则安静地保留旧值。
     */
    private void refreshWeatherIfNeeded() {
        final ThemeStore c = cfg;
        if (c != null && !c.showWeather) {
            return;
        }
        final Sky s = sky;
        final String city = c != null ? c.skyPlace() : "";
        if (s == null || !s.stale(c != null ? c.weatherRefreshMin : 30, city)) {
            return;
        }
        s.refreshAsync(this, city, this::renderTopDate);
    }

    /**
     * 悬浮信息条（v4.7）：按 config.json 的 {@code overlay.enabled} 启停。
     *
     * <p>只有「配置开着」且「已授予悬浮窗权限」才启动；否则把已在跑的停掉。
     * 权限侧载授予：{@code appops set com.xiaopaitech.wallpaperlauncher SYSTEM_ALERT_WINDOW allow}</p>
     */
    private void syncOverlay() {
        try {
            final ThemeStore c = cfg;
            final boolean want = c != null && c.ovEnabled && OverlayService.canDraw(this);
            if (want) {
                if (OverlayService.running()) {
                    OverlayService.refresh();
                } else {
                    OverlayService.start(this);
                }
            } else if (OverlayService.running()) {
                OverlayService.stop(this);
            }
        } catch (Throwable ignored) {
        }
    }

    private final BroadcastReceiver pkgWatcher = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadAppsAsync();
        }
    };

    /**
     * 诊断入口（开发用）：adb 里直接调起 Bing 壁纸列表，方便验证标题显示，
     * 不用去电视上长按时钟钻隐藏菜单。
     *
     * <pre>adb shell am broadcast -a com.laopeng.homekeeper.DIAG_BING</pre>
     */
    public static final String ACTION_DIAG_BING = "com.laopeng.homekeeper.DIAG_BING";
    private final BroadcastReceiver diagBing = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(MainActivity.this::showBingPicker);
        }
    };

    /**
     * 本机 ROM 每次重启都会把 display_density_forced 清掉（重启后回到 320），
     * 这里在桌面启动时检查并写回 200。
     * 需要 WRITE_SECURE_SETTINGS（安装后 root 下 `pm grant` 一次即可）。
     */
    private void ensureDensity() {
        new Thread(() -> {
            try {
                // 本机 ROM 重启会清掉 display_density_forced，回到物理 320。
                // 桌面启动时写回 220（已实测 WMS 不读 settings_secure.xml，只能靠 App 启动兜底）。
                int cur = android.provider.Settings.Secure.getInt(
                        getContentResolver(), "display_density_forced", 0);
                if (cur != 220) {
                    android.provider.Settings.Secure.putInt(
                            getContentResolver(), "display_density_forced", 220);
                    String r = AdbShell.exec("wm density 220");
                    android.util.Log.i("HKDENSITY", cur + " -> 220, adb=" + (r == null ? "null" : r.trim()));
                }
            } catch (Throwable t) {
                android.util.Log.w("HKDENSITY", "failed: " + t);
            }
        }, "density-fix").start();
    }

    /**
     * 配置目录/文件权限自检：adb 兜底保存靠 shell 身份写，
     * 文件必须是 666、目录必须是 777，否则 shell 会被 EACCES 挡住。
     * 用 root cp 或电脑端保存后权限常被改回 644，所以每次启动顺手修一下。
     */
    private void ensureCfgPerm() {
        new Thread(() -> {
            try {
                java.io.File f = ThemeStore.file(this);
                if (!f.exists()) {
                    return;
                }
                AdbShell.exec("chmod 666 " + f.getAbsolutePath());
                java.io.File d = f.getParentFile();
                if (d != null) {
                    AdbShell.exec("chmod 777 " + d.getAbsolutePath());
                }
            } catch (Throwable ignored) {
            }
        }, "cfg-perm").start();
    }

    /**
     * 确保自己是默认桌面。重装 APK 会把 HOME 角色重置回官方桌面，
     * 这里用 RoleManager 弹一次系统确认框，用户选「始终」后就固定下来。
     */
    private void ensureHomeRole() {
        try {
            // 先看系统的默认 HOME 到底是不是自己：用 resolveActivity 判断，
            // 比 RoleManager.isRoleHeld 准（有些 ROM 用 set-home-activity 设了但 role 状态不认）。
            android.content.Intent home = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager()
                    .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            String defPkg = (ri != null && ri.activityInfo != null)
                    ? ri.activityInfo.packageName : "";
            if (getPackageName().equals(defPkg)) {
                return;                       // 已经是默认桌面，什么都不做
            }
            if (android.os.Build.VERSION.SDK_INT < 29) {
                return;
            }
            android.app.role.RoleManager rm =
                    (android.app.role.RoleManager) getSystemService(android.content.Context.ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                startActivityForResult(
                        rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 1001);
            }
        } catch (Throwable t) {
            android.util.Log.w("HKROLE", "role request skipped: " + t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ensureDensity();
        ensureCfgPerm();
        getWindow().getDecorView().post(this::ensureHomeRole);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            sortMode = prefs.getInt(K_SORT, SORT_FREQ);
        } catch (Throwable ignored) {
        }
        try {
            String p = prefs.getString(K_WP, "");
            if (p != null) {
                wpPath = p;
            }
            String d = prefs.getString(K_WP_DAY, "");
            if (d != null) {
                wpAutoDay = d;
            }
            String k = prefs.getString(K_WP_KEY, "");
            if (k != null) {
                wpAutoKey = k;
            }
            wpRotateIdx = prefs.getInt(K_WP_ROT, 0);
            String pd = prefs.getString(K_WP_POOLD, "");
            if (pd != null) {
                wpPoolDay = pd;
            }
        } catch (Throwable ignored) {
        }
        // 先把上次缓存的天气装进来，顶栏立刻就有东西，不用等联网
        sky = Sky.load(this);

        root = findViewById(R.id.root);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        grid = findViewById(R.id.grid);
        gridFav = findViewById(R.id.grid_fav);
        tabAll = findViewById(R.id.tab_all);
        tabFav = findViewById(R.id.tab_fav);
        btnBg = findViewById(R.id.btn_bg);
        btnBg.setOnClickListener(v -> showBgPicker());

        // 首页 = 自选应用页（XML 里已把 grid 设为 gone，这里再钉一次，防止两处不一致）
        page = PAGE_HOME;
        applyPage();

        adapter = new AppAdapter(this, apps);
        grid.setAdapter(adapter);

        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < apps.size()) {
                launch(apps.get(position));
            }
        });

        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < apps.size()) {
                showMenu(apps.get(position));
            }
            return true;
        });

        // ---- 首屏：自定义页 ----
        // 先用默认槽位建好结构；真正的列数/槽位数在下面的 reloadConfig() 里按配置文件修正。
        resizeFavs(FavAdapter.DEFAULT_SLOTS);
        loadFavPkgs();
        favAdapter = new FavAdapter(this, favs);
        gridFav.setAdapter(favAdapter);
        // 首屏尺寸确定后再刷一次，让 FavAdapter 的"平均分布"高度计算生效
        // （首次 getView 时 gridFav.getHeight() 可能还是 0）
        gridFav.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot && b - t > 0) {
                favAdapter.notifyDataSetChanged();
            }
        });
        gridFav.setOnItemClickListener((parent, view, position, id) -> {
            if (arranging) {
                exitArrange(true);
                return;
            }
            AppEntry e = position < favs.size() ? favs.get(position) : null;
            if (e == null) {
                showPicker(position);
            } else {
                launch(e);
            }
        });
        gridFav.setOnItemLongClickListener((parent, view, position, id) -> {
            if (arranging) {
                return true;
            }
            AppEntry e = position < favs.size() ? favs.get(position) : null;
            if (e == null) {
                showPicker(position);
            } else {
                showFavMenu(position, e);
            }
            return true;
        });
        updateTabs();

        // 读取配置文件（/sdcard/homekeeper/config.json），并据此设置
        // 页签文案 / 列数 / 间距 / 字号 / 图标尺寸 / 当前背景。
        // 必须放在两个 adapter 都建好之后，否则 adapter 拿不到新的文字色。
        reloadConfig();

        // 顶栏：日期 + 农历 + 天气（天气过期才真正发请求）
        renderTopDate();
        refreshWeatherIfNeeded();

        // 左右到底跨行的实现在 dispatchKeyEvent()：
        // 踩坑 —— GridView 的 OnKeyListener 在本 ROM 上不会被回调，只能在 Activity 层拦。
        restoreFocus();

        // 长按时钟 = 隐藏设置入口
        clock.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("桌面设置")
                    .setItems(new String[]{
                            "刷新应用列表",
                            (sortMode == SORT_FREQ ? "排序：使用频率  ✓" : "排序：使用频率"),
                            (sortMode == SORT_NAME ? "排序：应用名称  ✓" : "排序：应用名称"),
                            "调整首页顺序",
                            "背景色 / 渐变",
                            (isBingMode() ? "Bing 每日壁纸  ✓" : "Bing 每日壁纸（在线）"),
                            "配色管理（图形化）",
                            "编辑配置文件",
                            (page == PAGE_FAV ? "前往「全部应用」页" : "前往「首页」"),
                            "切回当贝桌面"
                    }, (d, w) -> {
                        if (w == 0) {
                            loadAppsAsync();
                        } else if (w == 1) {
                            setSortMode(SORT_FREQ);
                        } else if (w == 2) {
                            setSortMode(SORT_NAME);
                        } else if (w == 3) {
                            enterArrangeFromMenu();
                        } else if (w == 4) {
                            showBgPicker();
                        } else if (w == 5) {
                            showBingPicker();
                        } else if (w == 6) {
                            showThemeManager();
                        } else if (w == 7) {
                            showConfigEditor();
                        } else if (w == 8) {
                            switchPage(page == PAGE_FAV ? PAGE_ALL : PAGE_FAV, 0);
                        } else {
                            Target.launch(MainActivity.this);
                        }
                    })
                    .show();
            return true;
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgWatcher, f);
        try {
            registerReceiver(diagBing, new IntentFilter(ACTION_DIAG_BING));
        } catch (Throwable ignored) {
        }

        loadAppsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
        // 时钟显示到秒，需要每秒刷一次（只 setText，代价很小）
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        // 配置文件可能被外部改过（电脑/文件管理器），变化了就重载
        reloadConfigIfChanged();
        // 从别的 App 回桌面时顺手看下天气要不要更新
        refreshWeatherIfNeeded();
        // 悬浮信息条：桌面回到前台就自动隐藏（免得跟顶栏叠成两份）
        OverlayService.setHomeFront(true);
        syncOverlay();
        // 录屏状态：录制中把顶栏时钟/天气也收起来（广播来自录屏 App）
        try {
            IntentFilter rf = new IntentFilter(RecStateReceiver.ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                // 广播是别的 App 发的，必须 EXPORTED 才收得到
                registerReceiver(recStateRx, rf, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(recStateRx, rf);
            }
        } catch (Throwable ignored) {
        }
        applyRecState(OverlayService.isRecording());
        if (apps.isEmpty()) {
            loadAppsAsync();
        } else {
            // 从别的 App 回到桌面：刚打开过的 App 计数变了，重算一次顺序
            resortAsync();
        }
    }

    /** 回到前台/刷新后把焦点还给网格，避免"选中框消失" */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            restoreFocus();
        }
    }

    private void restoreFocus() {
        final GridView g = activeGrid();
        g.post(() -> {
            try {
                final int sel = g.getSelectedItemPosition();
                if (g.getCount() > 0 && sel == GridView.INVALID_POSITION) {
                    g.setSelection(0);
                }
                if (g.hasFocus()) {
                    firstFocusDone = true;
                    return;
                }
                // 首次进入必须把焦点钉在网格上：否则系统会把初始焦点给顶部时钟，
                // 表现为「桌面启动后没有任何 App 有选中框」。
                if (!clock.hasFocus() || !firstFocusDone) {
                    if (g.requestFocus()) {
                        firstFocusDone = true;
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(clockTick);
        // 离开桌面（切到别的 App）：让悬浮信息条显出来，它就是给别的 App 看的
        OverlayService.setHomeFront(false);
        try { unregisterReceiver(recStateRx); } catch (Throwable ignored) {}
        // 离开桌面（回屏保 / 切到别的窗口）就把整理模式收掉，避免状态残留
        if (arranging) {
            exitArrange(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        try {
            unregisterReceiver(pkgWatcher);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(diagBing);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 本体已在前台时又收到 HOME 意图（launchMode=singleTask → 走这里，不是 onCreate）。
     * 让「按 HOME」回到首屏（自定义页），这是遥控器用户的心理预期。
     * 已经在首屏就只把焦点还给网格，不强行挪动选中框。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        android.util.Log.d(TAG, "onNewIntent: page=" + (page == PAGE_FAV ? "FAV" : "ALL")
                + " action=" + (intent == null ? "null" : intent.getAction()));
        if (arranging) {
            exitArrange(true);
        }
        if (page != PAGE_HOME) {
            switchPage(PAGE_HOME, 0);
        } else {
            final GridView g = activeGrid();
            if (g.getCount() > 0) {
                g.requestFocus();
            }
        }
    }

    /** 桌面不响应返回键 */
    @Override
    public void onBackPressed() {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 在 Activity 层拦截方向键：
     * <ul>
     *   <li>左右：走到本屏边界则跨屏（两屏成环）</li>
     *   <li>上下：走到本屏<b>最上/最下一行</b>则跨屏 —— 首页 ↓ 去「全部应用」，
     *       全部应用 ↑ 回「首页」；没到边界就原样交给 GridView 在屏内移动</li>
     * </ul>
     * Activity 一定先于视图树收到按键，比挂在 GridView 上的 OnKeyListener 可靠。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            final int code = event.getKeyCode();
            // 整理模式下，方向键用来搬动当前格，这里必须全部拦下，
            // 否则会被下面「左右跨屏」的逻辑抢走、把页面切来切去。
            if (arranging) {
                return handleArrangeKey(code);
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (moveHorizontally(code)) {
                    return true;
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (moveVertically(code)) {
                    return true;
                }
            }
            // 次屏（全部应用）按返回 = 回首屏（自定义）；首屏本身依旧完全不响应返回键
            if (code == KeyEvent.KEYCODE_BACK && page != PAGE_HOME) {
                switchPage(PAGE_HOME, -1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 鼠标滚轮切屏：向下滚 → 「全部应用」，向上滚 → 「首页」。
     * 原始事件先过 {@link ScrollPager} 滤波 —— 本机鼠标源即使在没人碰时也会持续吐 ±1 的幽灵事件，
     * 不滤的话桌面会自己来回翻。
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL) {
            final float v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (!arranging) {
                final int act = pager.feed(v, ev.getEventTime());
                if (act > 0) {
                    if (page != PAGE_ALL) {
                        switchPage(PAGE_ALL, 0);
                    }
                    return true;
                }
                if (act < 0) {
                    if (page != PAGE_FAV) {
                        switchPage(PAGE_FAV, -1);
                    }
                    return true;
                }
            }
            // 抖动 / 未达阈值：一律吞掉，别让它再去滚网格
            return v != 0f;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // ==================== 两屏切换 / 首屏：自定义页 ====================

    /** 当前页面所属的网格 */
    private GridView activeGrid() {
        return page == PAGE_FAV ? gridFav : grid;
    }

    /**
     * 把「当前页」这个状态同步到视图：两个网格的可见性 + 页签高亮。
     * 幂等、可反复调 —— onCreate / 状态恢复 / 切页 都走它，
     * 避免出现「page 字段说在自定义页、可见的却是全部应用」这种不一致。
     */
    private void applyPage() {
        final boolean fav = (page == PAGE_FAV);
        grid.setVisibility(fav ? View.GONE : View.VISIBLE);
        gridFav.setVisibility(fav ? View.VISIBLE : View.GONE);
        updateTabs();
    }

    /** 切换页面；sel >= 0 时把选中框移到该格子 */
    private void switchPage(final int target, final int sel) {
        android.util.Log.d(TAG, "switchPage: " + (page == PAGE_FAV ? "FAV" : "ALL")
                + " -> " + (target == PAGE_FAV ? "FAV" : "ALL") + " sel=" + sel);
        if (target == page) {
            if (sel >= 0 && activeGrid().getCount() > 0) {
                activeGrid().setSelection(sel);
            }
            return;
        }
        page = target;
        applyPage();
        firstFocusDone = true;
        final GridView to = activeGrid();
        to.post(() -> {
            if (sel >= 0 && to.getCount() > 0) {
                to.setSelection(sel);
            }
            to.requestFocus();
        });
    }

    /**
     * 进程被系统回收后重建（install -r / 内存紧张 / 重启）时会被调用，
     * 且晚于 onCreate —— 框架会把上次的视图状态（含可见性、选中格）盖回来。
     * 这里强制把状态拨回首屏（自定义页）+ 第一格，否则会重现
     * 「page 说自定义、可见的是全部应用」和「开机焦点落在最后一格」两个问题。
     */
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        page = PAGE_HOME;
        applyPage();
        final GridView g = activeGrid();
        g.post(() -> {
            try {
                if (g.getCount() > 0) {
                    g.setSelection(0);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 顶部页签高亮：当前页亮、另一页暗（页签顺序与页面顺序一致：自定义 在左） */
    private void updateTabs() {
        final boolean all = (page == PAGE_ALL);
        tabAll.setAlpha(all ? 1f : 0.45f);
        tabFav.setAlpha(all ? 0.45f : 1f);
    }

    /** 从 SharedPreferences 读 10 个槽位 */
    private void loadFavPkgs() {
        try {
            for (int i = 0; i < favSlots(); i++) {
                String p = prefs.getString(K_FAV_PREFIX + i, null);
                favPkgs[i] = (p == null || p.isEmpty()) ? null : p;
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveFavPkgs() {
        try {
            SharedPreferences.Editor ed = prefs.edit();
            for (int i = 0; i < favSlots(); i++) {
                ed.putString(K_FAV_PREFIX + i, favPkgs[i] == null ? "" : favPkgs[i]);
            }
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 首次运行时自动填充"最常用的 10 个"（应用按当前排序，已排除其它桌面） */
    private void autoFillFavsIfNeeded() {
        try {
            if (prefs.getBoolean(K_FAV_INIT, false)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        int k = 0;
        for (AppEntry e : apps) {
            if (k >= favSlots()) {
                break;
            }
            if (e.isLauncher) {
                continue;
            }
            favPkgs[k++] = e.pkg;
        }
        saveFavPkgs();
        try {
            prefs.edit().putBoolean(K_FAV_INIT, true).apply();
        } catch (Throwable ignored) {
        }
        android.util.Log.d(TAG, "fav: auto-filled " + k + " slots");
    }

    /** 把 favPkgs（包名）解析成 favs（带图标的条目）并刷新自定义页 */
    private void syncFavs() {
        if (favAdapter == null) {
            return;
        }
        autoFillFavsIfNeeded();
        Map<String, AppEntry> idx = new HashMap<>();
        for (AppEntry e : apps) {
            idx.put(e.pkg, e);
        }
        for (int i = 0; i < favSlots(); i++) {
            AppEntry e = favPkgs[i] == null ? null : idx.get(favPkgs[i]);
            favs.set(i, e);
        }
        favAdapter.notifyDataSetChanged();
        android.util.Log.d(TAG, "fav: " + dumpFavs());
    }

    private String dumpFavs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < favSlots(); i++) {
            sb.append(i).append(':').append(favPkgs[i] == null ? "-" : favPkgs[i]).append(' ');
        }
        return sb.toString();
    }

    /**
     * 把某槽位设为指定应用。
     * position 传 -1 表示"自动找第一个空槽"，返回实际落位（-1 = 已满）。
     */
    private int placeFav(int position, String pkg) {
        if (position < 0) {
            for (int i = 0; i < favSlots(); i++) {
                if (favPkgs[i] == null) {
                    position = i;
                    break;
                }
            }
            if (position < 0) {
                return -1;
            }
        }
        favPkgs[position] = pkg;
        saveFavPkgs();
        syncFavs();
        return position;
    }

    /** 把一个应用加入首页第一个空位 */
    private void addToFav(AppEntry e) {
        for (int i = 0; i < favSlots(); i++) {
            if (e.pkg.equals(favPkgs[i])) {
                toast("已在首页第 " + (i + 1) + " 位");
                return;
            }
        }
        int slot = placeFav(-1, e.pkg);
        if (slot < 0) {
            toast("首页已满，请先移除一个");
        } else {
            toast("已加入首页第 " + (slot + 1) + " 位");
        }
    }

    /** 应用选择器：为指定槽位挑一个应用 */
    private void showPicker(final int slot) {
        final List<AppEntry> pool = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        names.add("—— 清空此位 ——");
        for (AppEntry e : apps) {
            if (e.isLauncher) {
                continue;
            }
            pool.add(e);
            names.add(e.label);
        }
        if (pool.isEmpty()) {
            toast("没有可添加的应用");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择应用 · 第 " + (slot + 1) + " 位")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        placeFav(slot, null);
                    } else {
                        placeFav(slot, pool.get(w - 1).pkg);
                    }
                })
                .show();
    }

    /** 长按首页上已有应用 */
    private void showFavMenu(final int slot, AppEntry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(new String[]{"打开", "调整顺序", "更换", "移除", "应用信息"}, (d, w) -> {
                    if (w == 0) {
                        launch(e);
                    } else if (w == 1) {
                        enterArrange(slot);
                    } else if (w == 2) {
                        showPicker(slot);
                    } else if (w == 3) {
                        placeFav(slot, null);
                    } else {
                        showInfo(e);
                    }
                })
                .show();
    }

    // ==================== 首页手动排序（整理模式） ====================

    /**
     * 从时钟长按菜单进入整理模式：优先从"当前选中格"开始，
     * 选中的是空槽就找第一个有应用的槽位。
     */
    private void enterArrangeFromMenu() {
        if (page != PAGE_FAV) {
            switchPage(PAGE_FAV, -1);
        }
        int slot = gridFav.getSelectedItemPosition();
        if (slot < 0 || slot >= favSlots() || favPkgs[slot] == null) {
            slot = -1;
            for (int i = 0; i < favSlots(); i++) {
                if (favPkgs[i] != null) {
                    slot = i;
                    break;
                }
            }
        }
        if (slot < 0) {
            toast("首页还没有应用，先加一个再来排序");
            return;
        }
        enterArrange(slot);
    }

    /**
     * 进入「整理顺序」模式：拿起 {@code slot} 这一格。
     * 左右键左右挪、上下键上下挪（跨行），OK 放下、返回取消（还原进入时的顺序）。
     * 每次移动都即时落盘，所以即使中途来电/切走，已挪的部分也不会丢。
     */
    private void enterArrange(final int slot) {
        int s = slot;
        final int slots = favSlots();
        if (s < 0 || s >= slots) {
            s = 0;
        }
        if (favPkgs[s] == null) {
            toast("这一位是空的，先放个应用再排序");
            return;
        }
        if (page != PAGE_FAV) {
            switchPage(PAGE_FAV, s);
        }
        arranging = true;
        arrPos = s;
        arrBackup = favPkgs.clone();
        if (favAdapter != null) {
            favAdapter.setArrangePos(s);
        }
        updateArrangeHint();
        final int p = s;
        gridFav.post(() -> {
            try {
                gridFav.setSelection(p);
                gridFav.requestFocus();
            } catch (Throwable ignored) {
            }
        });
        toast("已进入整理：方向键移动，「OK」放下，「返回」取消");
    }

    /** 整理模式下的按键处理（返回 true = 已消费，不再往下传） */
    private boolean handleArrangeKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveArranged(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveArranged(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveArranged(-favSpan());
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveArranged(favSpan());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                exitArrange(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                exitArrange(false);
                return true;
            case KeyEvent.KEYCODE_HOME:
                // HOME 键交回系统（会走 onNewIntent，在那里收尾）
                return false;
            default:
                // 整理模式下吞掉其它按键，防止误触切页/启动
                return true;
        }
    }

    /** 把当前格与相邻格对调；越界则提示 */
    private void moveArranged(int delta) {
        final int slots = favSlots();
        if (arrPos < 0 || arrPos >= slots) {
            return;
        }
        final int to = arrPos + delta;
        if (to < 0 || to >= slots) {
            toast(delta == 0 ? "" : (delta < 0 ? "已经在最前面了" : "已经在最后面了"));
            return;
        }
        final String tmp = favPkgs[arrPos];
        favPkgs[arrPos] = favPkgs[to];
        favPkgs[to] = tmp;
        arrPos = to;
        saveFavPkgs();
        syncFavs();
        if (favAdapter != null) {
            favAdapter.setArrangePos(arrPos);
        }
        final int p = arrPos;
        gridFav.post(() -> {
            try {
                gridFav.setSelection(p);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 退出整理模式；keep=false 时还原进入时的顺序 */
    private void exitArrange(boolean keep) {
        if (!arranging) {
            return;
        }
        arranging = false;
        if (!keep && arrBackup != null) {
            if (arrBackup.length == favPkgs.length) {
                favPkgs = arrBackup.clone();
            }
            saveFavPkgs();
            syncFavs();
            toast("已取消，顺序还原");
        } else {
            toast("顺序已保存");
        }
        arrBackup = null;
        arrPos = -1;
        if (favAdapter != null) {
            favAdapter.setArrangePos(-1);
        }
        // 整理结束，把 date 从提示文案换回「日期 + 农历 + 天气」
        renderTopDate();
        restoreFocus();
    }

    /** 整理模式的提示文案借 date 的位置显示（顶栏空闲，且不遮挡图标） */
    private void updateArrangeHint() {
        try {
            date.setText("整理首页顺序：◀ ▶ 移动 · OK 放下 · 返回 取消");
        } catch (Throwable ignored) {
        }
    }

    /** 焦点是否在网格上（网格自身或其子项） */
    private boolean focusInGrid() {
        final GridView g = activeGrid();
        android.view.View f = getCurrentFocus();
        if (f == null) {
            return false;
        }
        if (f == g) {
            return true;
        }
        android.view.ViewParent p = f.getParent();
        while (p != null) {
            if (p == g) {
                return true;
            }
            if (!(p instanceof android.view.View)) {
                break;
            }
            p = ((android.view.View) p).getParent();
        }
        return false;
    }

    /**
     * 左右移动一格；走到本屏边界则跨屏。
     * 两屏连成一个环（自定义页在环上与全部应用首尾相接）：
     *   自定义末格 --右--> 全部应用首格；全部应用首格 --左--> 自定义末格；反过来同理。
     * 无论哪一屏是首屏，这个环都是对称的，不需随 PAGE_HOME 改。
     */
    private boolean moveHorizontally(int keyCode) {
        if (!focusInGrid()) {
            return false;
        }
        final GridView g = activeGrid();
        final boolean main = (g == grid);
        final int count = main ? adapter.getCount() : favSlots();
        if (count <= 0) {
            // 全部应用还没扫出来：至少允许从首屏（自定义）跳到全部应用页
            if (!main) {
                switchPage(PAGE_ALL, -1);
                return true;
            }
            return false;
        }
        int pos = g.getSelectedItemPosition();
        if (pos < 0 || pos >= count) {
            pos = 0;
        }
        final boolean right = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
        final int target = right ? pos + 1 : pos - 1;
        if (target >= count || target < 0) {
            if (main) {
                switchPage(PAGE_FAV, right ? 0 : favSlots() - 1);
            } else {
                switchPage(PAGE_ALL, right ? 0 : Math.max(0, adapter.getCount() - 1));
            }
            return true;
        }
        g.setSelection(target);
        return true;
    }

    /**
     * 上下移动一行；走到本屏最上/最下一行则跨屏。
     *   首页（末行） --下--> 全部应用（首格）；全部应用（首行） --上--> 首页。
     * 未到边界时返回 false，让 GridView 自己处理屏内移动（不抢按键）。
     */
    private boolean moveVertically(int keyCode) {
        if (!focusInGrid()) {
            return false;
        }
        final GridView g = activeGrid();
        final boolean fav = (g == gridFav);
        final int span = fav ? favSpan() : allSpan();
        final int count = fav ? favSlots() : (adapter == null ? 0 : adapter.getCount());
        final boolean down = (keyCode == KeyEvent.KEYCODE_DPAD_DOWN);
        // 首屏为空时也允许「下」去全部应用，否则用户会被困在空首页
        if (count <= 0) {
            if (fav && down) {
                switchPage(PAGE_ALL, 0);
                return true;
            }
            return false;
        }
        if (span <= 0) {
            return false;
        }
        int pos = g.getSelectedItemPosition();
        if (pos < 0 || pos >= count) {
            pos = 0;
        }
        final int target = down ? pos + span : pos - span;
        if (target >= count || target < 0) {
            // 已到本屏的上/下边界 —— 跨屏
            if (down) {
                if (fav) {
                    switchPage(PAGE_ALL, 0);
                    return true;
                }
                return false; // 全部应用已是最下屏，没地方去了
            }
            if (!fav) {
                switchPage(PAGE_FAV, -1);
                return true;
            }
            return false; // 首页第一行上方无内容
        }
        return false; // 屏内还有行 —— 交给 GridView
    }

    // ============================ 排序 ============================

    /** 切换排序方式并立即重排 */
    private void setSortMode(int mode) {
        if (sortMode == mode) {
            return;
        }
        sortMode = mode;
        try {
            prefs.edit().putInt(K_SORT, mode).apply();
        } catch (Throwable ignored) {
        }
        lastSig = "";
        if (apps.isEmpty()) {
            loadAppsAsync();
        } else {
            resortAsync();
        }
    }

    /**
     * 某个 App 的"使用次数"：本地点击次数与系统启动次数取较大者。
     * 本地计数是系统计数的一个子集，取 max 可以避免重复累加，
     * 又能在系统统计不可用时（没授权）退化成本地计数。
     */
    private static long launchesOf(AppEntry e) {
        return Math.max(e.launchCount, e.sysLaunchCount);
    }

    /** 按当前 sortMode 排序 */
    private void sortList(List<AppEntry> list) {
        final boolean byName = (sortMode == SORT_NAME);
        Collections.sort(list, (a, b) -> {
            // 别的桌面（Launcher）一律排到最后：
            // 它们会被 HOME 键反复拉起，计数虚高，且"桌面里排桌面"没有意义
            if (a.isLauncher != b.isLauncher) {
                return a.isLauncher ? 1 : -1;
            }
            if (!byName) {
                // 1) 使用次数最多者优先
                long la = launchesOf(a);
                long lb = launchesOf(b);
                if (la != lb) {
                    return lb > la ? 1 : -1;
                }
                // 2) 次数相同，用得久者优先
                if (a.usageMs != b.usageMs) {
                    return b.usageMs > a.usageMs ? 1 : -1;
                }
                // 3) 再相同，最近用过的优先
                if (a.lastUsed != b.lastUsed) {
                    return b.lastUsed > a.lastUsed ? 1 : -1;
                }
            }
            int c = a.label.compareToIgnoreCase(b.label);
            return c != 0 ? c : a.pkg.compareTo(b.pkg);
        });
    }

    /** 采集排序依据：本地点击计数 + 系统使用统计（无权限时后者为 0，静默降级） */
    private void applyFrequency(List<AppEntry> list, long now) {
        // 1) 本桌面点击次数（不需要任何权限，最贴合"从这个桌面打开"的习惯）
        try {
            SharedPreferences sp = prefs;
            if (sp != null) {
                for (AppEntry e : list) {
                    e.launchCount = sp.getInt("n_" + e.pkg, 0);
                    long t = sp.getLong("t_" + e.pkg, 0L);
                    if (t > e.lastUsed) {
                        e.lastUsed = t;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) 系统使用统计（需 GET_USAGE_STATS appop，未授权时返回空表）
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) getSystemService("usagestats");
            if (usm == null) {
                return;
            }
            List<UsageStats> us = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_MONTHLY, now - 30L * 86400000L, now);
            if (us == null || us.isEmpty()) {
                android.util.Log.d(TAG, "usageStats: EMPTY (无权限或无数据)");
                return;
            }
            Map<String, AppEntry> idx = new HashMap<>();
            for (AppEntry e : list) {
                idx.put(e.pkg, e);
            }
            int hit = 0;
            for (UsageStats u : us) {
                if (u == null) {
                    continue;
                }
                AppEntry e = idx.get(u.getPackageName());
                if (e == null) {
                    continue;
                }
                long fg = u.getTotalTimeInForeground();
                if (fg > 0) {
                    e.usageMs = Math.max(e.usageMs, fg);
                }
                long lu = u.getLastTimeUsed();
                if (lu > e.lastUsed) {
                    e.lastUsed = lu;
                }
                hit++;
            }
            android.util.Log.d(TAG, "usageStats: entries=" + us.size() + " matched=" + hit);
            countResumes(idx, now);
        } catch (Throwable t) {
            android.util.Log.d(TAG, "usageStats: ERROR " + t);
        }
    }

    /**
     * 用公开的 {@link UsageEvents} 事件流统计各 App 的前台启动次数。
     * （UsageStats.getAppLaunchCount() 是系统 API，普通 App 编译不过，
     *  但事件流是公开的，数 ACTIVITY_RESUMED 效果一样。）
     * 无权限时事件流为空，静默降级为「仅本地点击计数」。
     */
    private void countResumes(Map<String, AppEntry> idx, long now) {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService("usagestats");
            if (usm == null) {
                return;
            }
            UsageEvents ev = usm.queryEvents(now - 30L * 86400000L, now);
            if (ev == null) {
                return;
            }
            final int resumed = UsageEvents.Event.MOVE_TO_FOREGROUND;
            UsageEvents.Event one = new UsageEvents.Event();
            Map<String, Integer> cnt = new HashMap<>();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(one);
                if (one.getEventType() != resumed) {
                    continue;
                }
                String p = one.getPackageName();
                if (p == null) {
                    continue;
                }
                Integer c = cnt.get(p);
                cnt.put(p, c == null ? 1 : c + 1);
            }
            int applied = 0;
            for (Map.Entry<String, Integer> en : cnt.entrySet()) {
                AppEntry a = idx.get(en.getKey());
                if (a != null && en.getValue() > a.sysLaunchCount) {
                    a.sysLaunchCount = en.getValue();
                    applied++;
                }
            }
            android.util.Log.d(TAG, "events: pkgs=" + cnt.size() + " applied=" + applied);
        } catch (Throwable t) {
            android.util.Log.d(TAG, "events: ERROR " + t);
        }
    }

    /** 首次排在前 n 名的应用（用于日志核对排序效果） */
    private static String topOf(List<AppEntry> list, int n) {
        StringBuilder sb = new StringBuilder();
        int k = Math.min(n, list.size());
        for (int i = 0; i < k; i++) {
            AppEntry e = list.get(i);
            sb.append(i + 1).append('.').append(e.label)
                    .append("[n=").append(launchesOf(e))
                    .append(",t=").append(e.usageMs / 1000).append("s] ");
        }
        return sb.toString();
    }

    // ============================ 列表 ============================

    private void loadAppsAsync() {
        if (loading) {
            return;
        }
        loading = true;
        final float density = getResources().getDisplayMetrics().density;
        new Thread(() -> {
            final long now = System.currentTimeMillis();
            final List<AppEntry> list = queryApps(density);
            applyFrequency(list, now);
            sortList(list);
            final String sig = signatureOf(list);
            android.util.Log.d(TAG, "load: n=" + list.size() + " mode=" + sortMode
                    + " top: " + topOf(list, 8));
            ui.post(() -> {
                loading = false;
                // 列表内容/顺序没变就别刷新，避免无谓的重建导致焦点跳动
                if (sig.equals(lastSig) && apps.size() == list.size()) {
                    syncFavs();
                    restoreFocus();
                    return;
                }
                lastSig = sig;
                apps.clear();
                apps.addAll(list);
                adapter.notifyDataSetChanged();
                syncFavs();
                int idx = indexOf(lastLaunchedPkg);
                if (idx < 0) {
                    idx = 0;
                }
                if (idx < apps.size()) {
                    grid.setSelection(idx);
                }
                restoreFocus();
            });
        }, "app-scan").start();
    }

    /** 只重算排序、不重载图标（从别的 App 回到桌面时用，开销小得多） */
    private void resortAsync() {
        if (resorting || loading) {
            return;
        }
        resorting = true;
        final List<AppEntry> snapshot = new ArrayList<>(apps);
        new Thread(() -> {
            final long now = System.currentTimeMillis();
            applyFrequency(snapshot, now);
            sortList(snapshot);
            final String sig = signatureOf(snapshot);
            ui.post(() -> {
                resorting = false;
                if (sig.equals(lastSig)) {
                    restoreFocus();
                    return;
                }
                lastSig = sig;
                apps.clear();
                apps.addAll(snapshot);
                adapter.notifyDataSetChanged();
                // 让选中框跟随"刚打开过的那个 App"
                int idx = indexOf(lastLaunchedPkg);
                if (idx < 0) {
                    idx = grid.getSelectedItemPosition();
                }
                if (idx < 0 || idx >= apps.size()) {
                    idx = 0;
                }
                grid.setSelection(idx);
                restoreFocus();
                android.util.Log.d(TAG, "resort: top " + topOf(snapshot, 6));
            });
        }, "resort").start();
    }

    private int indexOf(String pkg) {
        if (pkg == null) {
            return -1;
        }
        for (int i = 0; i < apps.size(); i++) {
            if (pkg.equals(apps.get(i).pkg)) {
                return i;
            }
        }
        return -1;
    }

    /** 记一次"从本桌面打开"，用于频率排序 */
    private void bumpLaunch(String pkg) {
        try {
            int n = prefs.getInt("n_" + pkg, 0) + 1;
            prefs.edit()
                    .putInt("n_" + pkg, n)
                    .putLong("t_" + pkg, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 应用列表指纹（顺序敏感），用于判断是否真的需要重刷 */
    private String signatureOf(List<AppEntry> list) {
        StringBuilder sb = new StringBuilder(list.size() * 32);
        for (AppEntry e : list) {
            sb.append(e.pkg).append('|').append(e.label).append('\n');
        }
        return sb.toString();
    }

    private List<AppEntry> queryApps(float density) {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> map = new LinkedHashMap<>();
        // 图标渲染分辨率：首页格子放得很大（约 230px@1080p），给足余量避免放大模糊。
        // 96dp×3.2 = 307px，比实际显示尺寸略大，缩放时是"缩小"而非"放大"→ 更清晰。
        int iconSize = Math.max(192, (int) (96 * density));

        collect(pm, Intent.CATEGORY_LAUNCHER, false, map, iconSize);
        collect(pm, LEANBACK, true, map, iconSize);
        markLaunchers(pm, map);

        return new ArrayList<>(map.values());
    }

    /** 标记"别的桌面"，排序时置底 */
    private void markLaunchers(PackageManager pm, Map<String, AppEntry> map) {
        // 1) 正规方式：查询响应 HOME 的应用
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> ris = pm.queryIntentActivities(it, 0);
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri == null || ri.activityInfo == null) {
                        continue;
                    }
                    AppEntry e = map.get(ri.activityInfo.packageName);
                    if (e != null) {
                        e.isLauncher = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) 兜底：本机 PMS 的 filterHomeApp 会把非白名单桌面从上面的列表里剔除
        //    （当贝桌面就被剔掉了），所以再按包名特征补一次判断。
        for (AppEntry e : map.values()) {
            if (e.isLauncher || e.pkg == null) {
                continue;
            }
            if (e.pkg.toLowerCase(Locale.ROOT).contains("launcher")) {
                e.isLauncher = true;
            }
        }
    }

    private void collect(PackageManager pm, String category, boolean leanback,
                         Map<String, AppEntry> map, int iconSize) {
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(category);
            List<ResolveInfo> ris = pm.queryIntentActivities(it, 0);
            if (ris == null) {
                return;
            }
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || SELF.equals(pkg) || map.containsKey(pkg)) {
                    continue;
                }
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.cls = ri.activityInfo.name;
                CharSequence lb = ri.loadLabel(pm);
                e.label = lb == null ? pkg : lb.toString().trim();
                if (e.label.isEmpty()) {
                    e.label = pkg;
                }
                e.leanback = leanback;
                try {
                    e.icon = renderIcon(ri.loadIcon(pm), iconSize);
                } catch (Throwable ignored) {
                }
                map.put(pkg, e);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把图标统一成「一致的圆角卡片」再交给 ImageView 缩放显示。
     *
     * 各家 App 图标形状/大小/圆角都不一样（直角方、圆角方、白底、彩底混着），
     * 原样贴出来整屏很乱。这里统一做三件事：
     * 1) **统一占比**：图标等比缩放到「格子边长 × sizeRatio」的内框，四周留白一致；
     *    且**等比缩放居中**——不能直接 setBounds(0,0,size,size)，
     *    那样会把非正方形图标（如「115生活」的竖长图）拉变形；
     * 2) **统一圆角**：内框按 cornerRatio 裁圆角，直角图标也会被修成圆角；
     * 3) **统一描边**：沿圆角边描一圈半透明细线，弱图标在深底上也能看出边界。
     * 数值全部来自 config.json 的 icon 段（见 {@link ThemeStore#DEFAULT_JSON}）。
     * 输出尺寸给足（iconSize 由调用方按 96dp 计算），避免后续被放大而糊。
     */
    private Bitmap renderIcon(Drawable d, int size) {
        if (d == null) {
            return null;
        }
        try {
            ThemeStore c = cfg;
            float sizeRatio = c == null ? 0.88f : c.iconSizeRatio;
            float cornerRatio = c == null ? 0.3f : c.iconCornerRatio;
            float strokeDp = c == null ? 1.5f : c.iconStrokeDp;
            int strokeColor = c == null ? 0x33FFFFFF : c.iconStrokeColor;
            android.util.Log.i("HKICON", "size=" + size + " sizeRatio=" + sizeRatio
                    + " corner=" + cornerRatio + " stroke=" + strokeDp
                    + " cfgNull=" + (c == null) + " file=" + ThemeStore.pathText(this));

            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();

            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);

            // 内框：图标真正占的区域（居中）
            int inner = Math.max(1, Math.round(size * sizeRatio));
            int pad = (size - inner) / 2;
            float radius = inner * cornerRatio;

            if (w <= 0 || h <= 0) {
                w = inner;
                h = inner;
            }

            // 1) 先按原始尺寸渲染（封顶 512，够用又省内存），便于按 alpha 裁边
            final int cap = 512;
            float pre = Math.min(1f, Math.min((float) cap / w, (float) cap / h));
            int rw = Math.max(1, Math.round(w * pre));
            int rh = Math.max(1, Math.round(h * pre));
            Bitmap src = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(src);
            d.setBounds(0, 0, rw, rh);
            d.draw(sc);

            // 2) 裁掉四周全透明边：自适应图标（AdaptiveIconDrawable）天然带 1/6 的
            //    透明留白，不裁掉它看起来就比老式满幅图标小一圈，整屏尺寸不齐。
            int bx = 0, by = 0, bw = rw, bh = rh;
            int[] bb = alphaBounds(src);
            if (bb != null) {
                bx = bb[0];
                by = bb[1];
                bw = Math.max(1, bb[2] - bb[0]);
                bh = Math.max(1, bb[3] - bb[1]);
            }
            Bitmap content = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas cc = new Canvas(content);
            cc.drawBitmap(src, -bx, -by, null);
            src.recycle();

            // 3) 等比缩放居中 -> 内框
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setDither(true);
            Bitmap raw = Bitmap.createBitmap(inner, inner, Bitmap.Config.ARGB_8888);
            Canvas rc = new Canvas(raw);
            float scale = Math.min((float) inner / bw, (float) inner / bh);
            int dw = Math.max(1, Math.round(bw * scale));
            int dh = Math.max(1, Math.round(bh * scale));
            float dl = (inner - dw) / 2f;
            float dt = (inner - dh) / 2f;
            rc.drawBitmap(content, null, new RectF(dl, dt, dl + dw, dt + dh), paint);
            content.recycle();

            // 4) 圆角遮罩（直角图标 -> 圆角；本来圆角的也不受影响）
            if (radius > 0.5f) {
                Bitmap masked = Bitmap.createBitmap(inner, inner, Bitmap.Config.ARGB_8888);
                Canvas mc = new Canvas(masked);
                Path clip = new Path();
                clip.addRoundRect(new RectF(0, 0, inner, inner), radius, radius,
                        Path.Direction.CW);
                mc.clipPath(clip);
                mc.drawBitmap(raw, 0, 0, paint);
                raw.recycle();
                raw = masked;
            }
            canvas.drawBitmap(raw, pad, pad, paint);
            raw.recycle();

            if (DUMP_ICON < 3) {
                DUMP_ICON++;
                try {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(
                            "/sdcard/hkicon_" + DUMP_ICON + ".png");
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    android.util.Log.i("HKICON", "dumped /sdcard/hkicon_" + DUMP_ICON + ".png "
                            + bmp.getWidth() + "x" + bmp.getHeight() + " inner=" + inner
                            + " pad=" + pad + " radius=" + radius);
                } catch (Throwable ignored) {
                }
            }

            // 3) 统一描边
            if (strokeDp > 0.01f) {
                float sw = strokeDp * getResources().getDisplayMetrics().density;
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeWidth(sw);
                sp.setColor(strokeColor);
                float off = sw / 2f;
                canvas.drawRoundRect(new RectF(pad + off, pad + off,
                        pad + inner - off, pad + inner - off), radius, radius, sp);
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 找位图中「非透明像素」的外接矩形，返回 {x, y, right, bottom}；整幅全透明返回 null。
     * 采样步长 2px —— 图标边缘都是抗锯齿的，不需要逐像素精确，快 4 倍。
     */
    private static int[] alphaBounds(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        final int step = 2;
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y += step) {
            int row = y * w;
            for (int x = 0; x < w; x += step) {
                if ((px[row + x] >>> 24) > 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0 || minX > maxX || minY > maxY) {
            return null;
        }
        // 采样是跳着来的，边界往外补一点，别贴着内容裁
        minX = Math.max(0, minX - step);
        minY = Math.max(0, minY - step);
        maxX = Math.min(w - 1, maxX + step);
        maxY = Math.min(h - 1, maxY + step);
        return new int[]{minX, minY, maxX + 1, maxY + 1};
    }

    private void launch(AppEntry e) {
        lastLaunchedPkg = e.pkg;
        bumpLaunch(e.pkg);
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(e.pkg, e.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Throwable t) {
            toast("无法打开 " + e.label);
        }
    }

    private void showMenu(AppEntry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(new String[]{"打开", "添加到首页", "应用信息"}, (d, w) -> {
                    if (w == 0) {
                        launch(e);
                    } else if (w == 1) {
                        addToFav(e);
                    } else {
                        showInfo(e);
                    }
                })
                .show();
    }

    private void showInfo(AppEntry e) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("无法打开应用信息");
        }
    }

    // ==================== 背景色 / 配置文件 ====================

    /**
     * 从配置文件（+ 内置默认兜底）重载全部界面参数，并立即生效。
     * 由 onCreate / onResume（检测到文件变化）/ 保存对话框 调用。
     */
    private void reloadConfig() {
        // 配置变化可能连带改变槽位数，整理模式下的快照会随之失效 —— 先收尾
        if (arranging) {
            exitArrange(true);
        }
        final ThemeStore s = ThemeStore.load(this);
        cfg = s;
        themes = new ArrayList<>(s.themes);
        try {
            cfgMtime = ThemeStore.file(this).lastModified();
        } catch (Throwable ignored) {
        }

        // ---- 页签文案 ----
        try {
            tabFav.setText(s.tabHome);
            tabAll.setText(s.tabAll);
            btnBg.setText(s.tabBg);
        } catch (Throwable ignored) {
        }

        // ---- 网格列数 / 间距 ----
        final float den = getResources().getDisplayMetrics().density;
        try {
            gridFav.setNumColumns(s.favColumns);
            grid.setNumColumns(s.allColumns);
            gridFav.setVerticalSpacing(Math.round(s.favRowSpacingDp * den));
            grid.setVerticalSpacing(Math.round(s.allRowSpacingDp * den));
            gridFav.setHorizontalSpacing(Math.round(s.favColSpacingDp * den));
            grid.setHorizontalSpacing(Math.round(s.allColSpacingDp * den));
        } catch (Throwable ignored) {
        }

        // ---- 字号 ----
        try {
            clock.setTextSize(s.clockSizeSp);
            date.setTextSize(s.dateSizeSp);
            tabFav.setTextSize(s.tabSizeSp);
            tabAll.setTextSize(s.tabSizeSp);
            btnBg.setTextSize(s.tabSizeSp);
        } catch (Throwable ignored) {
        }

        // ---- 把网格/图标参数传给 adapter（列数变化会连带改变槽位数）----
        try {
            if (favAdapter != null) {
                boolean slotsChanged = favAdapter.applyConfig(s.favColumns, s.iconSizeDp,
                        s.iconPadBottomDp, s.nameSizeSp, s.namePadTopDp);
                favAdapter.setCornerRatio(s.iconCornerRatio);
                if (slotsChanged) {
                    // 列数变了 → 槽位数变了 → 数据数组跟着调整，并重新读取已保存的包名
                    resizeFavs(favAdapter.getSlots());
                    loadFavPkgs();
                    syncFavs();
                }
            }
        } catch (Throwable ignored) {
        }

        // ---- 当前背景 ----
        int theme = 0;
        try {
            theme = prefs.getInt(K_THEME, 0);
        } catch (Throwable ignored) {
        }
        themeIdx = theme;
        applyTheme(theme);

        if (s.lastError != null) {
            toast(s.lastError);
        }

        // 配置说要用 Bing 壁纸但本地还没图 → 后台补一张（首次改配置文件时的体验）
        ensureBingWallpaperIfNeeded();
    }

    /**
     * 应用第 idx 种背景。
     *
     * <p>两种模式：</p>
     * <ul>
     *   <li>{@code color} —— themes 里的纯色/渐变；底色偏亮时正文切成深色，否则不可读；</li>
     *   <li>{@code bing} —— 本地已选的 Bing 每日一图 + 暗遮罩；照片亮度不可控，
     *       一律用浅色文字并压暗背景，才保证时钟/图标名可读。</li>
     * </ul>
     */
    private void applyTheme(int idx) {
        final List<Theme> list = themes;
        if (list == null || list.isEmpty()) {
            return;
        }
        if (idx < 0 || idx >= list.size()) {
            idx = 0;
        }
        themeIdx = idx;
        final Theme th = list.get(idx);
        // 语义：top = 顶部（深端），bottom = 底部（浅端）；纯色时两者相同
        final int bottom = th.bottom;
        final int top = th.top;
        // 图片壁纸是否真的可用（模式对 + 本地有图）
        final boolean photo = isBingMode() && bingDrawable() != null;
        // 文字色按「底/顶两端的中点亮度」判定，保证渐变两端都可读；图片底恒用浅色
        final int mid = averageColor(bottom, top);
        final boolean light = !photo && isLightColor(mid);
        final int textC = light ? 0xFF1B1F24 : 0xFFE9EEF7;
        // 浅色渐变（如暖沙/薄荷雾）顶部其实是深的，副标题常压在深色区上，
        // 所以浅色底的次要文字不能用浅灰，要用更深的灰才两头都可读。
        // 图片底则相反：本来就有暗遮罩，次要文字要用更亮的灰才看得清。
        final int dimC = photo ? 0xFFD7E0EC : (light ? 0xFF3F4654 : 0xFF8C97A8);
        // 「背景」按钮是可点击入口，浅色底上用一个比 dimC 更深的灰，保证对比度
        final int btnC = photo ? 0xFFD7E0EC : (light ? 0xFF2A313D : 0xFF8C97A8);
        try {
            if (root != null) {
                applyRootBackground(bottom, top, photo);
            }
            if (clock != null) {
                clock.setTextColor(textC);
            }
            if (date != null) {
                date.setTextColor(dimC);
            }
            if (btnBg != null) {
                btnBg.setTextColor(btnC);
            }
            if (tabFav != null) {
                tabFav.setTextColor(textC);
            }
            if (tabAll != null) {
                tabAll.setTextColor(textC);
            }
            if (adapter != null) {
                adapter.setTextColor(textC);
            }
            if (favAdapter != null) {
                favAdapter.setColors(textC, dimC);
            }
            // 图片壁纸：给文字加投影，压在照片的高光区上也立得住
            setShadow(clock, photo);
            setShadow(date, photo);
            setShadow(tabFav, photo);
            setShadow(tabAll, photo);
            setShadow(btnBg, photo);
            if (adapter != null) {
                adapter.setTextShadow(photo);
            }
            if (favAdapter != null) {
                favAdapter.setTextShadow(photo);
            }
        } catch (Throwable ignored) {
        }
        try {
            prefs.edit().putInt(K_THEME, idx).apply();
        } catch (Throwable ignored) {
        }
    }

    // ==================== BinG 每日壁纸（v4.6）====================

    /** 配置文件里的壁纸模式是否为 bing */
    private boolean isBingMode() {
        final ThemeStore c = cfg;
        return c != null && "bing".equalsIgnoreCase(c.wpMode == null ? "" : c.wpMode.trim());
    }

    /** 当前壁纸文件对应的 Drawable（带缓存；路径变了或解码失败返回 null） */
    private Drawable bingDrawable() {
        final String p = wpPath;
        if (p == null || p.isEmpty()) {
            return null;
        }
        if (wpDrawable != null && p.equals(wpDrawablePath)) {
            return wpDrawable;
        }
        try {
            final File f = new File(p);
            if (!f.exists() || f.length() < 20000) {
                return null;
            }
            // 只解到 1920 宽：1080p 电视够用，UHD 原图解码后 33MB 盒子扛不住
            final Bitmap b = Bing.decode(f, 1920);
            if (b == null) {
                return null;
            }
            final Drawable d = new Bing.CropDrawable(b);
            wpDrawable = d;
            wpDrawablePath = p;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把背景铺到根布局。
     *
     * <p>图片壁纸用 {@link LayerDrawable}（图 + 遮罩）叠出来 —— 比把位图逐像素压暗省事且不改像素。
     * 遮罩用<b>纵向渐变</b>而非纯色：顶栏（时钟/日期/页签）与底部图标名最容易压在高亮画面上，
     * 所以上下两头压得更重、中间保留原图，既保可读又不糊掉照片。</p>
     */
    private void applyRootBackground(int bottom, int top, boolean photo) {
        if (root == null) {
            return;
        }
        if (photo) {
            final Drawable img = bingDrawable();
            if (img != null) {
                final int dim = clampInt(cfg != null ? cfg.wpDim : 35, 0, 80);
                final android.graphics.drawable.GradientDrawable scrim =
                        new android.graphics.drawable.GradientDrawable();
                scrim.setOrientation(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
                scrim.setColors(new int[]{
                        argbOf(dim + 28),   // 顶部：再压暗一档，托住顶栏文字
                        argbOf(dim),        // 中部：保留照片
                        argbOf(dim + 16)}); // 底部：图标名所在，也压一点
                root.setBackground(new LayerDrawable(new Drawable[]{img, scrim}));
                return;
            }
        }
        root.setBackground(makeBackground(bottom, top));
    }

    /** dim(0-80) → 不透明黑（0xAARRGGBB） */
    private static int argbOf(int dim) {
        final int a = Math.max(0, Math.min(255, Math.round(255f * dim / 100f)));
        return Color.argb(a, 0, 0, 0);
    }

    /** 图片壁纸时给文字加投影（radius>0 开，否则清掉） */
    private static void setShadow(TextView t, boolean on) {
        if (t == null) {
            return;
        }
        if (on) {
            t.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        } else {
            t.setShadowLayer(0f, 0f, 0f, 0);
        }
    }

    /**
     * 弹列表让用户挑一张 Bing 壁纸。
     *
     * <p>列的是<b>本地池里的全部图</b>（累积下来的每一张都在），每张尽量带上中文标题
     * （接口只给最近 16 天，更早的从本地标题库 {@code titles.json} 取）。本地还空着时才现拉一次接口。</p>
     */
    private void showBingPicker() {
        toast("正在获取 Bing 壁纸列表…");
        final String mkt = cfg != null ? cfg.bingMkt : "zh-CN";
        final boolean uhd = cfg != null && cfg.bingUhd;
        final int keep = cfg != null ? cfg.wpRotatePool : 0;
        new Thread(() -> {
            try {
                // 日期 → 标题。只信 Bing 接口给的（最近 16 天，准确）。
                // 本地 titles.json 是按日期从第三方抓的，实测整体错位（2026-10-18 接口说
                // "水上通衢/威尼斯"，本地给的是"多巴达恩城堡"），拿它覆盖只会张冠李戴，
                // 所以不再调用 loadLocalTitles；查不到标题的条目在列表里只显示日期。
                //
                // 为了不浪费接口每次给的准确标题，抓到就并进本地 verified 库逐日累积，
                // 以后这些日期即使超出接口 16 天窗口也还能显示正确的名字。
                final java.util.Map<String, String> titles = new java.util.HashMap<>();
                loadVerifiedTitles(titles);
                final java.util.Map<String, String> fresh = new java.util.HashMap<>();
                try {
                    for (Bing.Item it : Bing.list(mkt, POOL_WARM)) {
                        fresh.put(it.date, it.label());
                    }
                } catch (Throwable ignored) {
                }
                titles.putAll(fresh);
                mergeVerifiedTitles(fresh);
                List<File> pool = Bing.cached(this);
                if (pool.isEmpty()) {
                    for (Bing.Item it : Bing.list(mkt, POOL_WARM)) {
                        Bing.download(this, it, uhd, keep);
                    }
                    pool = Bing.cached(this);
                }
                final List<File> files = pool;
                runOnUiThread(() -> {
                    if (files.isEmpty()) {
                        toast("没取到壁纸，检查网络后重试");
                        return;
                    }
                    showBingList(files, titles);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toast("获取失败：" + shortErr(t)));
            }
        }, "bing-list").start();
    }

    /** 壁纸选择列表：随机 / 本地池全部 / 回到纯色 */
    private void showBingList(final List<File> files, final java.util.Map<String, String> titles) {
        // 说明：这里必须列出【全部】本地图（每天可能不止一张，都是不同的照片）。
        // 标题只信 Bing 接口给的（准确）；本地 titles.json 是按日期从第三方抓的，整体有错位
        // （实测 2026-10-18 接口说"水上通衢/威尼斯"，它给的是"多巴达恩城堡"），
        // 所以不再拿它覆盖，查不到标题就只显示日期，绝不张冠李戴。
        final String[] names = new String[files.size() + 2];
        names[0] = "🎲 随机来一张";


        int titled = 0;
        int fromName = 0;
        for (int i = 0; i < files.size(); i++) {
            final File f = files.get(i);
            final String d = dateOf(f);
            // 标题优先级：
            //   ① 文件名自带的（YYYYMMDD_标题.jpg，外部导入的整理库，最可靠，直接能读）
            //   ② Bing 官方接口给的（只有最近 16 天，但日期与图对得上）
            // 同一天的第二、三张（bing_YYYYMMDD_01/02）没有独立标题，
            // 用了就会张冠李戴，所以只显示日期。
            final String fnTitle = Bing.titleOfName(f.getName());
            final String t;
            if (!fnTitle.isEmpty()) {
                t = fullDate(d) + "  " + clip(fnTitle, 22);
                fromName++;
            } else {
                t = isVariantName(f.getName()) ? null : titles.get(d);
            }
            names[i + 1] = (t != null && !t.isEmpty()) ? t : fullDate(d);
            if (t != null && !t.isEmpty()) {
                titled++;
            }
        }
        names[names.length - 1] = "↩ 回到纯色 / 渐变背景";
        // 诊断：把标题覆盖率与前 10 条打出来（验证本地标题库是否生效）
        try {
            final StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= Math.min(10, files.size()); i++) {
                sb.append(names[i]).append(" | ");
            }
            Log.i("HKDBG", "bing-list: pool=" + files.size() + " titled=" + titled
                    + " fromName=" + fromName + " head=" + sb);
        } catch (Throwable ignored) {
        }

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Bing 每日壁纸（本地 " + files.size() + " 张，选一张即换）")
                .setItems(names, (d, w) -> {
                    if (w == 0) {
                        applyBingFile(files.get((int) (Math.random() * files.size())));
                    } else if (w == names.length - 1) {
                        setWallpaperMode("color");
                        toast("已回到纯色 / 渐变背景");
                    } else {
                        applyBingFile(files.get(w - 1));
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        // 焦点直接钉在列表上，遥控器不用先绕按钮
        dlg.setOnShowListener(d -> {
            try {
                dlg.getListView().requestFocus();
            } catch (Throwable ignored) {
            }
        });
        dlg.show();
    }

    /** 直接把本地已有的图切上去（手选列表用，不用再下载）；成功才把模式切成 bing */
    private void applyBingFile(final File f) {
        wpPath = f.getAbsolutePath();
        wpDrawable = null;
        wpDrawablePath = "";
        // 手动选的这张 = 今天已经定过壁纸了，当天别再被自动换图覆盖
        final String today = dayFmt.format(new Date());
        wpAutoDay = today;
        wpAutoKey = dateOf(f);
        try {
            prefs.edit().putString(K_WP, wpPath)
                    .putString(K_WP_DAY, today)
                    .putString(K_WP_KEY, wpAutoKey).apply();
        } catch (Throwable ignored) {
        }
        setWallpaperMode("bing");
        toast("已换成 Bing 壁纸：" + prettyDate(wpAutoKey));
        if (cfg != null && cfg.wpApplySystem) {
            trySetSystemWallpaper(f);
        }
    }

    /** {@code bing_20210201_01.jpg} 这类同日多图（带 _NN 后缀）返回 true；主图 {@code bing_20210201.jpg} 返回 false */
    private static boolean isVariantName(String name) {
        if (name == null) {
            return false;
        }
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final int us = base.lastIndexOf('_');
        if (us < 0 || base.length() - us - 1 != 2) {
            return false;
        }
        return Character.isDigit(base.charAt(us + 1)) && Character.isDigit(base.charAt(us + 2));
    }

    /** {@code 20261001} → {@code 2026-10-01}（列表用，带年份；图池跨 2010~现在） */
    private static String fullDate(String yyyymmdd) {
        if (yyyymmdd != null && yyyymmdd.length() == 8) {
            return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6)
                    + "-" + yyyymmdd.substring(6, 8);
        }
        return yyyymmdd == null ? "" : yyyymmdd;
    }

    /** {@code 20261001} → {@code 10-01}；格式不对就原样返回 */
    private static String prettyDate(String yyyymmdd) {
        if (yyyymmdd != null && yyyymmdd.length() == 8) {
            return yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
        }
        return yyyymmdd == null ? "" : yyyymmdd;
    }

    /**
     * 载入本地标题库 {@code <files>/bing/titles.json}（{@code yyyyMMdd -> 中文标题}）。
     *
     * <p>标题来自用户电脑上收集的历史 Bing 壁纸（文件名带中文名），已按<b>图片内容</b>匹配好；
     * 用来给 Bing 接口覆盖不到的更早的图补上名字（接口最多只返回最近 16 天）。
     * 拼成与 {@link Bing.Item#label()} 一致的 {@code "MM-DD  标题"} 写进 {@code out}；
     * <b>会覆盖已有键</b>（内容匹配比接口的按日匹配更可靠）。</p>
     */
    /** 读本地"已验证标题"库：只存 Bing 接口确认过的（date → "10-19  慢节奏生活"） */
    private void loadVerifiedTitles(java.util.Map<String, String> out) {
        try {
            final File f = new File(Bing.dir(this), "titles_verified.json");
            if (!f.exists() || f.length() <= 2) {
                return;
            }
            final org.json.JSONObject o = new org.json.JSONObject(readTextFile(f));
            final java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                final String d = keys.next();
                final String t = o.optString(d, "");
                if (!t.isEmpty()) {
                    out.put(d, t);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把接口刚拿到的标题并进本地库（只增不改，已有条目不动） */
    private void mergeVerifiedTitles(java.util.Map<String, String> fresh) {
        if (fresh == null || fresh.isEmpty()) {
            return;
        }
        try {
            final File f = new File(Bing.dir(this), "titles_verified.json");
            org.json.JSONObject o = new org.json.JSONObject();
            if (f.exists() && f.length() > 2) {
                try {
                    o = new org.json.JSONObject(readTextFile(f));
                } catch (Throwable ignored) {
                }
            }
            boolean changed = false;
            for (java.util.Map.Entry<String, String> e : fresh.entrySet()) {
                final String v = e.getValue();
                if (v != null && !v.isEmpty() && !o.has(e.getKey())) {
                    o.put(e.getKey(), v);
                    changed = true;
                }
            }
            if (changed) {
                writeTextFile(f, o.toString());
                Log.i("HKDBG", "verified titles -> " + o.length());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readTextFile(File f) throws Exception {
        final java.io.InputStream in = new java.io.FileInputStream(f);
        final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static void writeTextFile(File f, String text) throws Exception {
        final java.io.OutputStream out = new java.io.FileOutputStream(f);
        out.write(text.getBytes("UTF-8"));
        out.close();
    }

    private void loadLocalTitles(java.util.Map<String, String> out) {
        try {
            final File f = new File(Bing.dir(this), "titles.json");
            if (!f.exists() || f.length() <= 2) {
                return;
            }
            final java.io.InputStream in = new java.io.FileInputStream(f);
            final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            final org.json.JSONObject o =
                    new org.json.JSONObject(new String(bo.toByteArray(), "UTF-8"));
            final java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                final String d = keys.next();
                final String t = o.optString(d, "");
                if (!t.isEmpty()) {
                    out.put(d, prettyDate(d) + "  " + clip(t, 26));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 过长标题截断（列表一行放不下） */
    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** 改 config.json 里的 {@code wallpaper.mode} 并立即重绘（写盘失败只提示、不影响当前画面） */
    private void setWallpaperMode(String mode) {
        try {
            final ThemeStore c = cfg;
            if (c != null) {
                c.wpMode = mode;
                if (!c.save(this, c.toJson())) {
                    toast("配置写入失败：" + c.lastError);
                }
            }
        } catch (Throwable ignored) {
        }
        reloadConfig();
    }

    /**
     * 配置写着要用 Bing 壁纸、但本地还没图（比如第一次改配置文件）→ 后台抓今日那张。
     * 抓的过程中先用纯色撑着，成功了再自动切过去；失败静默（下次重载会再试）。
     */
    private void ensureBingWallpaperIfNeeded() {
        if (!isBingMode() || bingDrawable() != null) {
            return;
        }
        new Thread(() -> {
            try {
                final List<Bing.Item> items =
                        Bing.list(cfg != null ? cfg.bingMkt : "zh-CN", 1);
                if (items.isEmpty()) {
                    return;
                }
                final Bing.Item it = items.get(0);
                final File f = Bing.download(this, it, cfg != null && cfg.bingUhd,
                        cfg != null ? cfg.wpRotatePool : 0);
                final String today = dayFmt.format(new Date());
                runOnUiThread(() -> {
                    wpPath = f.getAbsolutePath();
                    wpDrawable = null;
                    wpDrawablePath = "";
                    // 视为「今天已经定过」→ 当天不再自动换，下次跨天才换新图
                    wpAutoDay = today;
                    wpAutoKey = it.date == null ? "" : it.date;
                    try {
                        prefs.edit().putString(K_WP, wpPath)
                                .putString(K_WP_DAY, wpAutoDay)
                                .putString(K_WP_KEY, wpAutoKey).apply();
                    } catch (Throwable ignored) {
                    }
                    applyTheme(themeIdx);
                });
            } catch (Throwable ignored) {
            }
        }, "bing-init").start();
    }

    /**
     * （v4.7）Bing 壁纸每天自动换成当天新图。
     *
     * <p><b>为什么不能拿 startdate 和本地日期比</b>：Bing 接口的 {@code startdate} 是按
     * <b>UTC</b> 记的 —— 北京时间 10-11 这天的图，接口回的是 {@code 20261010}
     * （{@code fullstartdate=202610101600}，即 UTC 10-10 16:00 = 北京 10-11 00:00 新上的）。
     * 所以「startdate == 本地今天」永远不成立。正确判据是拿 startdate 跟
     * <b>上次应用过的那张</b>（{@link #wpAutoKey}）比：跨天后 idx=0 变了就是出了新图。
     * {@link #wpAutoDay} 只负责「每天最多动手一次」的节流。</p>
     *
     * <p>手动选过图的话当天也记为「已定」，不会刚选完就被自动顶掉，次日恢复正常自动换。</p>
     */
    private void autoBingWallpaperIfNeeded() {
        final ThemeStore c = cfg;
        if (c == null || !isBingMode() || !c.wpAutoDaily) {
            return;
        }
        if (c.wpRotateMin > 0) {
            // 开了轮播就交给 rotateBingWallpaperIfNeeded()：两套节流同时跑只会互相打架
            return;
        }
        final String today = dayFmt.format(new Date());
        if (today.equals(wpAutoDay)) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - wpLastTry < WP_RETRY_MS) {
            return;
        }
        wpLastTry = now;
        final String mkt = c.bingMkt;
        final boolean uhd = c.bingUhd;
        final boolean sys = c.wpApplySystem;
        final int keep = c.wpRotatePool;
        new Thread(() -> {
            try {
                final List<Bing.Item> items = Bing.list(mkt, 1);
                if (items.isEmpty()) {
                    return;
                }
                final Bing.Item it = items.get(0);
                // 跟上次那张一样 → 还没出新图，先不落地，过一会儿再试
                if (it.date == null || it.date.equals(wpAutoKey)) {
                    return;
                }
                final File f = Bing.download(this, it, uhd, keep);
                runOnUiThread(() -> {
                    wpPath = f.getAbsolutePath();
                    wpDrawable = null;
                    wpDrawablePath = "";
                    wpAutoDay = today;
                    wpAutoKey = it.date;
                    try {
                        prefs.edit().putString(K_WP, wpPath)
                                .putString(K_WP_DAY, wpAutoDay)
                                .putString(K_WP_KEY, wpAutoKey).apply();
                    } catch (Throwable ignored) {
                    }
                    applyTheme(themeIdx);
                    if (sys) {
                        trySetSystemWallpaper(f);
                    }
                });
            } catch (Throwable ignored) {
            }
        }, "bing-daily").start();
    }

    /**
     * （v4.7）按 {@code wallpaper.rotateMinutes} 定时轮换 Bing 图（{@code 0} = 关）。
     *
     * <p><b>池子是本地的、能一直长大</b>：Bing 官方接口一次只给 8 张、翻页最多 16 张，
     * 所以"想放更多"不能靠接口，只能靠累积 —— 每次轮播顺手把接口里本地还没有的图下下来，
     * 每天新增一张，池子就随着时间越滚越大。{@code wallpaper.rotatePool} 是<b>上限</b>，
     * {@code 0} = 不限制（有多少用多少，只受磁盘约束）。</p>
     *
     * <p>开关刚打开 / 重启后会<b>先换一张</b>（不干等一个周期），首次还会把最近
     * {@link #POOL_WARM} 天一次性补进池子，不至于开局只有一张。</p>
     *
     * <p>与 {@link #autoBingWallpaperIfNeeded()} 互斥：轮播本身每天在补新图，不需要再叠一层
     * 「每天换当天图」。关掉轮播后会回到那套逻辑，届时靠 {@link #wpAutoKey} 判断要不要补今天那张。</p>
     */
    private void rotateBingWallpaperIfNeeded() {
        final ThemeStore c = cfg;
        if (c == null || !isBingMode() || c.wpRotateMin <= 0) {
            return;
        }
        final long period = c.wpRotateMin * 60_000L;
        final long now = System.currentTimeMillis();
        if (wpRotateMs > 0 && now - wpRotateMs < period) {
            return;
        }
        // 先记时刻：下载可能比一个 tick 还慢，不先记会连着触发好几轮
        wpRotateMs = now;
        final String mkt = c.bingMkt;
        final boolean uhd = c.bingUhd;
        final boolean sys = c.wpApplySystem;
        final int keep = c.wpRotatePool;
        final int cur = wpRotateIdx;
        final String today = dayFmt.format(new Date());
        new Thread(() -> {
            try {
                // 1) 每天把接口里的新图补进本地池（接口只覆盖最近 16 天，池子靠日积月累长大）
                if (!today.equals(wpPoolDay)) {
                    try {
                        for (Bing.Item it : Bing.list(mkt, POOL_WARM)) {
                            Bing.download(this, it, uhd, keep);
                        }
                        // 全下成功才记账；失败不记，下一轮（一个周期后）自己重试
                        wpPoolDay = today;
                        prefs.edit().putString(K_WP_POOLD, today).apply();
                    } catch (Throwable ignored) {
                    }
                }
                // 2) 池子还是空的（首次、或上面那次没成功）→ 再补一次
                List<File> pool = Bing.cached(this);
                if (pool.isEmpty()) {
                    for (Bing.Item it : Bing.list(mkt, POOL_WARM)) {
                        Bing.download(this, it, uhd, keep);
                    }
                    pool = Bing.cached(this);
                }
                if (pool.isEmpty()) {
                    return;
                }
                // 3) keep > 0 时只用最近的那几张；0 = 全都要
                final int n = (keep > 0 && keep < pool.size()) ? keep : pool.size();
                final int i = Math.abs(cur) % n;
                final File f = pool.get(i);
                final String key = dateOf(pool.get(i));
                runOnUiThread(() -> {
                    wpPath = f.getAbsolutePath();
                    wpDrawable = null;
                    wpDrawablePath = "";
                    wpRotateIdx = i + 1;
                    wpAutoKey = key;
                    // 轮播期间把「今天已定过」的记录清空：等哪天关掉轮播，autoDaily 会立刻
                    // 把当天那张补回来 —— 否则会一直停在轮播最后那张历史图上
                    wpAutoDay = "";
                    try {
                        prefs.edit().putString(K_WP, wpPath)
                                .putInt(K_WP_ROT, wpRotateIdx)
                                .putString(K_WP_KEY, wpAutoKey)
                                .putString(K_WP_DAY, "").apply();
                    } catch (Throwable ignored) {
                    }
                    applyTheme(themeIdx);
                    if (sys) {
                        trySetSystemWallpaper(f);
                    }
                });
            } catch (Throwable ignored) {
            }
        }, "bing-rotate").start();
    }

    /**
     * 从文件名里取出 {@code YYYYMMDD}；取不到返回空串。
     *
     * <p>两种命名都认：{@code bing_YYYYMMDD.jpg}（App 下载）和
     * {@code YYYYMMDD_标题.jpg}（外部导入的带标题库）。</p>
     */
    private static String dateOf(File f) {
        return Bing.dateOfName(f.getName());
    }

    /** 顺手把图写进系统壁纸（部分盒子没有壁纸服务，失败静默） */
    private void trySetSystemWallpaper(File f) {
        try {
            final Bitmap b = Bing.decode(f, 2560);
            if (b == null) {
                return;
            }
            android.app.WallpaperManager.getInstance(this).setBitmap(b);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "set system wallpaper failed: " + t);
        }
    }

    /** 异常信息可能很长，截短了放 toast */
    private static String shortErr(Throwable t) {
        String m = t == null ? "" : String.valueOf(t.getMessage());
        if (m.isEmpty()) {
            m = t == null ? "未知错误" : t.getClass().getSimpleName();
        }
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 造背景 Drawable：起止色相同→纯色；不同→**纵向**线性渐变「上深下浅」。
     * BOTTOM_TOP 的取色顺序是「底部色 → 顶部色」，所以这里传 (浅端, 深端)。
     * 用 GradientDrawable 而非 XML，便于主题表驱动、随时增删。
     */
    private static android.graphics.drawable.Drawable makeBackground(int bottom, int top) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        if (bottom == top) {
            g.setColor(bottom);
        } else {
            g.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP);
            g.setColors(new int[]{bottom, top});
        }
        return g;
    }

    /** 两色的平均值（用于渐变背景的亮度判定） */    private static int averageColor(int a, int b) {
        int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) / 2;
        int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) / 2;
        int bl = ((a & 0xFF) + (b & 0xFF)) / 2;
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** 感知亮度（ITU-R BT.601），>= 150 视为浅色底 */
    private static boolean isLightColor(int c) {
        final int r = (c >> 16) & 0xFF;
        final int g = (c >> 8) & 0xFF;
        final int b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 >= 150;
    }

    /**
     * 背景选择器。
     * 单选列表（可被遥控器上下+OK 操作），每项前面用底色本身画一个色块，
     * 渐变项标注「渐变」。选完立即生效并关闭，不用再点确定。
     * 列表内容完全来自配置文件，增减配色不用改主程序。
     */
    private void showBgPicker() {
        final List<Theme> list = themes;
        if (list == null || list.isEmpty()) {
            toast("没有可用配色，请检查配置文件");
            return;
        }
        final SpannableString[] items = new SpannableString[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final Theme th = list.get(i);
            // 前 3 格用方块字符铺成一个「色块」—— 比原来单个 ■ 醒目得多。
            // 对话框本身是深色底，所以浅色块也看得见。渐变项取底部（较亮）色。
            final String tag = th.isGradient() ? "  ·渐变" : "";
            final String line = "███ " + th.name + tag + (i == themeIdx ? "    ✓" : "");
            SpannableString s = new SpannableString(line);
            s.setSpan(new BackgroundColorSpan(th.swatchColor()), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = s;
        }
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(isBingMode() ? "背景（当前是 Bing 图片；选一项即切回纯色）"
                        : "背景（选一项即生效）")
                .setSingleChoiceItems(items, themeIdx, (d, w) -> {
                    // 从图片模式选回纯色：顺手把配置里的 mode 改掉，免得下次启动又被图片盖住
                    final ThemeStore c = cfg;
                    if (c != null && !"color".equalsIgnoreCase(c.wpMode)) {
                        c.wpMode = "color";
                        c.save(this, c.toJson());
                    }
                    applyTheme(w);
                    d.dismiss();
                })
                .setPositiveButton("Bing 壁纸", (d, w) -> showBingPicker())
                .setNeutralButton("图形化改配色", (d, w) -> showThemeManager())
                .setNegativeButton("关闭", null)
                .create();
        // 焦点直接钉在列表上：否则遥控器要先按几次方向键才能从「取消」绕到选项上
        dlg.setOnShowListener(d -> {
            try {
                dlg.getListView().requestFocus();
            } catch (Throwable ignored) {
            }
        });
        dlg.show();
    }

    /**
     * 配置文件编辑器：弹出多行输入框，直接编辑配置文件全文。
     * 保存后立即重载并生效；格式错误会提示且不影响当前画面。
     *
     * <p>TV 上用遥控器打字不便，所以编辑框只是「兜底」——
     * 更推荐用电脑/文件管理器改 {@link ThemeStore#file(android.content.Context)} 指向的文件，
     * 改完回桌面会自动重载（onResume 检测 mtime）。</p>
     */
    private void showConfigEditor() {
        final String path = ThemeStore.pathText(this);
        final EditText et = new EditText(this);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setTextSize(12f);
        et.setHorizontallyScrolling(false);
        et.setMinLines(10);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setText(ThemeStore.readRaw(this));
        // 光标移到开头，方便直接看第一行
        try {
            et.setSelection(0);
        } catch (Throwable ignored) {
        }
        final ScrollView sv = new ScrollView(this);
        sv.addView(et);
        sv.setPadding(0, 4, 0, 0);

        // 编辑框上方一行「参数说明」，点开是每个参数的用途（从配置文件里的 _说明 键生成）
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));

        TextView help = new TextView(this);
        help.setText("？参数说明（点这里看每个参数的含义）");
        help.setTextSize(12f);
        help.setTextColor(0xFF4EA1FF);
        help.setPadding(0, dp(6), 0, dp(10));
        help.setFocusable(true);
        help.setOnClickListener(v -> showConfigHelp());
        box.addView(help);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        new AlertDialog.Builder(this)
                .setTitle("编辑配置  ·  " + path)
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    final String txt = et.getText().toString();
                    // 保存可能要绕道本地 adb（网络操作），必须在后台线程
                    new Thread(() -> {
                        ThemeStore probe = new ThemeStore();
                        probe.parse(txt);
                        android.util.Log.i("HKSAVE", "len=" + txt.length()
                                + " probeErr=" + probe.lastError
                                + " target=" + ThemeStore.pathText(MainActivity.this));
                        if (probe.lastError != null) {
                            final String err = probe.lastError;
                            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("没有保存")
                                    .setMessage(err + "\n\n常见原因：\n"
                                            + "· 少了一个逗号或引号\n"
                                            + "· 用了中文标点（，：；“”）\n"
                                            + "· 最后一项多了一个逗号\n\n"
                                            + "改好后再点一次「保存」。")
                                    .setPositiveButton("知道了", null)
                                    .show());
                            return;
                        }
                        boolean ok = cfg.save(MainActivity.this, txt);
                        android.util.Log.i("HKSAVE", "save=" + ok + " err=" + cfg.lastError);
                        runOnUiThread(() -> {
                            if (!ok) {
                                toast("保存失败：" + cfg.lastError);
                                return;
                            }
                            reloadConfig();
                            toast("已保存并生效");
                        });
                    }, "cfg-save").start();
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    try {
                        cfg.save(MainActivity.this, ThemeStore.DEFAULT_JSON);
                        reloadConfig();
                        toast("已恢复默认配置");
                    } catch (Throwable t) {
                        toast("恢复失败");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 配置参数说明：内容直接读配置文件里的 {@code _说明} / {@code _参数名} 键生成，
     * 所以改配置文件里的说明文字，这里跟着变，不用改代码。
     */
    private void showConfigHelp() {
        StringBuilder sb = new StringBuilder();
        try {
            String json = ThemeStore.readRaw(this);
            org.json.JSONObject o = new org.json.JSONObject(json);
            java.util.Iterator<String> segs = o.keys();
            while (segs.hasNext()) {
                String seg = segs.next();
                org.json.JSONObject so = o.optJSONObject(seg);
                if (so == null) {
                    continue;
                }
                String doc = so.optString("_说明", "");
                sb.append("【").append(seg).append("】");
                if (!doc.isEmpty()) {
                    sb.append("  ").append(doc);
                }
                sb.append("\n");
                java.util.Iterator<String> ks = so.keys();
                while (ks.hasNext()) {
                    String k = ks.next();
                    if (!k.startsWith("_") || "_说明".equals(k)) {
                        continue;
                    }
                    sb.append("  ").append(k.substring(1)).append("：")
                            .append(so.optString(k)).append("\n");
                }
                sb.append("\n");
            }
        } catch (Throwable t) {
            sb.append("读取配置失败：").append(t);
        }
        if (sb.length() == 0) {
            sb.append("当前配置文件里没有说明字段。\n\n"
                    + "说明以 _说明 / _参数名 的形式写在 config.json 里，App 会忽略它们。\n"
                    + "恢复方法：在电脑上运行 tools/annotate_cfg.py 重新生成后写回。");
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12f);
        tv.setLineSpacing(dp(4), 1f);
        tv.setPadding(dp(24), dp(14), dp(24), dp(14));

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("配置参数说明")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ==================== 图形化配色管理（v4.1）====================

    /**
     * 图形化调色板预置色：深 → 中 → 浅 → 鲜艳 四段共 40 色。
     * 遥控器/鼠标直接点格子即可取色，不必打字输十六进制。
     */
    private static final String[] PALETTE = {
            "#0E1116", "#1B1F24", "#23262B", "#10243E", "#123027", "#1A1035", "#2B1435", "#3A2A12",
            "#4A2E86", "#8A3A1E", "#0E6B72", "#8A2050", "#3A4150", "#6B4520", "#3A8A96", "#4A5A6A",
            "#8E6BB8", "#C2703C", "#3FA9B3", "#C24A82", "#77839A", "#B08A5A", "#7ED0C8", "#93A3B8",
            "#BFEFE6", "#FFE2BE", "#EDEFF2", "#D9F2EC", "#F5E6D3", "#C3E8E8", "#E8D5C4", "#FFFFFF",
            "#E5484D", "#F5A524", "#30A46C", "#3B82F6", "#8E4EC6", "#E93D82", "#12A594", "#FFB224"
    };

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 色卡 Drawable：纯色或纵向渐变，带圆角与细描边（浅色块也能看出边界） */
    private android.graphics.drawable.Drawable swatchDrawable(int bottom, int top, int cornerDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(cornerDp));
        g.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        if (bottom == top) {
            g.setColor(bottom);
        } else {
            g.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP);
            g.setColors(new int[]{bottom, top});
        }
        return g;
    }

    /** 色板小格子：常态细白边、聚焦/按下时换成加粗蓝边 */
    private android.graphics.drawable.Drawable cellDrawable(int color) {
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, strokeBox(color, 3, 0xFF4C8DFF));
        sld.addState(new int[]{android.R.attr.state_pressed}, strokeBox(color, 3, 0xFF4C8DFF));
        sld.addState(new int[]{}, strokeBox(color, 1, 0x66FFFFFF));
        return sld;
    }

    private android.graphics.drawable.Drawable strokeBox(int color, int strokeDp, int strokeColor) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(5));
        g.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return g;
    }

    /** 统一的"胶囊按钮"样式（可聚焦的 TextView） */
    private TextView pill(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14f);
        b.setFocusable(true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setBackgroundResource(R.drawable.pill_bg);
        b.setTextColor(0xFFE9EEF7);
        return b;
    }

    /**
     * 配色管理主界面：列出当前所有配色，每项带**真实渐变/纯色缩略色卡**。
     * 点任意一项 → 进入配色编辑器；底部可新增 / 恢复默认。
     */
    private void showThemeManager() {
        if (cfg == null) {
            reloadConfig();
        }
        final List<Theme> list = (cfg != null) ? cfg.themes : themes;

        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(10), dp(10), dp(10));
        for (int i = 0; i < list.size(); i++) {
            col.addView(themeRow(i, list.get(i)));
        }

        final ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        sv.addView(col);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("配色管理 · 共 " + list.size() + " 项（点一项即可改）")
                .setView(sv)
                .setPositiveButton("＋ 新增配色", null)
                .setNeutralButton("恢复默认", null)
                .setNegativeButton("关闭", null)
                .create();
        // 手动接管三个按钮：点「改配色」时要先关掉本对话框，避免两层弹窗叠在一起
        mgrDlg = dlg;
        dlg.setOnShowListener(d -> {
            try {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    dlg.dismiss();
                    editTheme(-1);
                });
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    try {
                        if (cfg != null && cfg.save(this, ThemeStore.DEFAULT_JSON)) {
                            reloadConfig();
                            toast("已恢复默认配色");
                        } else {
                            toast("恢复失败");
                        }
                    } catch (Throwable t) {
                        toast("恢复失败");
                    }
                    dlg.dismiss();
                });
            } catch (Throwable ignored) {
            }
        });
        dlg.show();
    }

    /** 当前打开的「配色管理」对话框（打开编辑器前要先关掉它） */
    private AlertDialog mgrDlg;

    /** 管理列表里的一行：色卡 + 名称 + 色值，可聚焦可点 */
    private View themeRow(final int index, final Theme th) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.pill_bg);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        View sw = new View(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(84), dp(46));
        slp.rightMargin = dp(14);
        sw.setLayoutParams(slp);
        sw.setBackground(swatchDrawable(th.bottom, th.top, 7));
        row.addView(sw);

        TextView tv = new TextView(this);
        String kind = th.isGradient()
                ? "渐变   " + ThemeStore.toHex6(th.top) + " → " + ThemeStore.toHex6(th.bottom)
                : "纯色   " + ThemeStore.toHex6(th.bottom);
        tv.setText(th.name + "\n" + kind + ((index == themeIdx) ? "   ✓ 使用中" : ""));
        tv.setTextSize(14f);
        tv.setTextColor(0xFFE9EEF7);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        row.setOnClickListener(v -> {
            if (mgrDlg != null) {
                mgrDlg.dismiss();
            }
            editTheme(index);
        });
        return row;
    }

    /**
     * 配色编辑器：名称 + 纯色/渐变切换 + 实时预览 + 40 色图形色板 + 目标端切换 + RGB 滑块。
     * index = -1 表示新增。保存时写回配置文件并立即生效。
     */
    private void editTheme(final int index) {
        if (cfg == null) {
            reloadConfig();
        }
        if (cfg == null) {
            toast("配置未就绪");
            return;
        }
        final Theme cur = (index >= 0 && index < cfg.themes.size()) ? cfg.themes.get(index) : null;

        // ---- 编辑中的状态（数组是为了能在 lambda / 内部类里改）----
        // 新增时的默认值：沿用「上深下浅」的约定（顶部近黑、底部亮紫）
        final boolean[] gradient = {cur == null || cur.isGradient()};
        final int[] cTop = {cur != null ? cur.top : 0xFF07040F};
        final int[] cBottom = {cur != null ? cur.bottom : 0xFF4A2E86};
        if (!gradient[0]) {
            cTop[0] = cBottom[0];
        }
        // 当前调节哪一端：0 = 顶部（深），1 = 底部（浅）；纯色只有 1
        final int[] target = {gradient[0] ? 0 : 1};

        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));

        // 名称
        TextView lbName = new TextView(this);
        lbName.setText("名称");
        lbName.setTextSize(13f);
        lbName.setTextColor(0xFF8C97A8);
        col.addView(lbName);
        final EditText nameEt = new EditText(this);
        nameEt.setSingleLine(true);
        nameEt.setTextSize(15f);
        nameEt.setText(cur != null ? cur.name : ("配色 " + (cfg.themes.size() + 1)));
        col.addView(nameEt);

        // 类型切换
        final TextView btnSolid = pill("纯色");
        final TextView btnGrad = pill("渐变");
        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeRow.setPadding(0, dp(8), 0, 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = dp(10);
        btnSolid.setLayoutParams(blp);
        typeRow.addView(btnSolid);
        typeRow.addView(btnGrad);
        col.addView(typeRow);

        // 实时预览
        final View preview = new View(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100));
        plp.topMargin = dp(10);
        preview.setLayoutParams(plp);
        col.addView(preview);

        // 调哪一端
        final TextView btnTop = pill("顶部色");
        final TextView btnBottom = pill("底部色");
        final LinearLayout targetRow = new LinearLayout(this);
        targetRow.setOrientation(LinearLayout.HORIZONTAL);
        targetRow.setPadding(0, dp(8), 0, 0);
        btnTop.setLayoutParams(blp);
        targetRow.addView(btnTop);
        targetRow.addView(btnBottom);
        col.addView(targetRow);

        // 当前色值
        final TextView hex = new TextView(this);
        hex.setTextSize(14f);
        hex.setTextColor(0xFFE9EEF7);
        hex.setPadding(0, dp(8), 0, dp(4));
        col.addView(hex);

        // 40 色图形色板（8 × 5）
        final List<View> cells = new ArrayList<>();
        LinearLayout prow = null;
        for (int i = 0; i < PALETTE.length; i++) {
            if (i % 8 == 0) {
                prow = new LinearLayout(this);
                prow.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(prow);
            }
            final String hx = PALETTE[i];
            View cell = new View(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(38), dp(34));
            clp.rightMargin = dp(5);
            clp.bottomMargin = dp(5);
            cell.setLayoutParams(clp);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setBackground(cellDrawable(ThemeStore.parseColor(hx)));
            cell.setTag(hx);
            prow.addView(cell);
            cells.add(cell);
        }

        // RGB 微调
        final SeekBar[] sb = new SeekBar[3];
        final String[] rgbName = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(rgbName[i]);
            t.setTextSize(14f);
            t.setTextColor(0xFF8C97A8);
            t.setWidth(dp(26));
            r.addView(t);
            SeekBar s = new SeekBar(this);
            s.setMax(255);
            s.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            sb[i] = s;
            r.addView(s);
            col.addView(r);
        }

        final ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(440)));
        sv.addView(col);

        // ---- 刷新：把状态画到所有控件上（预览 / 色值 / 高亮 / 滑块）----
        final Runnable refresh = () -> {
            final int shown = (gradient[0] && target[0] == 0) ? cTop[0] : cBottom[0];
            preview.setBackground(makeBackground(cBottom[0], gradient[0] ? cTop[0] : cBottom[0]));
            hex.setText("正在调：" + (gradient[0] ? (target[0] == 0 ? "顶部色" : "底部色") : "颜色")
                    + "  " + ThemeStore.toHex6(shown)
                    + (gradient[0]
                    ? "      成品 " + ThemeStore.toHex6(cTop[0]) + " → " + ThemeStore.toHex6(cBottom[0])
                    : ""));
            btnSolid.setAlpha(gradient[0] ? 0.45f : 1f);
            btnGrad.setAlpha(gradient[0] ? 1f : 0.45f);
            targetRow.setVisibility(gradient[0] ? View.VISIBLE : View.GONE);
            btnTop.setAlpha(target[0] == 0 ? 1f : 0.45f);
            btnBottom.setAlpha(target[0] == 1 ? 1f : 0.45f);
            final int r = (shown >> 16) & 0xFF, g2 = (shown >> 8) & 0xFF, b2 = shown & 0xFF;
            final int[] want = {r, g2, b2};
            for (int i = 0; i < 3; i++) {
                if (sb[i].getProgress() != want[i]) {
                    sb[i].setProgress(want[i]);
                }
            }
        };

        // 改「当前正在调的那一端」
        class Setter {
            void set(int v) {
                if (gradient[0] && target[0] == 0) {
                    cTop[0] = v;
                } else {
                    cBottom[0] = v;
                    if (!gradient[0]) {
                        cTop[0] = v;
                    }
                }
                refresh.run();
            }
        }
        final Setter setter = new Setter();

        // ---- 挂事件 ----
        btnSolid.setOnClickListener(v -> {
            gradient[0] = false;
            cTop[0] = cBottom[0];
            target[0] = 1;
            refresh.run();
        });
        btnGrad.setOnClickListener(v -> {
            gradient[0] = true;
            target[0] = 0;
            refresh.run();
        });
        btnTop.setOnClickListener(v -> {
            target[0] = 0;
            refresh.run();
        });
        btnBottom.setOnClickListener(v -> {
            target[0] = 1;
            refresh.run();
        });
        for (View cell : cells) {
            cell.setOnClickListener(v -> {
                Object tag = v.getTag();
                if (tag instanceof String) {
                    setter.set(ThemeStore.parseColor((String) tag));
                }
            });
        }
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            sb[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    final int shown = (gradient[0] && target[0] == 0) ? cTop[0] : cBottom[0];
                    int r = (shown >> 16) & 0xFF, g2 = (shown >> 8) & 0xFF, b2 = shown & 0xFF;
                    if (idx == 0) {
                        r = p;
                    } else if (idx == 1) {
                        g2 = p;
                    } else {
                        b2 = p;
                    }
                    setter.set(0xFF000000 | (r << 16) | (g2 << 8) | b2);
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                }
            });
        }
        refresh.run();

        final AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "编辑配色" : "新增配色")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    String nm = nameEt.getText().toString().trim();
                    if (nm.isEmpty()) {
                        nm = "配色 " + (cfg.themes.size() + 1);
                    }
                    final Theme nt = gradient[0]
                            ? new Theme(nm, cTop[0], cBottom[0])
                            : new Theme(nm, cBottom[0]);
                    try {
                        if (index >= 0 && index < cfg.themes.size()) {
                            cfg.themes.set(index, nt);
                        } else {
                            cfg.themes.add(nt);
                        }
                        int newIdx = index >= 0 ? index : cfg.themes.size() - 1;
                        if (!cfg.save(MainActivity.this, cfg.toJson())) {
                            toast("保存失败：" + cfg.lastError);
                            return;
                        }
                        reloadConfig();
                        applyTheme(newIdx);
                        toast("已保存并生效：" + nt.name);
                        ui.post(MainActivity.this::showThemeManager);
                    } catch (Throwable t) {
                        toast("保存失败");
                    }
                })
                .setNegativeButton("取消", null);
        if (index >= 0) {
            b.setNeutralButton("删除", (d, w) -> {
                try {
                    if (cfg.themes.size() <= 1) {
                        toast("至少保留一个配色");
                        return;
                    }
                    cfg.themes.remove(index);
                    if (!cfg.save(MainActivity.this, cfg.toJson())) {
                        toast("删除失败：" + cfg.lastError);
                        return;
                    }
                    reloadConfig();
                    toast("已删除");
                    ui.post(MainActivity.this::showThemeManager);
                } catch (Throwable t) {
                    toast("删除失败");
                }
            });
        }
        b.show();
    }

    /** 检测配置文件是否被外部改过，改了才重载（避免每次 onResume 都重建） */
    private void reloadConfigIfChanged() {        try {
            long m = ThemeStore.file(this).lastModified();
            if (m != cfgMtime) {
                reloadConfig();
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
