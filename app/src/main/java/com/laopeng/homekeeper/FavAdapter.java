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
                icon.setImageBitmap(e.icon);
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
