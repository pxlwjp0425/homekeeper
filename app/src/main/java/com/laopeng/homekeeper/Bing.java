package com.laopeng.homekeeper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Bing「每日一图」壁纸：取列表 → 下载 → 本地缓存 → 居中裁剪的 Drawable。
 *
 * <p><b>为什么用 Bing</b>：免 key、纯 HTTPS，国内可直连（{@code www.bing.com} 与 {@code cn.bing.com} 均返回 200），
 * 每天一张高质量横图，尺寸正好 1920×1080，铺在 1080p 电视上不做缩放就是点对点。</p>
 *
 * <p><b>内存提示</b>：接口返回的 {@code url} 是 1920×1080（约 340KB），另有 {@code _UHD.jpg} 是 4K（约 3.5MB，
 * 解码后 33MB）。盒子内存紧张，默认只用 1080p；真要 4K 由配置开关控制，解码时也会降采样。</p>
 *
 * <p>纯 HTTP/JSON/位图处理，不依赖 Activity，可在后台线程调用。</p>
 */
final class Bing {

    private static final String TAG = "HKDBG";
    /** Bing 每日一图接口（免 key） */
    private static final String API =
            "https://www.bing.com/HPImageArchive.aspx?format=js&n=%d&idx=%d&mkt=%s";
    private static final String HOST = "https://www.bing.com";
    /** 单次请求最多能拿几张（Bing 侧硬上限） */
    private static final int PER_PAGE = 8;
    /** 翻页最多能凑多少张（{@code idx=16} 会重复返回 idx=8 的内容，故封顶 16） */
    private static final int MAX_TOTAL = 16;
    /** 本地缓存保留的张数上限；{@code <= 0} 表示<b>不限制</b>（只受磁盘空间约束） */
    public static final int KEEP_UNLIMITED = 0;
    /** 小于这个字节数视为下载不完整 */
    private static final long MIN_BYTES = 20_000L;

    private Bing() {
    }

    /** 一张每日一图 */
    static final class Item {
        /** 起始日期 yyyyMMdd */
        String date = "";
        /** 中文标题，如「秋色中转动的水轮」 */
        String title = "";
        /** 版权说明，如「格莱德溪磨坊… (© dszc/Getty Images)」 */
        String copyright = "";
        /** 完整图片地址（1920×1080） */
        String url = "";

        /** 列表里显示用：{@code 10-10  秋色中转动的水轮} */
        String label() {
            String d = date == null ? "" : date;
            if (d.length() == 8) {
                // 图池从 2010 年跨到现在，只给「10-19」会分不清是哪一年，必须带年份
                d = d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
            }
            String t = (title == null || title.isEmpty()) ? copyright : title;
            if (t == null) {
                t = "";
            }
            if (t.length() > 20) {
                t = t.substring(0, 20) + "…";
            }
            return d + "  " + t;
        }
    }

    /**
     * 取最近 {@code n} 天的每日一图（1-{@link #MAX_TOTAL}）。
     *
     * <p>要超过 {@link #PER_PAGE} 张时会用 {@code idx} 往后翻页再合并，并按日期去重 ——
     * 超出 Bing 侧翻页范围时它会重复返回上一页的内容，不去重就会出现多份同样的图。</p>
     */
    static List<Item> list(String mkt, int n) throws Exception {
        if (n < 1) {
            n = 1;
        }
        if (n > MAX_TOTAL) {
            n = MAX_TOTAL;
        }
        final String m = (mkt == null || mkt.trim().isEmpty()) ? "zh-CN" : mkt.trim();
        final List<Item> out = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int idx = 0; idx < MAX_TOTAL && out.size() < n; idx += PER_PAGE) {
            final List<Item> page = fetch(m, idx, Math.min(PER_PAGE, n - out.size()));
            if (page.isEmpty()) {
                break;
            }
            for (Item it : page) {
                if (out.size() >= n) {
                    break;
                }
                final String key = (it.date == null || it.date.isEmpty()) ? it.url : it.date;
                if (seen.add(key)) {
                    out.add(it);
                }
            }
        }
        return out;
    }

    /** 抓一页（{@code idx} 为起始序号，{@code n} 为本页张数） */
    private static List<Item> fetch(String mkt, int idx, int n) throws Exception {
        final String body = new String(
                httpGet(String.format(Locale.ROOT, API, n, idx, mkt)),
                Charset.forName("UTF-8"));
        final JSONObject o = new JSONObject(body);
        final JSONArray arr = o.optJSONArray("images");
        final List<Item> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject e = arr.optJSONObject(i);
            if (e == null) {
                continue;
            }
            final Item it = new Item();
            it.date = e.optString("startdate", "");
            it.title = e.optString("title", "");
            it.copyright = e.optString("copyright", "");
            final String base = e.optString("urlbase", "");
            final String u = e.optString("url", "");
            if (!base.isEmpty()) {
                it.url = HOST + base + "_1920x1080.jpg";
            } else if (!u.isEmpty()) {
                it.url = u.startsWith("http") ? u : HOST + u;
            }
            if (!it.url.isEmpty()) {
                out.add(it);
            }
        }
        return out;
    }

    /** UHD（4K）地址：把 {@code _1920x1080.jpg} 换成 {@code _UHD.jpg} */
    static String uhd(String url1080) {
        if (url1080 == null) {
            return null;
        }
        return url1080.replace("_1920x1080.jpg", "_UHD.jpg");
    }

    /** 缓存目录：App 私有 {@code <files>/bing}（免任何存储权限） */
    static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "bing");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 这张图对应的本地文件 */
    static File fileFor(Context ctx, Item it) {
        final String d = (it.date == null || it.date.isEmpty())
                ? "x" + Math.abs(it.url.hashCode()) : it.date;
        return new File(dir(ctx), "bing_" + d + ".jpg");
    }

    /** 找目录里该日期的任意一张壁纸（两种命名都认）；没有返回 {@code null} */
    private static File findAnyByDate(Context ctx, String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) {
            return null;
        }
        try {
            final File[] all = dir(ctx).listFiles((d, name) -> isWallpaperName(name)
                    && yyyymmdd.equals(dateOfName(name)));
            if (all == null || all.length == 0) {
                return null;
            }
            // 优先带标题的那张（YYYYMMDD_标题.jpg），它信息最全
            Arrays.sort(all, (a, b) -> {
                final boolean ta = !titleOfName(a.getName()).isEmpty();
                final boolean tb = !titleOfName(b.getName()).isEmpty();
                if (ta != tb) {
                    return ta ? -1 : 1;
                }
                return a.getName().compareTo(b.getName());
            });
            return all[0].length() >= MIN_BYTES ? all[0] : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 下载（本地已有完整文件则直接复用），返回本地文件；{@code keep <= 0} 时不淘汰 */
    static File download(Context ctx, Item it, boolean uhd, int keep) throws Exception {
        final File f = fileFor(ctx, it);
        if (f.exists() && f.length() >= MIN_BYTES) {
            // 刷新"最近使用"，让淘汰算法知道它还有用
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        // 当天已经有一张带标题的图（YYYYMMDD_标题.jpg，来自外部整理库）就直接用，
        // 否则每天自动换图都会再下一份同名不同命名的副本，列表里同一天出现两张。
        final File byDate = findAnyByDate(ctx, it.date);
        if (byDate != null) {
            //noinspection ResultOfMethodCallIgnored
            byDate.setLastModified(System.currentTimeMillis());
            return byDate;
        }
        final String url = uhd ? uhd(it.url) : it.url;
        final byte[] data = httpGet(url);
        if (data.length < MIN_BYTES) {
            throw new Exception("图片数据不完整（" + data.length + "B）");
        }
        final File tmp = new File(f.getAbsolutePath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(data);
            fo.flush();
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
        if (!f.exists() || f.length() < MIN_BYTES) {
            throw new Exception("写入缓存失败");
        }
        prune(ctx, keep);
        return f;
    }

    /**
     * 目录里这两种命名都算壁纸，缺一不可：
     *
     * <ul>
     *   <li>{@code bing_YYYYMMDD.jpg} / {@code bing_YYYYMMDD_01.jpg} —— App 自己下载的，没有标题；</li>
     *   <li>{@code YYYYMMDD_标题.jpg} / {@code YYYYMMDD.jpg} —— 外部导入的带标题库，
     *       标题直接藏在文件名里（见 {@link #titleOfName}）。</li>
     * </ul>
     */
    static boolean isWallpaperName(String name) {
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jpg")) {
            return false;
        }
        return dateOfName(name).length() == 8;
    }

    /** 从文件名取 {@code YYYYMMDD}（两种命名都认）；取不到返回空串 */
    static String dateOfName(String name) {
        if (name == null) {
            return "";
        }
        final int off = name.startsWith("bing_") ? 5 : 0;
        if (name.length() < off + 8) {
            return "";
        }
        for (int i = off; i < off + 8; i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return "";
            }
        }
        return name.substring(off, off + 8);
    }

    /**
     * 从文件名取标题：{@code 20210105_玻利维亚…鹦鹉.jpg} → {@code 玻利维亚…鹦鹉}。
     *
     * <p>没有下划线（{@code 20210105.jpg}）、或下划线后是纯数字（{@code bing_20210105_01.jpg}
     * 这种同日变体编号）时返回空串 —— 后者是「同一天的第二张图」，它没有独立标题，
     * 硬套主图标题就是张冠李戴。</p>
     */
    static String titleOfName(String name) {
        final String d = dateOfName(name);
        if (d.isEmpty()) {
            return "";
        }
        final int off = name.startsWith("bing_") ? 5 : 0;
        final int sep = off + 8;
        if (name.length() <= sep + 1 || name.charAt(sep) != '_') {
            return "";
        }
        String t = name.substring(sep + 1);
        if (t.toLowerCase(Locale.ROOT).endsWith(".jpg")) {
            t = t.substring(0, t.length() - 4);
        }
        if (t.isEmpty()) {
            return "";
        }
        boolean allDigit = true;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                allDigit = false;
                break;
            }
        }
        return allDigit ? "" : t.trim();
    }

    /**
     * 本地已缓存的图，按日期<b>从新到旧</b>排。
     *
     * <p>先按文件名里的 {@code YYYYMMDD} 倒序，同一天再按名字倒序 —— 这样
     * {@code bing_20210105.jpg} 和 {@code 20210105_标题.jpg} 能正确混排
     * （直接按名字排的话，{@code bing_} 开头的全挤到最后）。</p>
     */
    static List<File> cached(Context ctx) {
        final File[] all = dir(ctx).listFiles((d, name) -> isWallpaperName(name));
        final List<File> out = new ArrayList<>();
        if (all != null) {
            out.addAll(Arrays.asList(all));
        }
        out.sort((a, b) -> {
            final int c = dateOfName(b.getName()).compareTo(dateOfName(a.getName()));
            return c != 0 ? c : b.getName().compareTo(a.getName());
        });
        return out;
    }

    /** 只保留最近使用的 {@code max} 张，其余删掉；{@code max <= 0} 表示不限制、不删任何东西 */
    private static void prune(Context ctx, int max) {
        if (max <= 0) {
            return;
        }
        try {
            final File[] all = dir(ctx).listFiles((d, name) -> isWallpaperName(name));
            if (all == null || all.length <= max) {
                return;
            }
            Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = max; i < all.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                all[i].delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "bing prune failed: " + t);
        }
    }

    /**
     * 解码成宽度不超过 {@code maxW} 的位图（RGB_565，省一半内存）。
     * UHD 原图解码后是 33MB，盒子扛不住，必须降采样。
     */
    static Bitmap decode(File f, int maxW) {
        try {
            final BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), b);
            int sample = 1;
            if (b.outWidth > 0 && maxW > 0) {
                while (b.outWidth / (sample * 2) >= maxW) {
                    sample *= 2;
                }
            }
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            Log.w(TAG, "bing decode failed: " + t);
            return null;
        }
    }

    /**
     * 居中裁剪铺满：等比放大到刚好盖住整个区域，多出来的边裁掉。
     * 用矩阵绘制而非预裁剪位图 —— 省一份位图内存，且窗口尺寸变化时自动跟着变。
     */
    static final class CropDrawable extends Drawable {
        private final Bitmap bmp;
        private final Paint paint =
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        CropDrawable(Bitmap b) {
            bmp = b;
        }

        @Override
        public void draw(Canvas c) {
            if (bmp == null || bmp.isRecycled()) {
                return;
            }
            final android.graphics.Rect r = getBounds();
            if (r.width() <= 0 || r.height() <= 0) {
                return;
            }
            final float scale = Math.max((float) r.width() / bmp.getWidth(),
                    (float) r.height() / bmp.getHeight());
            final float dx = (r.width() - bmp.getWidth() * scale) / 2f;
            final float dy = (r.height() - bmp.getHeight() * scale) / 2f;
            final int save = c.save();
            c.clipRect(r.left, r.top, r.right, r.bottom);
            c.translate(r.left + dx, r.top + dy);
            c.scale(scale, scale);
            c.drawBitmap(bmp, 0f, 0f, paint);
            c.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    // ==================== 网络 ====================

    private static byte[] httpGet(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11) HomeKeeper/4.7");
            final int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                return bo.toByteArray();
            }
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
