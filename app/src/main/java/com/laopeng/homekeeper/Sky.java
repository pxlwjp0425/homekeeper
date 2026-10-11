package com.laopeng.homekeeper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 顶栏天气：取「当前天气 + 气温」并缓存。
 *
 * <p>数据源 <b>open-meteo</b>（免 key、纯 HTTPS，不涉及明文流量）：<br>
 * {@code https://api.open-meteo.com/v1/forecast?...&current=temperature_2m,weather_code}</p>
 *
 * <p>地点解析顺序：</p>
 * <ol>
 *   <li>配置里直接写坐标 {@code "24.72,118.15"}</li>
 *   <li>命中缓存（同一配置串且未过期）</li>
 *   <li>{@code ""} / {@code "auto"} → 按公网 IP 定位（ipinfo.io，<b>仅当出口 IP 与真实位置一致时才准</b>）</li>
 *   <li>城市名（如 {@code 厦门同安}）→ <b>先查内置城市表</b>（覆盖福建全省 + 厦门各区 + 全国主要城市）</li>
 *   <li>内置表没有 → 才走 open-meteo 地理编码（对中文地名支持很差，仅作兜底）</li>
 *   <li>以上全失败 → 用上次缓存的坐标；再没有就用内置兜底坐标</li>
 * </ol>
 *
 * <p>所有网络动作都在后台线程；失败只记日志、保留上次结果，<b>绝不弹错也不阻塞桌面</b>。
 * 结果落 SharedPreferences，盒子重启后立刻有数，不必等联网。</p>
 */
final class Sky {

    private static final String TAG = "HKDBG";
    private static final String PREF = "sky";

    private static final String K_DESC = "desc";
    private static final String K_TEMP = "temp";
    private static final String K_TS = "ts";
    private static final String K_CITY = "city";
    private static final String K_LAT = "lat";
    private static final String K_LON = "lon";
    private static final String K_GEO_KEY = "geoKey";
    private static final String K_GEO_TS = "geoTs";

    /** 定位失败时的兜底坐标（福建厦门同安） */
    private static final double FALLBACK_LAT = 24.7235;
    private static final double FALLBACK_LON = 118.1530;

    /**
     * 内置地名 → 坐标表。
     *
     * <p>为什么要有它：open-meteo 的地理编码对<b>中文</b>地名几乎不可用——
     * 「厦门」查不到任何结果，「同安」命中的是福州/广东/四川的同名镇。
     * 所以常见地名一律先在此表里精确命中，查不到才退回网络地理编码。</p>
     *
     * <p>键名不带「市/区/县/省」后缀，匹配时会先剥掉这些后缀再比对。</p>
     */
    private static final Map<String, double[]> CITIES = new HashMap<>();

    static {
        // ---- 福建：厦门各区（区名可单独填，也可写「厦门同安」） ----
        put("厦门", 24.4798, 118.0819);
        put("思明", 24.4456, 118.0824);
        put("湖里", 24.5129, 118.1466);
        put("集美", 24.5756, 118.0979);
        put("海沧", 24.4848, 118.0328);
        put("同安", 24.7235, 118.1530);
        put("翔安", 24.6171, 118.2476);
        // ---- 福建：其余地市 ----
        put("福州", 26.0745, 119.2965);
        put("莆田", 25.4310, 119.0078);
        put("三明", 26.2634, 117.6389);
        put("泉州", 24.8741, 118.6757);
        put("漳州", 24.5130, 117.6471);
        put("南平", 26.6435, 118.1783);
        put("龙岩", 25.0755, 117.0173);
        put("宁德", 26.6657, 119.5482);
        put("平潭", 25.5036, 119.7884);
        // ---- 全国主要城市 ----
        put("北京", 39.9042, 116.4074);
        put("上海", 31.2304, 121.4737);
        put("广州", 23.1291, 113.2644);
        put("深圳", 22.5431, 114.0579);
        put("杭州", 30.2741, 120.1551);
        put("宁波", 29.8683, 121.5440);
        put("南京", 32.0603, 118.7969);
        put("苏州", 31.2989, 120.5853);
        put("无锡", 31.4912, 120.3119);
        put("温州", 27.9938, 120.6994);
        put("合肥", 31.8206, 117.2272);
        put("南昌", 28.6820, 115.8579);
        put("济南", 36.6512, 117.1201);
        put("青岛", 36.0671, 120.3826);
        put("郑州", 34.7466, 113.6254);
        put("武汉", 30.5928, 114.3055);
        put("长沙", 28.2282, 112.9388);
        put("成都", 30.5728, 104.0668);
        put("重庆", 29.5630, 106.5516);
        put("西安", 34.3416, 108.9398);
        put("昆明", 24.8801, 102.8329);
        put("贵阳", 26.6470, 106.6302);
        put("南宁", 22.8170, 108.3665);
        put("海口", 20.0444, 110.1999);
        put("三亚", 18.2528, 109.5119);
        put("天津", 39.3434, 117.3616);
        put("石家庄", 38.0428, 114.5149);
        put("太原", 37.8706, 112.5489);
        put("沈阳", 41.8057, 123.4315);
        put("大连", 38.9140, 121.6147);
        put("长春", 43.8171, 125.3235);
        put("哈尔滨", 45.8038, 126.5350);
        put("兰州", 36.0611, 103.8343);
        put("西宁", 36.6171, 101.7782);
        put("银川", 38.4872, 106.2309);
        put("乌鲁木齐", 43.8256, 87.6168);
        put("拉萨", 29.6500, 91.1000);
        put("呼和浩特", 40.8414, 111.7519);
        put("东莞", 23.0207, 113.7518);
        put("佛山", 23.0218, 113.1219);
        put("珠海", 22.2710, 113.5767);
        put("汕头", 23.3535, 116.6820);
        put("香港", 22.3193, 114.1694);
        put("澳门", 22.1987, 113.5439);
        put("台北", 25.0330, 121.5654);
    }

    private static void put(String key, double lat, double lon) {
        CITIES.put(key, new double[]{lat, lon});
    }

    /** 网络超时（毫秒） */
    private static final int TIMEOUT = 8000;

    /** 顶栏要显示的那一段，如 {@code 晴 21℃}；空串 = 还没有数据 */
    volatile String summary = "";
    /** 当前数据对应的地点名（仅备查，不上顶栏） */
    volatile String city = "";
    /** 上次成功刷新的时间戳 */
    volatile long ts = 0L;

    /** 上次成功定位时用的配置串（用来判断用户是否改了 city） */
    volatile String geoCfg = "";

    private volatile boolean busy = false;

    private Sky() {
    }

    /** 从缓存读出上次的结果，界面可立即显示（不必等网络） */
    static Sky load(Context ctx) {
        Sky s = new Sky();
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            s.ts = p.getLong(K_TS, 0L);
            s.city = p.getString(K_CITY, "");
            s.geoCfg = p.getString(K_GEO_KEY, "");
            s.summary = compose(p.getString(K_DESC, ""), p.getString(K_TEMP, ""));
        } catch (Throwable t) {
            Log.w(TAG, "sky load failed: " + t);
        }
        return s;
    }

    /** 数据是否已过期（还没数据也算过期） */
    boolean stale(int refreshMinutes) {
        return stale(refreshMinutes, geoCfg);
    }

    /**
     * 数据是否已过期。<b>配置里的地点串换了也算过期</b> ——
     * 否则用户改了 {@code city} 后，因缓存还在 TTL 内而永不重算，界面就一直显示旧地点。
     */
    boolean stale(int refreshMinutes, String cityCfg) {
        if (summary.isEmpty()) {
            return true;
        }
        String c = cityCfg == null ? "" : cityCfg.trim();
        if (!c.equals(geoCfg)) {
            return true;
        }
        long ttl = Math.max(5, refreshMinutes) * 60000L;
        return System.currentTimeMillis() - ts > ttl;
    }

    /**
     * 后台刷新；结束后在主线程回调 {@code onDone}（无论成功失败都会回调）。
     * 已有请求在跑时直接返回，避免 tick 每 15 秒叠一次请求。
     */
    void refreshAsync(final Context ctx, final String cityCfg, final Runnable onDone) {
        if (busy) {
            return;
        }
        busy = true;
        Thread th = new Thread(() -> {
            try {
                double[] ll = resolve(ctx, cityCfg);
                if (ll != null) {
                    fetch(ctx, ll[0], ll[1]);
                }
            } catch (Throwable t) {
                Log.w(TAG, "sky refresh failed: " + t);
            } finally {
                busy = false;
                if (onDone != null) {
                    new Handler(Looper.getMainLooper()).post(onDone);
                }
            }
        }, "sky-refresh");
        th.setDaemon(true);
        th.start();
    }

    // ==================== 地点 ====================

    /** 解析出经纬度；拿不到返回 null */
    private double[] resolve(Context ctx, String cityCfg) {
        String cfg = cityCfg == null ? "" : cityCfg.trim();
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        geoCfg = cfg;

        double[] direct = parseLatLon(cfg);
        if (direct != null) {
            // 直接写坐标：稳定，不必反复重算；配置串变了才落一次盘
            if (!cfg.equals(p.getString(K_GEO_KEY, "\u0000"))) {
                p.edit()
                        .putString(K_GEO_KEY, cfg)
                        .putString(K_CITY, cfg)
                        .putLong(K_GEO_TS, System.currentTimeMillis())
                        .putString(K_LAT, String.valueOf(direct[0]))
                        .putString(K_LON, String.valueOf(direct[1]))
                        .apply();
            }
            return direct;
        }

        boolean auto = cfg.isEmpty() || "auto".equalsIgnoreCase(cfg);
        // 自动定位跟着 IP 走，24 小时就重算一次；手填的城市名很稳定，30 天
        long ttl = auto ? 24L * 3600_000L : 30L * 24 * 3600_000L;
        long age = System.currentTimeMillis() - p.getLong(K_GEO_TS, 0L);
        if (cfg.equals(p.getString(K_GEO_KEY, "\u0000")) && age >= 0 && age < ttl) {
            double la = num(p, K_LAT);
            double lo = num(p, K_LON);
            if (!Double.isNaN(la) && !Double.isNaN(lo)) {
                return new double[]{la, lo};
            }
        }

        double[] r = null;
        String name = null;
        if (auto) {
            try {
                JSONObject o = new JSONObject(http("https://ipinfo.io/json"));
                r = parseLatLon(o.optString("loc", ""));
                name = o.optString("city", "");
                if (r != null && name != null && !name.isEmpty()) {
                    String zh = geocodeName(name);
                    if (zh != null && !zh.isEmpty()) {
                        name = zh;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "ip geo failed: " + t);
            }
        } else {
            String[] hit = lookupCity(cfg);
            if (hit != null) {
                r = new double[]{Double.parseDouble(hit[1]), Double.parseDouble(hit[2])};
                name = hit[0];
                Log.i(TAG, "sky geo by builtin table: " + cfg + " -> " + name);
            } else {
                try {
                    double[] g = geocode(cfg);
                    if (g != null) {
                        r = g;
                        name = cfg;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "geocode failed: " + t);
                }
            }
        }

        if (r == null) {
            // 解析失败：退回上次缓存的坐标，最后才是内置兜底
            double la = num(p, K_LAT);
            double lo = num(p, K_LON);
            if (!Double.isNaN(la) && !Double.isNaN(lo)) {
                return new double[]{la, lo};
            }
            Log.w(TAG, "sky geo fallback to default coords");
            return new double[]{FALLBACK_LAT, FALLBACK_LON};
        }

        SharedPreferences.Editor e = p.edit();
        e.putString(K_GEO_KEY, cfg);
        e.putLong(K_GEO_TS, System.currentTimeMillis());
        e.putString(K_LAT, String.valueOf(r[0]));
        e.putString(K_LON, String.valueOf(r[1]));
        if (name != null && !name.isEmpty()) {
            e.putString(K_CITY, name);
        }
        e.apply();
        return r;
    }

    /** "26.06,119.31" → {26.06, 119.31}；不是坐标则返回 null */
    private static double[] parseLatLon(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        // 全角逗号换成半角，空格全部去掉（"26.06, 119.31" 也要能认）
        String v = s.trim().replace('，', ',').replaceAll("\\s+", "");
        String[] parts = v.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double la = Double.parseDouble(parts[0]);
            double lo = Double.parseDouble(parts[1]);
            if (la < -90 || la > 90 || lo < -180 || lo > 180) {
                return null;
            }
            return new double[]{la, lo};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 内置城市表查询。返回 {@code {规范名, lat, lon}}，查不到返回 null。
     *
     * <p>匹配顺序（都先剥掉 市/区/县/省 后缀）：<br>
     * ① 完全相等 → ② 以某个键名<b>结尾</b>（取最长，{@code 厦门同安}→同安）→
     * ③ <b>包含</b>某个键名（取最长）。</p>
     */
    private static String[] lookupCity(String raw) {
        String n = strip(raw);
        if (n.isEmpty()) {
            return null;
        }
        double[] d = CITIES.get(n);
        if (d != null) {
            return new String[]{n, num(d[0]), num(d[1])};
        }
        String bestKey = null;
        for (String k : CITIES.keySet()) {
            if (n.endsWith(k) && (bestKey == null || k.length() > bestKey.length())) {
                bestKey = k;
            }
        }
        if (bestKey == null) {
            for (String k : CITIES.keySet()) {
                if (n.contains(k) && (bestKey == null || k.length() > bestKey.length())) {
                    bestKey = k;
                }
            }
        }
        if (bestKey == null) {
            return null;
        }
        d = CITIES.get(bestKey);
        return new String[]{bestKey, num(d[0]), num(d[1])};
    }

    /** 去掉空白与「市/区/县/省」后缀，便于和内置表比对 */
    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("[\\s]+", "")
                .replace("特别行政区", "")
                .replaceAll("[市区县省]", "");
    }

    /** 城市名 → 坐标（open-meteo 地理编码，中文名直接用） */
    private static double[] geocode(String name) throws Exception {
        String u = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name="
                + URLEncoder.encode(name, "UTF-8");
        JSONArray arr = new JSONObject(http(u)).optJSONArray("results");
        if (arr == null || arr.length() == 0) {
            return null;
        }
        JSONObject o = arr.optJSONObject(0);
        if (o == null) {
            return null;
        }
        double la = o.optDouble("latitude", Double.NaN);
        double lo = o.optDouble("longitude", Double.NaN);
        if (Double.isNaN(la) || Double.isNaN(lo)) {
            return null;
        }
        return new double[]{la, lo};
    }

    /** 城市名 → 中文名（仅用于把 ipinfo 的英文城市名换成中文） */
    private static String geocodeName(String name) {
        try {
            String u = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name="
                    + URLEncoder.encode(name, "UTF-8");
            JSONArray arr = new JSONObject(http(u)).optJSONArray("results");
            if (arr != null && arr.length() > 0) {
                return arr.optJSONObject(0).optString("name", null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== 天气 ====================

    /** 拉当前天气并写缓存 */
    private void fetch(Context ctx, double lat, double lon) throws Exception {
        String u = "https://api.open-meteo.com/v1/forecast?latitude=" + num(lat)
                + "&longitude=" + num(lon)
                + "&current=temperature_2m,weather_code"
                + "&timezone=Asia%2FShanghai";
        JSONObject o = new JSONObject(http(u));
        JSONObject cur = o.optJSONObject("current");
        if (cur == null) {
            return;
        }
        int code = cur.optInt("weather_code", -1);
        double t = cur.optDouble("temperature_2m", Double.NaN);
        if (Double.isNaN(t)) {
            return;
        }
        String desc = wmo(code);
        String temp = Math.round(t) + "℃";
        summary = compose(desc, temp);

        SharedPreferences.Editor e = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        e.putString(K_DESC, desc);
        e.putString(K_TEMP, temp);
        e.putLong(K_TS, System.currentTimeMillis());
        e.apply();
        ts = System.currentTimeMillis();
        Log.i(TAG, "sky ok: " + summary + " @" + num(lat) + "," + num(lon));
    }

    /** 拼顶栏那一段；温度为空时只用描述，都没就空串 */
    private static String compose(String desc, String temp) {
        String d = desc == null ? "" : desc.trim();
        String t = temp == null ? "" : temp.trim();
        if (d.isEmpty()) {
            return t;
        }
        return t.isEmpty() ? d : (d + " " + t);
    }

    /** WMO weather code → 中文（够用即可） */
    private static String wmo(int c) {
        switch (c) {
            case 0:
                return "晴";
            case 1:
                return "晴间多云";
            case 2:
                return "多云";
            case 3:
                return "阴";
            case 45:
            case 48:
                return "雾";
            case 51:
            case 53:
            case 55:
                return "毛毛雨";
            case 56:
            case 57:
            case 66:
            case 67:
                return "冻雨";
            case 61:
                return "小雨";
            case 63:
                return "中雨";
            case 65:
                return "大雨";
            case 71:
                return "小雪";
            case 73:
                return "中雪";
            case 75:
                return "大雪";
            case 77:
                return "雪粒";
            case 80:
                return "阵雨";
            case 81:
                return "强阵雨";
            case 82:
                return "暴雨";
            case 85:
            case 86:
                return "阵雪";
            case 95:
                return "雷阵雨";
            case 96:
            case 99:
                return "雷暴冰雹";
            default:
                return "";
        }
    }

    // ==================== 工具 ====================

    private static String num(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static double num(SharedPreferences p, String key) {
        try {
            return Double.parseDouble(p.getString(key, ""));
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("User-Agent", "homekeeper/4.3");
            int rc = c.getResponseCode();
            if (rc != 200) {
                throw new IllegalStateException("HTTP " + rc + " " + url);
            }
            try (InputStream in = c.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } finally {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }
}
