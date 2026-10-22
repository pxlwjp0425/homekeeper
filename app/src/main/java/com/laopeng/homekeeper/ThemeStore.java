package com.laopeng.homekeeper;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置文件的读写（背景配色 + 网格/文字/页签等界面参数）。
 *
 * <p>位置：{@code /sdcard/homekeeper/config.json}（外置存储，可用电脑/文件管理器直接改）。
 * App 启动时读；读不到 / 解析失败则用 {@link #DEFAULT_JSON} 内置默认，
 * 并把默认写出去，方便用户看到格式后照抄修改。</p>
 *
 * <p>★ 设计要点：主程序**不再内置配色数组**，一切以本文件为准。
 * 用户改文件 → 回桌面自动生效（onResume 时检测文件 mtime 变化后重载）。</p>
 */
final class ThemeStore {

    private static final String TAG = "HKDBG";
    /** 配置文件所在目录（外置存储） */
    private static final String DIR_NAME = "homekeeper";
    private static final String FILE_NAME = "config.json";

    /** 内置默认配置：既是首次运行时的初始值，也是解析失败时的兜底 */
    static final String DEFAULT_JSON = "{"
            + "\n  \"tabs\": { \"home\": \"首页\", \"all\": \"全部应用\", \"bg\": \"背景\" },"
            + "\n  \"grid\": { \"favColumns\": 5, \"allColumns\": 7,"
            + "\n             \"favRowSpacingDp\": 40, \"allRowSpacingDp\": 10,"
            + "\n             \"favColSpacingDp\": 8, \"allColSpacingDp\": 8,"
            + "\n             \"iconSizeDp\": 160, \"iconPadBottomDp\": 12 },"
            + "\n  \"icon\": { \"sizeRatio\": 0.88, \"cornerRatio\": 0.3,"
            + "\n            \"strokeDp\": 1.5, \"strokeColor\": \"#33FFFFFF\" },"
            + "\n  \"text\": { \"clockSizeSp\": 38, \"dateSizeSp\": 16, \"tabSizeSp\": 15,"
            + "\n             \"nameSizeSp\": 16, \"namePadTopDp\": 10 },"
            + "\n  \"topbar\": { \"showLunar\": true, \"showWeather\": true,"
            + "\n              \"city\": \"\", \"refreshMinutes\": 30 },"
            + "\n  \"overlay\": { \"enabled\": true, \"marginDp\": 24, \"clockSizeSp\": 28,"
            + "\n               \"dateSizeSp\": 14, \"bgAlpha\": 0, \"shadow\": true },"
            + "\n  \"wallpaper\": { \"mode\": \"color\", \"bingMkt\": \"zh-CN\","
            + "\n                 \"bingUhd\": false, \"dim\": 35, \"applySystem\": false,"
            + "\n                 \"autoDaily\": true, \"rotateMinutes\": 0, \"rotatePool\": 0 },"
            + "\n  \"themes\": ["
            + "\n    { \"name\": \"深空黑\", \"color\": \"#0E1116\" },"
            + "\n    { \"name\": \"石墨灰\", \"color\": \"#23262B\" },"
            + "\n    { \"name\": \"午夜蓝\", \"color\": \"#10243E\" },"
            + "\n    { \"name\": \"松林绿\", \"color\": \"#123027\" },"
            + "\n    { \"name\": \"云母白\", \"color\": \"#EDEFF2\" },"
            + "\n    { \"name\": \"星紫\",   \"top\": \"#07040F\", \"bottom\": \"#4A2E86\" },"
            + "\n    { \"name\": \"暮霞\",   \"top\": \"#1A0805\", \"bottom\": \"#8A3A1E\" },"
            + "\n    { \"name\": \"深海青\", \"top\": \"#02161C\", \"bottom\": \"#0E6B72\" },"
            + "\n    { \"name\": \"品红夜\", \"top\": \"#17030C\", \"bottom\": \"#8A2050\" },"
            + "\n    { \"name\": \"石墨\",   \"top\": \"#0B0D12\", \"bottom\": \"#3A4150\" },"
            + "\n    { \"name\": \"暖沙\",   \"top\": \"#6B4520\", \"bottom\": \"#FFE2BE\" },"
            + "\n    { \"name\": \"薄荷雾\", \"top\": \"#3A8A96\", \"bottom\": \"#BFEFE6\" }"
            + "\n  ]"
            + "\n}";

    // ---- 解析结果（全部有默认值，解析失败也不会崩）----
    final List<Theme> themes = new ArrayList<>();
    String tabHome = "首页";
    String tabAll = "全部应用";
    String tabBg = "背景";
    int favColumns = 5;
    int allColumns = 7;
    int favRowSpacingDp = 40;
    int allRowSpacingDp = 10;
    int favColSpacingDp = 8;
    int allColSpacingDp = 8;
    int iconSizeDp = 160;
    int iconPadBottomDp = 12;
    /** 图标统一样式：图标边长 ÷ 格子边长（越小留白越多） */
    float iconSizeRatio = 0.88f;
    /** 图标圆角半径 ÷ 图标边长（0.5 = 胶囊；0 = 直角） */
    float iconCornerRatio = 0.3f;
    /** 图标描边宽度 dp（0 = 不描边） */
    float iconStrokeDp = 1.5f;
    /** 图标描边颜色（建议半透明，深底浅底都好看） */
    int iconStrokeColor = 0x33FFFFFF;
    int clockSizeSp = 38;
    int dateSizeSp = 16;
    int tabSizeSp = 15;
    int nameSizeSp = 16;
    int namePadTopDp = 10;
    /** 顶栏日期后是否追加农历（如「农历八月十九」） */
    boolean showLunar = true;
    /** 顶栏是否在星期后追加本地天气（如「晴 21℃」） */
    boolean showWeather = true;
    /** 天气地点：空 / "auto" = 按公网 IP 自动定位；也可写城市名（福州）或坐标（26.06,119.31） */
    String weatherCity = "";
    /**
     * 小米天气 locationKey（如 {@code weathercn:101230202} = 福建厦门·同安区）。
     * 非空 → 桌面顶栏改走「天气 TV」App 同一套小米接口，两边数据完全一致；
     * 空 → 沿用 open-meteo（按 city / IP 定位）。
     */
    String weatherKey = MiWeather.KEY_TONGAN;
    /** 天气刷新间隔（分钟） */
    int weatherRefreshMin = 30;

    /** 交给 {@link Sky} 的「地点串」：配了 locationKey 就用它（走小米），否则用城市名/IP。 */
    String skyPlace() {
        return (weatherKey != null && !weatherKey.isEmpty()) ? weatherKey : weatherCity;
    }

    // ---- 音频（v4.7）：把「通话类音频」从不存在的外放拉回 HDMI ----
    // ---- 悬浮信息条（v4.7）：把「时间 + 日期/农历/天气」浮在其它 App 之上 ----
    /** 是否显示悬浮信息条（需 {@code SYSTEM_ALERT_WINDOW} 授权，见 OverlayService） */
    boolean ovEnabled = true;
    /** 距屏幕左/上边缘的边距（dp） */
    int ovMarginDp = 24;
    /** 悬浮条里时间的字号（sp） */
    int ovClockSizeSp = 28;
    /** 悬浮条里日期那行的字号（sp） */
    int ovDateSizeSp = 14;
    /** 悬浮条底色黑的不透明度 %（0 = 全透明，只有白字 + 阴影）—— 默认全透明 */
    int ovBgAlpha = 0;
    /** 文字是否带投影（浅色画面上也能看清） */
    boolean ovShadow = true;

    // ---- 悬浮工具条（v4.11）：清内存 / 录屏开关 / 重启 / 关机，浮在其它 App 之上 ----
    /** 是否显示可交互悬浮工具条（需 SYSTEM_ALERT_WINDOW，见 ToolsOverlayService） */
    boolean toolsEnabled = false;
    /** 工具条卡片距屏幕左/上边缘（dp） */
    int toolsMarginDp = 24;
    /**
     * 重启 / 关机的完整 shell 命令（走本机 adbd 执行）。
     * 该 ROM 的 reboot 需 root，普通 shell 会被拒，故默认填 usrchan root 通道：
     * 重启 = {@code echo <口令> | /system/xbin/usrchan root sh -c 'reboot'}
     * 关机 = {@code echo <口令> | /system/xbin/usrchan root sh -c 'reboot -p'}
     * 口令放 config.json（用户可改、已入库但属本机私有），不写死在源码里。
     * 留空则工具条里对应按钮禁用。
     */
    String toolsRebootCmd = "";
    String toolsOffCmd = "";

    // ---- 壁纸（v4.6）：纯色/渐变 还是 Bing 每日一图 ----
    /** 壁纸模式：{@code color}（用 themes 的纯色/渐变）| {@code bing}（Bing 每日一图，本地已选的那张） */
    String wpMode = "color";
    /** Bing 市场，{@code zh-CN} 返回中文本地化的图与标题 */
    String bingMkt = "zh-CN";
    /** 是否下载 4K 原图（约 3.5MB；盒子内存紧张，默认只用 1920×1080） */
    boolean bingUhd = false;
    /** 图片壁纸上的黑色遮罩百分比（0-80）—— 压暗照片，保证时钟/文字可读 */
    int wpDim = 35;
    /** 是否同时把图写进系统壁纸（部分盒子无壁纸服务，失败静默） */
    boolean wpApplySystem = false;
    /**
     * （v4.7）用了 Bing 壁纸时，是否每天自动换成当天的新图。
     * 关掉就固定成手动选的那张。
     */
    boolean wpAutoDaily = true;
    /**
     * （v4.7）每隔多少分钟轮换一张 Bing 图（从最近 {@link #wpRotatePool} 张里循环）；
     * {@code 0} = 不轮换。开了轮播就不再走 {@link #wpAutoDaily} 那套「每天换当天图」——
     * 列表本身每天在变，轮播自然会轮到新图。
     */
    int wpRotateMin = 0;
    /**
     * （v4.7）轮播池的**张数上限**，也是本地缓存的保留上限（0 = <b>不限制</b>，0-365）。
     *
     * <p>Bing 官方接口一次只给 8 张、翻页最多凑 16 张，所以"想要更多"只能靠<b>本地累积</b>：
     * 每天把新图存进 {@code files/bing}，池子随时间长大。设 0 就是有多少用多少，
     * 只受磁盘空间约束（一张约 340KB，一年也就 120MB 上下）。</p>
     */
    int wpRotatePool = 0;

    /** 读取失败的原因（供界面提示），null = 正常 */
    String lastError;

    /** 配置文件路径（外置优先；外置不可写时自动回退到 App 私有目录） */
    static File file() {
        return file(null);
    }

    /** 解析配置文件路径。
     *
     * <p>优先级（实测可写性，不靠 {@code canWrite()}——本机 fuse 对它返回 EPERM）：
     * <ol>
     *   <li>外置 {@code /sdcard/homekeeper/config.json}：能<b>直接落盘</b>就优先用它
     *       （电脑/文件管理器可直接改）。</li>
     *   <li>外置目录打不开（如极光盒子 fuse/SELinux 对 App 进程 EPERM/EACCES，
     *       且 chmod/chown 被强制回写改不动）→ 回退到 App 私有目录
     *       {@code <files>/homekeeper/config.json}（SELinux 标签自动是本 App 域，
     *       读写 100% 可保证，界面编辑仍可用）。</li>
     * </ol>
     * 探测只在首次做一次并缓存，避免每 15s 重载都开目录。
     */
    static File file(Context ctx) {
        File ext = new File(new File(Environment.getExternalStorageDirectory(), DIR_NAME), FILE_NAME);
        if (ctx == null) {
            return ext;
        }
        // 探测结果必须与「调用者是不是 Activity」无关：
        // 以前只有 Activity 才探测，桌面判定外置不可写走私有目录、悬浮条（Service）却读 /sdcard，
        // 两边各写各的配置 —— 改字号/开关在悬浮条上永远不生效。
        if (!sDirProbeDone) {
            // 直接落盘探测：能真正打开外置目录才用外置，否则退回私有目录
            try {
                new File(Environment.getExternalStorageDirectory(), DIR_NAME).mkdirs();
                File probe = new File(Environment.getExternalStorageDirectory(),
                        DIR_NAME + "/.writeprobe_" + Long.toString(System.currentTimeMillis(), 36));
                java.io.FileOutputStream fo = new java.io.FileOutputStream(probe, true);
                fo.close();
                probe.delete();
                sUseExtDir = true;
            } catch (Throwable ignored) {
                sUseExtDir = false;   // 外置打不开 → 用私有目录
            }
            sDirProbeDone = true;
        }
        if (sUseExtDir) {
            return ext;
        }
        // 私有目录：getFilesDir() 已含 /files，再挂一级 homekeeper 子目录避免撞别的文件
        return new File(ctx.getFilesDir(), DIR_NAME + "/" + FILE_NAME);
    }

    /** 外置配置目录（用于提示用户去哪改文件） */
    static File dir() {
        return new File(Environment.getExternalStorageDirectory(), DIR_NAME);
    }

    /** 缓存：是否已探测外置目录可写性 */
    private static boolean sDirProbeDone = false;
    /** 探测结果：外置 /sdcard/homekeeper 能否直接落盘（本机 fuse 可写才为 true） */
    private static boolean sUseExtDir = true;

    /** 当前实际生效的配置路径文本（供界面提示） */
    static String pathText(Context ctx) {
        return file(ctx).getAbsolutePath();
    }

    /**
     * 从磁盘加载配置。文件不存在时把默认配置写出去。
     * 任何异常都回退到默认值（绝不抛给调用方）。
     */
    static ThemeStore load(Context ctx) {
        ThemeStore s = new ThemeStore();
        File f = file(ctx);
        String json = null;
        try {
            if (!f.exists()) {
                // 首次：把默认配置落盘，用户就有模板可抄了
                writeFile(f, DEFAULT_JSON);
            }
            json = readFile(f);
            android.util.Log.i(TAG, "load " + f.getAbsolutePath()
                    + " exists=" + f.exists() + " len=" + (json == null ? -1 : json.length()));
            // 老版本配置文件没有 topbar 段 → 补一段写回去，用户才有地方改
            json = migrate(f, json);
        } catch (Throwable t) {
            Log.w(TAG, "config read failed: " + t);
        }
        if (json == null || json.trim().isEmpty()) {
            json = DEFAULT_JSON;
            s.lastError = "配置文件为空，已用内置默认";
        }
        s.parse(json);
        return s;
    }

    /**
     * 老配置文件补新段：缺 {@code topbar} / {@code wallpaper} 就补上内置默认值并写回
     * （原有内容与顺序保留）。已经是新格式的文件原样返回，不重写 —— 免得每次启动都动用户的文件。
     */
    private static String migrate(File f, String json) {
        if (json == null || json.trim().isEmpty()) {
            return json;
        }
        try {
            JSONObject o = new JSONObject(json);
            JSONObject def = new JSONObject(DEFAULT_JSON);
            boolean changed = false;
            for (String key : new String[]{"topbar", "wallpaper", "overlay"}) {
                final JSONObject d = def.optJSONObject(key);
                if (d == null) {
                    continue;
                }
                if (!o.has(key)) {
                    o.put(key, d);
                    changed = true;
                    continue;
                }
                // 段还在、但新版本往里加了子键（如 wallpaper.autoDaily）→ 补上，用户才有地方改
                final JSONObject cur = o.optJSONObject(key);
                if (cur == null) {
                    continue;
                }
                for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) {
                    final String k = it.next();
                    if (!cur.has(k)) {
                        cur.put(k, d.opt(k));
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return json;
            }
            String merged = o.toString(2);
            writeFile(f, merged);
            Log.i(TAG, "config migrated: missing sections added");
            return merged;
        } catch (Throwable t) {
            Log.w(TAG, "config migrate skipped: " + t);
            return json;
        }
    }

    /** 解析 JSON 文本；失败则整份回退到默认，并记 lastError */
    void parse(String json) {
        // 先清空：本方法可能在同一实例上被调用多次（如 save() 写盘后回读），
        // 不清会把 themes 累加成双份。
        themes.clear();
        try {
            JSONObject o = new JSONObject(json);

            JSONObject tabs = o.optJSONObject("tabs");
            if (tabs != null) {
                tabHome = tabs.optString("home", tabHome);
                tabAll = tabs.optString("all", tabAll);
                tabBg = tabs.optString("bg", tabBg);
            }

            JSONObject g = o.optJSONObject("grid");
            if (g != null) {
                favColumns = g.optInt("favColumns", favColumns);
                allColumns = g.optInt("allColumns", allColumns);
                favRowSpacingDp = g.optInt("favRowSpacingDp", favRowSpacingDp);
                allRowSpacingDp = g.optInt("allRowSpacingDp", allRowSpacingDp);
                favColSpacingDp = g.optInt("favColSpacingDp", favColSpacingDp);
                allColSpacingDp = g.optInt("allColSpacingDp", allColSpacingDp);
                iconSizeDp = g.optInt("iconSizeDp", iconSizeDp);
                iconPadBottomDp = g.optInt("iconPadBottomDp", iconPadBottomDp);
            }

            JSONObject ic = o.optJSONObject("icon");
            if (ic != null) {
                iconSizeRatio = (float) ic.optDouble("sizeRatio", iconSizeRatio);
                iconCornerRatio = (float) ic.optDouble("cornerRatio", iconCornerRatio);
                iconStrokeDp = (float) ic.optDouble("strokeDp", iconStrokeDp);
                String sc = ic.optString("strokeColor", null);
                if (sc != null && !sc.trim().isEmpty()) {
                    iconStrokeColor = parseColor(sc);
                }
            }

            JSONObject t = o.optJSONObject("text");
            if (t != null) {
                clockSizeSp = t.optInt("clockSizeSp", clockSizeSp);
                dateSizeSp = t.optInt("dateSizeSp", dateSizeSp);
                tabSizeSp = t.optInt("tabSizeSp", tabSizeSp);
                nameSizeSp = t.optInt("nameSizeSp", nameSizeSp);
                namePadTopDp = t.optInt("namePadTopDp", namePadTopDp);
            }

            JSONObject tb = o.optJSONObject("topbar");
            if (tb != null) {
                showLunar = tb.optBoolean("showLunar", showLunar);
                showWeather = tb.optBoolean("showWeather", showWeather);
                String ct = tb.optString("city", null);
                if (ct != null) {
                    weatherCity = ct;
                }
                String lk = tb.optString("locationKey", null);
                if (lk != null) {
                    weatherKey = lk.trim();
                }
                weatherRefreshMin = tb.optInt("refreshMinutes", weatherRefreshMin);
            }

            JSONObject ov = o.optJSONObject("overlay");
            if (ov != null) {
                ovEnabled = ov.optBoolean("enabled", ovEnabled);
                ovMarginDp = ov.optInt("marginDp", ovMarginDp);
                ovClockSizeSp = ov.optInt("clockSizeSp", ovClockSizeSp);
                ovDateSizeSp = ov.optInt("dateSizeSp", ovDateSizeSp);
                ovBgAlpha = ov.optInt("bgAlpha", ovBgAlpha);
                ovShadow = ov.optBoolean("shadow", ovShadow);
            }

            JSONObject tl = o.optJSONObject("tools");
            if (tl != null) {
                toolsEnabled = tl.optBoolean("enabled", toolsEnabled);
                toolsMarginDp = tl.optInt("marginDp", toolsMarginDp);
                String rc = tl.optString("rebootCmd", null);
                if (rc != null) {
                    toolsRebootCmd = rc.trim();
                }
                String oc = tl.optString("offCmd", null);
                if (oc != null) {
                    toolsOffCmd = oc.trim();
                }
            }

            JSONObject wp = o.optJSONObject("wallpaper");
            if (wp != null) {
                String m = wp.optString("mode", null);
                if (m != null && !m.trim().isEmpty()) {
                    wpMode = m.trim();
                }
                String mk = wp.optString("bingMkt", null);
                if (mk != null && !mk.trim().isEmpty()) {
                    bingMkt = mk.trim();
                }
                bingUhd = wp.optBoolean("bingUhd", bingUhd);
                wpDim = wp.optInt("dim", wpDim);
                wpApplySystem = wp.optBoolean("applySystem", wpApplySystem);
                wpAutoDaily = wp.optBoolean("autoDaily", wpAutoDaily);
                wpRotateMin = wp.optInt("rotateMinutes", wpRotateMin);
                wpRotatePool = wp.optInt("rotatePool", wpRotatePool);
            }

            JSONArray arr = o.optJSONArray("themes");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    String nm = e.optString("name", "配色" + (i + 1));
                    String col = e.optString("color", null);
                    if (col != null && !col.isEmpty()) {
                        themes.add(new Theme(nm, parseColor(col)));
                    } else {
                        String top = e.optString("top", null);
                        String bottom = e.optString("bottom", null);
                        if (top != null && bottom != null) {
                            themes.add(new Theme(nm, parseColor(top), parseColor(bottom)));
                        } else if (top != null) {
                            themes.add(new Theme(nm, parseColor(top)));
                        } else if (bottom != null) {
                            themes.add(new Theme(nm, parseColor(bottom)));
                        }
                    }
                }
            }

            // 数值合法性兜底
            favColumns = clamp(favColumns, 1, 12);
            allColumns = clamp(allColumns, 1, 12);
            iconSizeDp = clamp(iconSizeDp, 24, 400);
            clockSizeSp = clamp(clockSizeSp, 8, 120);
            dateSizeSp = clamp(dateSizeSp, 8, 80);
            tabSizeSp = clamp(tabSizeSp, 8, 80);
            nameSizeSp = clamp(nameSizeSp, 8, 80);
            favRowSpacingDp = clamp(favRowSpacingDp, 0, 200);
            allRowSpacingDp = clamp(allRowSpacingDp, 0, 200);
            favColSpacingDp = clamp(favColSpacingDp, 0, 200);
            allColSpacingDp = clamp(allColSpacingDp, 0, 200);
            iconPadBottomDp = clamp(iconPadBottomDp, 0, 200);
            iconSizeRatio = clampF(iconSizeRatio, 0.4f, 1.0f);
            iconCornerRatio = clampF(iconCornerRatio, 0f, 0.5f);
            iconStrokeDp = clampF(iconStrokeDp, 0f, 8f);
            namePadTopDp = clamp(namePadTopDp, 0, 120);
            weatherRefreshMin = clamp(weatherRefreshMin, 5, 720);
            wpDim = clamp(wpDim, 0, 80);
            wpRotateMin = clamp(wpRotateMin, 0, 1440);
            wpRotatePool = clamp(wpRotatePool, 0, 365);
            ovMarginDp = clamp(ovMarginDp, 0, 300);
            ovClockSizeSp = clamp(ovClockSizeSp, 8, 120);
            ovDateSizeSp = clamp(ovDateSizeSp, 6, 80);
            ovBgAlpha = clamp(ovBgAlpha, 0, 100);

            if (themes.isEmpty()) {
                // 有 grid/text 但 themes 空 → 至少给回默认配色
                parseThemesOnly(DEFAULT_JSON);
                lastError = "配置里没有 themes，已用内置配色";
            }
        } catch (Throwable ex) {
            Log.w(TAG, "config parse failed, fallback: " + ex);
            lastError = "配置文件格式有误，已用内置默认（" + ex.getClass().getSimpleName() + "）";
            resetToDefault();
        }
    }

    private void parseThemesOnly(String json) {
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("themes");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                String nm = e.optString("name", "配色" + (i + 1));
                String col = e.optString("color", null);
                if (col != null && !col.isEmpty()) {
                    themes.add(new Theme(nm, parseColor(col)));
                } else {
                    String top = e.optString("top", null);
                    String bottom = e.optString("bottom", null);
                    if (top != null && bottom != null) {
                        themes.add(new Theme(nm, parseColor(top), parseColor(bottom)));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void resetToDefault() {
        themes.clear();
        ThemeStore d = new ThemeStore();
        d.parse(DEFAULT_JSON);
        themes.addAll(d.themes);
    }

    /**
     * 把当前内存里的配置序列化成 JSON 文本（供图形化编辑器写回文件）。
     * 输出格式与 {@link #DEFAULT_JSON} 完全一致：纯色写 {@code color}，
     * 渐变写 {@code top}（深）+ {@code bottom}（浅）。
     */
    String toJson() {
        try {
            JSONObject o = new JSONObject();

            JSONObject tabs = new JSONObject();
            tabs.put("home", tabHome);
            tabs.put("all", tabAll);
            tabs.put("bg", tabBg);
            o.put("tabs", tabs);

            JSONObject g = new JSONObject();
            g.put("favColumns", favColumns);
            g.put("allColumns", allColumns);
            g.put("favRowSpacingDp", favRowSpacingDp);
            g.put("allRowSpacingDp", allRowSpacingDp);
            g.put("favColSpacingDp", favColSpacingDp);
            g.put("allColSpacingDp", allColSpacingDp);
            g.put("iconSizeDp", iconSizeDp);
            g.put("iconPadBottomDp", iconPadBottomDp);
            o.put("grid", g);

            JSONObject ic = new JSONObject();
            ic.put("sizeRatio", (double) iconSizeRatio);
            ic.put("cornerRatio", (double) iconCornerRatio);
            ic.put("strokeDp", (double) iconStrokeDp);
            ic.put("strokeColor", String.format("#%08X", iconStrokeColor));
            o.put("icon", ic);

            JSONObject t = new JSONObject();
            t.put("clockSizeSp", clockSizeSp);
            t.put("dateSizeSp", dateSizeSp);
            t.put("tabSizeSp", tabSizeSp);
            t.put("nameSizeSp", nameSizeSp);
            t.put("namePadTopDp", namePadTopDp);
            o.put("text", t);

            JSONObject tb = new JSONObject();
            tb.put("showLunar", showLunar);
            tb.put("showWeather", showWeather);
            tb.put("city", weatherCity);
            tb.put("locationKey", weatherKey);
            tb.put("refreshMinutes", weatherRefreshMin);
            o.put("topbar", tb);

            JSONObject ov = new JSONObject();
            ov.put("enabled", ovEnabled);
            ov.put("marginDp", ovMarginDp);
            ov.put("clockSizeSp", ovClockSizeSp);
            ov.put("dateSizeSp", ovDateSizeSp);
            ov.put("bgAlpha", ovBgAlpha);
            ov.put("shadow", ovShadow);
            o.put("overlay", ov);

            JSONObject tl = new JSONObject();
            tl.put("enabled", toolsEnabled);
            tl.put("marginDp", toolsMarginDp);
            tl.put("rebootCmd", toolsRebootCmd);
            tl.put("offCmd", toolsOffCmd);
            o.put("tools", tl);

            JSONObject wp = new JSONObject();
            wp.put("mode", wpMode);
            wp.put("bingMkt", bingMkt);
            wp.put("bingUhd", bingUhd);
            wp.put("dim", wpDim);
            wp.put("applySystem", wpApplySystem);
            wp.put("autoDaily", wpAutoDaily);
            wp.put("rotateMinutes", wpRotateMin);
            wp.put("rotatePool", wpRotatePool);
            o.put("wallpaper", wp);

            JSONArray arr = new JSONArray();
            for (Theme th : themes) {
                JSONObject e = new JSONObject();
                e.put("name", th.name);
                if (th.isGradient()) {
                    e.put("top", toHex6(th.top));
                    e.put("bottom", toHex6(th.bottom));
                } else {
                    e.put("color", toHex6(th.bottom));
                }
                arr.put(e);
            }
            o.put("themes", arr);

            return o.toString(2);
        } catch (Throwable ex) {
            Log.w(TAG, "toJson failed: " + ex);
            return DEFAULT_JSON;
        }
    }

    /** 把当前配置写回文件（供界面保存按钮调用） */
    boolean save(Context ctx, String raw) {
        // 保留原文件里的 _说明 / _参数名 注释键（App 自己 toJson 时不带它们）
        final String jsonText = keepDocs(ctx, raw);
        try {
            writeFile(file(ctx), jsonText);
            // 写成功后立刻重新解析，让界面无副作用地拿到新值
            parse(jsonText);
            lastError = null;
            return true;
        } catch (Throwable t) {
            // 本机 fuse 对 App 进程写 /sdcard 返回 EPERM（scoped storage 判定），
            // 但 shell 身份可以写，所以绕道本地 adb(5555) 用 cat 落盘。
            // 调用方可能就在主线程（换壁纸、切主题等），直接做网络会抛 NetworkOnMainThreadException，
            // 因此主线程场景下丢到子线程执行并同步等结果。
            boolean ok;
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                // 兜底：主线程最多等 1.5 秒。saveViaAdb 内部要轮询校验（最长 4 秒），
                // 以前这里 join(8000)，等于在主线程上等满 8 秒 —— 必 ANR。
                // 调用方现在都已改成后台保存，这条路径只是防漏网。
                final boolean[] r = {false};
                final boolean[] done = {false};
                final Object lock = new Object();
                Thread th = new Thread(() -> {
                    boolean v = saveViaAdb(ctx, jsonText);
                    synchronized (lock) {
                        r[0] = v;
                        done[0] = true;
                        lock.notifyAll();
                    }
                }, "cfg-save-adb");
                th.start();
                synchronized (lock) {
                    try {
                        if (!done[0]) lock.wait(1500);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (!done[0]) {
                    // 还没落完盘：内存里先生效，后台线程继续写完
                    parse(jsonText);
                    lastError = null;
                    Log.i(TAG, "save dispatched to background (adb slow)");
                    return true;
                }
                ok = r[0];
            } else {
                ok = saveViaAdb(ctx, jsonText);
            }
            if (ok) {
                parse(jsonText);
                lastError = null;
                Log.i(TAG, "saved via adb shell fallback");
                return true;
            }
            lastError = "保存失败：" + t;
            return false;
        }
    }

    /**
     * 把磁盘上原配置里的 {@code _说明} / {@code _参数名} 注释键合并进新内容，
     * 这样 App 自己回写（换壁纸、切主题）也不会把用户看到的说明冲掉。
     */
    private String keepDocs(Context ctx, String jsonText) {
        try {
            String old = readFile(file(ctx));
            if (old == null || !old.contains("_说明")) {
                return jsonText;
            }
            JSONObject oldO = new JSONObject(old);
            JSONObject newO = new JSONObject(jsonText);
            java.util.Iterator<String> segs = oldO.keys();
            while (segs.hasNext()) {
                String seg = segs.next();
                JSONObject os = oldO.optJSONObject(seg);
                JSONObject ns = newO.optJSONObject(seg);
                if (os == null || ns == null) {
                    continue;
                }
                java.util.Iterator<String> ks = os.keys();
                while (ks.hasNext()) {
                    String k = ks.next();
                    if (k.startsWith("_") && !ns.has(k)) {
                        ns.put(k, os.opt(k));
                    }
                }
            }
            return newO.toString(2);
        } catch (Throwable t) {
            return jsonText;
        }
    }

    /** adb 兜底写盘：直接覆盖目标文件，再单独 chmod 666，最后校验字节数一致。 */
    private boolean saveViaAdb(Context ctx, String jsonText) {
        try {
            String p = file(ctx).getAbsolutePath();
            // 不用 `cat > p.tmp && chmod && mv`：CLSE 后连接立即关闭，
            // 后续的 chmod/mv 会被 SIGHUP 打断，文件永远换不过去。
            boolean ok = AdbShell.execWithInput("cat > " + p, jsonText);
            if (!ok) {
                return false;
            }
            // 单独一次命令补权限：shell 写出来的是 600，必须放开让 App 能读、下次还能写
            AdbShell.exec("chmod 666 " + p);
            int expect = jsonText.getBytes("UTF-8").length;
            for (int i = 0; i < 16; i++) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
                String back = readFile(file(ctx));
                if (back != null && Math.abs(back.getBytes("UTF-8").length - expect) <= 4) {
                    return true;
                }
            }
            Log.w(TAG, "saveViaAdb verify failed, expect=" + expect);
        } catch (Throwable t) {
            Log.w(TAG, "saveViaAdb failed: " + t);
        }
        return false;
    }

    /** 把磁盘上当前的内容原样读出来（供编辑框显示） */
    static String readRaw(Context ctx) {
        try {
            File f = file(ctx);
            String s = readFile(f);
            if (s == null || s.trim().isEmpty()) {
                // 磁盘还没文件 → 至少把默认内容显示出来，方便用户照着改
                writeFile(f, DEFAULT_JSON);
                return DEFAULT_JSON;
            }
            return s;
        } catch (Throwable t) {
            return DEFAULT_JSON;
        }
    }

    // ==================== 工具 ====================

    /** 解析 #RRGGBB / #AARRGGBB；失败返回不透明黑 */
    static int parseColor(String s) {
        try {
            String v = s.trim();
            if (!v.startsWith("#")) v = "#" + v;
            long c = Long.parseLong(v.substring(1), 16);
            if (v.length() == 7) {          // #RRGGBB
                return (int) (0xFF000000L | c);
            } else if (v.length() == 9) {   // #AARRGGBB
                return (int) c;
            }
        } catch (Throwable ignored) {
        }
        return 0xFF000000;
    }

    /** 反解成 #AARRGGBB 文本（供保存时回写） */
    static String toHex(int c) {
        return String.format("#%08X", c);
    }

    /**
     * 反解成 {@code #RRGGBB}（不带透明度）——配置文件里更好读、更好手改。
     * 颜色确实带透明度时才退回 {@code #AARRGGBB}。
     */
    static String toHex6(int c) {
        if (((c >>> 24) & 0xFF) == 0xFF) {
            return String.format("#%06X", c & 0xFFFFFF);
        }
        return String.format("#%08X", c);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String readFile(File f) throws Exception {
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), Charset.forName("UTF-8")))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        File d = f.getParentFile();
        if (d != null && !d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), Charset.forName("UTF-8"))) {
            w.write(content);
        }
    }
}
