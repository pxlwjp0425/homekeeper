package com.laopeng.homekeeper;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

final class AppAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<AppEntry> data;
    /** 应用名文字色，随桌面背景色切换（浅色底要换成深字） */
    private int colorText;
    /** 是否给应用名加投影（图片壁纸时开，压在照片上也看得清） */
    private boolean shadow;

    @SuppressWarnings("deprecation")
    AppAdapter(Context ctx, List<AppEntry> data) {
        this.inflater = LayoutInflater.from(ctx);
        this.data = data;
        this.colorText = ctx.getResources().getColor(R.color.text);
    }

    /** 背景色变化时调用 */
    void setTextColor(int c) {
        this.colorText = c;
        notifyDataSetChanged();
    }

    /** 图片壁纸开关投影（照片底色上文字才立得住） */
    void setTextShadow(boolean on) {
        this.shadow = on;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_app, parent, false);
        }
        AppEntry e = data.get(position);
        ImageView icon = v.findViewById(R.id.icon);
        TextView name = v.findViewById(R.id.name);
        if (e.icon != null) {
            icon.setImageBitmap(e.icon);
        } else {
            icon.setImageDrawable(null);
        }
        name.setText(e.label);
        name.setTextColor(colorText);
        if (shadow) {
            name.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        } else {
            name.setShadowLayer(0f, 0f, 0f, 0);
        }
        return v;
    }
}
