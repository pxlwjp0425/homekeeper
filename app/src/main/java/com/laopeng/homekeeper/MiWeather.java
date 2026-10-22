package com.laopeng.homekeeper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.zip.GZIPInputStream;

/**
 * 小米天气接口客户端（{@code weatherapi.market.xiaomi.com}）——<b>与「天气 TV」App 同源同参数</b>，
 * 这样桌面顶栏显示的就是天气 App 里那份数据（温度、天气码、更新时间完全一致）。
 *
 * <p>为什么不用原来的 open-meteo：两个源实测会打架 ——
 * 2026-10-17 10:45 同安：小米「晴 30℃」，open-meteo「多云 32℃」。</p>
 *
 * <p>接口约束：<b>{@code latitude}/{@code longitude} 必填且要和 {@code locationKey} 对得上</b>
 * （只传 key 会返回 {@code errCode:5 Param latitude error}）→ 换城市要同时在配置里改这三个值。</p>
 *
 * <p>只取顶栏要用的几个字段（实时温度/天气码、今日高低温、发布时间），
 * 完整数据（逐小时/15 日/AQI/生活指数）仍由天气 App 负责。</p>
 */
final class MiWeather {

    private static final String HOST = "https://weatherapi.market.xiaomi.com";
    private static final String APP_KEY = "weather20151024";
    private static final String SIGN = "zUFJoAR2ZVrVf0E1";
    private static final String ROM = "MIUI14";
    private static final String APP_VER = "15";
    private static final String UA = "Dalvik/2.1.0 (Linux; U; Android 11; BA001 Build/RQ3A.211001.001)";

    /** 默认地点：福建省厦门市同安区（小米 locationKey + 匹配的经纬度）。 */
    static final String KEY_TONGAN = "weathercn:101230202";
    static final double TONGAN_LAT = 24.736;
    static final double TONGAN_LON = 118.144;

    /** 一次请求的结果，字段都带「有没有值」的判断依据。 */
    static final class R {
        boolean ok;
        int temp = Integer.MIN_VALUE;
        int tMax = Integer.MIN_VALUE;
        int tMin = Integer.MIN_VALUE;
        int code = -1;
        String desc = "";
        String pub = "";
        String source = "";
    }

    private MiWeather() {}

    /** 传进来的「地点串」是不是小米 locationKey。 */
    static boolean looks(String s) {
        return s != null && s.startsWith("weathercn:");
    }

    /**
     * 拉同安的实时天气。
     *
     * @param key 小米 locationKey（如 {@code weathercn:101230202}）
     * @param lat 该 key 对应的纬度；传 NaN 用同安的
     * @param lon 该 key 对应的经度；传 NaN 用同安的
     */
    static R fetch(String key, double lat, double lon) throws Exception {
        if (Double.isNaN(lat)) lat = TONGAN_LAT;
        if (Double.isNaN(lon)) lon = TONGAN_LON;
        String url = HOST + "/wtr-v3/weather/all"
                + "?latitude=" + lat + "&longitude=" + lon + "&isLocated=false"
                + "&locationKey=" + URLEncoder.encode(key, "UTF-8")
                + "&days=1"
                + "&appKey=" + APP_KEY + "&sign=" + SIGN
                + "&romVersion=" + ROM + "&appVersion=" + APP_VER
                + "&locale=zh_CN&isGlobal=false";
        R r = parse(http(url));
        if (!r.ok) throw new Exception("小米天气解析失败");
        return r;
    }

    // ---------------------------------------------------------------- 解析

    static R parse(String json) {
        R r = new R();
        try {
            JSONObject d = new JSONObject(json);
            if (d.has("errCode")) {
                throw new Exception("errCode=" + d.optInt("errCode") + " " + d.optString("errDesc"));
            }

            JSONObject cur = d.optJSONObject("current");
            if (cur != null) {
                JSONObject t = cur.optJSONObject("temperature");
                r.temp = asInt(t == null ? null : t.optString("value", ""));
                r.code = asInt(cur.optString("weather", ""));
                r.pub = cur.optString("pubTime", "");
            }

            JSONObject fd = d.optJSONObject("forecastDaily");
            if (fd != null) {
                JSONArray ta = arrOf(fd, "temperature");
                JSONObject t0 = ta == null ? null : ta.optJSONObject(0);
                if (t0 != null) {
                    r.tMax = asInt(t0.optString("from", ""));
                    r.tMin = asInt(t0.optString("to", ""));
                }
                // 白天/夜间两段天气码，顶栏用白天的
                JSONArray wa = arrOf(fd, "weather");
                JSONObject w0 = wa == null ? null : wa.optJSONObject(0);
                if (w0 != null && r.code < 0) r.code = asInt(w0.optString("from", ""));
            }

            r.desc = name(r.code);
            r.source = d.optString("url", "");
            r.ok = r.temp != Integer.MIN_VALUE;
            return r;
        } catch (Exception e) {
            r.ok = false;
            r.desc = "";
            return r;
        }
    }

    private static JSONArray arrOf(JSONObject o, String k) {
        JSONObject x = o.optJSONObject(k);
        return x == null ? null : x.optJSONArray("value");
    }

    private static int asInt(String s) {
        if (s == null || s.isEmpty()) return Integer.MIN_VALUE;
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * 天气码 → 中文描述。<b>必须与天气 App 的 {@code Wx.name()} 完全一致</b>，
     * 否则桌面和 App 会对同一个码显示不同文案。
     */
    static String name(int c) {
        switch (c) {
            case 0: return "晴";
            case 1: return "多云";
            case 2: return "阴";
            case 3: return "阵雨";
            case 4: return "雷阵雨";
            case 5: return "雷阵雨伴冰雹";
            case 6: return "雨夹雪";
            case 7: return "小雨";
            case 8: return "中雨";
            case 9: return "大雨";
            case 10: return "暴雨";
            case 11: return "大暴雨";
            case 12: return "特大暴雨";
            case 13: return "阵雪";
            case 14: return "小雪";
            case 15: return "中雪";
            case 16: return "大雪";
            case 17: return "暴雪";
            case 18: return "雾";
            case 19: return "冻雨";
            case 20: return "沙尘暴";
            case 21: return "小到中雨";
            case 22: return "中到大雨";
            case 23: return "大到暴雨";
            case 24: return "暴雨到大暴雨";
            case 25: return "大暴雨到特大暴雨";
            case 26: return "小到中雪";
            case 27: return "中到大雪";
            case 28: return "大到暴雪";
            case 29: return "浮尘";
            case 30: return "扬沙";
            case 31: return "强沙尘暴";
            case 32: return "浓雾";
            case 33: return "霾";
            case 34: return "雷阵雨伴有冰雹";
            default: return "";
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static String http(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "gzip");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            InputStream raw = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
            if (raw == null) throw new Exception("HTTP " + code);
            String enc = c.getContentEncoding();
            InputStream in = (enc != null && enc.toLowerCase().contains("gzip"))
                    ? new GZIPInputStream(raw) : raw;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String s = new String(bos.toByteArray(), "UTF-8");
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code + " " + s);
            return s;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
