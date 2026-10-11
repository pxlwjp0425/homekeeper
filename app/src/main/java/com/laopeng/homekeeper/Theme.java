package com.laopeng.homekeeper;

/**
 * 一套配色方案。支持两种形态：
 * <ul>
 *   <li><b>纯色</b>：{@code color} 有值，{@code top}/{@code bottom} 相同或不看；</li>
 *   <li><b>纵向渐变（上深下浅）</b>：{@code top} = 顶部（深）色，{@code bottom} = 底部（浅）色。</li>
 * </ul>
 * 由 {@link ThemeStore} 从配置文件解析，主程序不再内置配色表。
 */
final class Theme {

    /** 显示名 */
    final String name;
    /** 顶部（深端）颜色，纯色时等于 {@link #bottom} */
    final int top;
    /** 底部（浅端）颜色 */
    final int bottom;

    Theme(String name, int top, int bottom) {
        this.name = name == null || name.trim().isEmpty() ? "未命名" : name.trim();
        this.top = top;
        this.bottom = bottom;
    }

    Theme(String name, int color) {
        this(name, color, color);
    }

    /** 是否为渐变（两端不同） */
    boolean isGradient() {
        return top != bottom;
    }

    /** 用于列表里画色块的展示色 —— 渐变取底部（较亮）端更醒目 */
    int swatchColor() {
        return bottom;
    }
}
