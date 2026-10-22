package com.laopeng.homekeeper;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 自定义页（首屏）适配器：槽位数 = 列数 × 行数（由配置文件的列数与固定 2 行推导）。
 * data 里对应项为 null 时表示空槽，显示"+"代表"添加"。
 *
 * <p>★ 列数 / 图标尺寸 / 文字大小 / 内边距全部来自配置文件（{@link #applyConfig}），
 * 这里不再写死。<b>槽位数随之变化</b>：列数变了会自动重新计算 SLOTS 并通知外部。</p>
 */
final class FavAdapter extends BaseAdapter {

    /** 固定 2 行 */
    static final int ROWS = 2;
    /** 默认列数（配置读不到时的兜底） */
    static final int DEFAULT_SPAN = 5;
    /** 默认槽位数 = 默认列数 × 2 行 */
    static final int DEFAULT_SLOTS = DEFAULT_SPAN * ROWS;
    /** 列数（来自配置，默认 5） */
    private int span = DEFAULT_SPAN;
    /** 槽位数 = 列数 × 行数 */
    private int slots = DEFAULT_SLOTS;

    private final Context ctx;
    private final LayoutInflater inflater;
    private final List<AppEntry> data;
    /** 文字色随桌面背景色切换（浅色底要换成深字） */
    private int colorText;
    private int colorDim;
    /** 是否给应用名加投影（图片壁纸时开，压在照片上也看得清） */
    private boolean shadow;

    /** 图标边长（dp）来自配置 */
    private int iconSizeDp = 160;
    /** 图标下方留白（dp） */
    private int iconPadBottomDp = 12;
    /** 应用名文字大小（sp） */
    private int nameSizeSp = 16;
    /** 应用名上方留白（dp） */
    private int namePadTopDp = 10;
    /** 临时诊断：前 3 张裁好的图落盘 */
    private static int DUMP_R = 0;
    /** 图标圆角比例（来自配置 icon.cornerRatio，0 = 直角，0.5 = 圆形） */
    private float cornerRatio = 0.3f;
    /** 「整理顺序」模式下正在被搬动的槽位，-1 = 不在整理 */
    private int arrangePos = -1;

    @SuppressWarnings("deprecation")
    FavAdapter(Context ctx, List<AppEntry> data) {
        this.ctx = ctx;
        this.inflater = LayoutInflater.from(ctx);
        this.data = data;
        this.colorText = ctx.getResources().getColor(R.color.text);
        this.colorDim = ctx.getResources().getColor(R.color.text_dim);
    }

    /** 当前列数 */
    int getSpan() {
        return span;
    }

    /** 当前槽位数 */
    int getSlots() {
        return slots;
    }

    /**
     * 应用配置（列数 / 图标 / 文字）。列数变化时同步重算槽位数。
     *
     * @return 槽位数是否发生了变化（变了的话调用方需要同步 favPkgs / favs 的尺寸）
     */
    boolean applyConfig(int columns, int iconSizeDp, int iconPadBottomDp,
                        int nameSizeSp, int namePadTopDp) {
        boolean slotsChanged = columns != this.span;
        this.span = columns <= 0 ? 5 : columns;
        this.slots = ROWS * this.span;
        this.iconSizeDp = iconSizeDp;
        this.iconPadBottomDp = iconPadBottomDp;
        this.nameSizeSp = nameSizeSp;
        this.namePadTopDp = namePadTopDp;
        notifyDataSetChanged();
        return slotsChanged;
    }

    /** 图标圆角比例变化时调用（显示层直接按 View 轮廓裁剪，保证一定是圆角） */
    void setCornerRatio(float r) {
        if (r < 0f) {
            r = 0f;
        }
        if (r > 0.5f) {
            r = 0.5f;
        }
        if (Math.abs(r - cornerRatio) < 0.001f) {
            return;
        }
        this.cornerRatio = r;
        notifyDataSetChanged();
    }

    /** 按配置圆角比例裁一次图标（带缓存，配置变了才重裁） */
    private android.graphics.Bitmap roundOf(AppEntry e) {
        if (cornerRatio <= 0.001f) {
            return e.icon;
        }
        if (e.iconRounded != null
                && Math.abs(e.iconRoundedRatio - cornerRatio) < 0.001f) {
            return e.iconRounded;
        }
        android.graphics.Bitmap src = e.icon;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return src;
        }
        try {
            float r = Math.min(w, h) * Math.min(0.5f, cornerRatio);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.BitmapShader(src,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(new android.graphics.RectF(0, 0, w, h), r, r, p);
            e.iconRounded = out;
            e.iconRoundedRatio = cornerRatio;
            if (DUMP_R < 3) {
                DUMP_R++;
                try {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(
                            "/sdcard/hkround_" + DUMP_R + ".png");
                    out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 背景色变化时调用 */
    void setColors(int text, int dim) {
        this.colorText = text;
        this.colorDim = dim;
        notifyDataSetChanged();
    }

    /** 图片壁纸开关投影（照片底色上文字才立得住） */
    void setTextShadow(boolean on) {
        this.shadow = on;
        notifyDataSetChanged();
    }

    /** 设置「整理顺序」模式下正在搬动的槽位（-1 = 结束整理） */
    void setArrangePos(int pos) {
        if (this.arrangePos == pos) {
            return;
        }
        this.arrangePos = pos;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return slots;
    }

    @Override
    public Object getItem(int position) {
        return position >= 0 && position < data.size() ? data.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_fav, parent, false);
        }
        // 平均分布：让每格高度 = (网格可用高度 - 行间距×(行数-1)) / 行数，
        // 使若干格纵向铺满整屏，而不是挤在顶部。
        if (parent != null) {
            int h = parent.getHeight()
                    - parent.getPaddingTop() - parent.getPaddingBottom();
            if (h > 0) {
                int rows = (slots + span - 1) / span;
                int spacing = 0;
                if (parent instanceof android.widget.GridView) {
                    spacing = ((android.widget.GridView) parent).getVerticalSpacing();
                }
                int itemH = (h - spacing * (rows - 1)) / rows;
                if (itemH > 0) {
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    if (lp != null && lp.height != itemH) {
                        lp.height = itemH;
                        v.setLayoutParams(lp);
                    }
                }
            }
        }
        ImageView icon = v.findViewById(R.id.icon);
        TextView name = v.findViewById(R.id.name);

        // ---- 图标尺寸 / 文字大小 / 内边距：按配置实时应用 ----
        int iconPx = Math.round(dp(iconSizeDp));
        ViewGroup.LayoutParams ilp = icon.getLayoutParams();
        if (ilp != null && (ilp.width != iconPx || ilp.height != iconPx)) {
            ilp.width = iconPx;
            ilp.height = iconPx;
            icon.setLayoutParams(ilp);
        }
        name.setTextSize(nameSizeSp);

        ViewGroup.LayoutParams vlp = v.getLayoutParams();
        if (v instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) v;
            int padBottom = Math.round(dp(iconPadBottomDp));
            if (ll.getPaddingBottom() != padBottom) {
                ll.setPadding(ll.getPaddingLeft(), ll.getPaddingTop(),
                        ll.getPaddingRight(), padBottom);
            }
        }
        name.setPadding(name.getPaddingLeft(),
                Math.round(dp(namePadTopDp)),
                name.getPaddingRight(), name.getPaddingBottom());

        AppEntry e = position < data.size() ? data.get(position) : null;
        if (e == null) {
            icon.setImageResource(R.drawable.ic_add);
            name.setText("添加");
            name.setTextColor(colorDim);
        } else {
            if (e.icon != null) {
                android.util.Log.i("HKFAV", "pkg=" + e.pkg + " ratio=" + cornerRatio
                        + " icon=" + e.icon.getWidth() + "x" + e.icon.getHeight()
                        + " rounded=" + (roundOf(e) != e.icon));
                // 显示层兜底：再按配置比例裁一次圆角。renderIcon 产出的 bitmap 本来带圆角，
                // 这里重复裁只是把外围透明区再切一点，无副作用；万一拿到的是直角图标也能修圆。
                icon.setImageBitmap(roundOf(e));
            } else {
                icon.setImageDrawable(null);
            }
            name.setText(e.label);
            name.setTextColor(colorText);
        }
        if (shadow) {
            name.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        } else {
            name.setShadowLayer(0f, 0f, 0f, 0);
        }
        // 「整理顺序」模式下正在搬动的那一格：加高亮边框，一眼看出"我正拿着这一格"
        if (position == arrangePos) {
            v.setBackgroundResource(R.drawable.item_move);
        } else {
            v.setBackground(null);
        }
        return v;
    }
}
