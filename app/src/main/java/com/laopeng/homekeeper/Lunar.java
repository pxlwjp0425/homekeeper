package com.laopeng.homekeeper;

import android.icu.util.ChineseCalendar;

import java.util.Date;

/**
 * 农历（只用于顶栏那行小字，形如 {@code 农历八月十九}）。
 *
 * <p>底层用 {@code android.icu.util.ChineseCalendar}（API 24+，盒子的 Android 11 没问题）。
 * 任何异常都吞掉返回空串 —— 顶栏显示少一段，绝不能让桌面崩。</p>
 *
 * <p>「闰月」由 {@code IS_LEAP_MONTH} 判定，输出 {@code 闰四月}。</p>
 */
final class Lunar {

    /** 月份名，下标 0 = 正月 */
    private static final String[] MONTHS = {
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"
    };
    /** 日的十位：0 初 / 1 十 / 2 廿 / 3 三 */
    private static final String[] DAY_TENS = {"初", "十", "廿", "三"};
    /** 日的个位：0 用「十」（因为 10 = 初十，单独处理） */
    private static final String[] DAY_UNITS = {"十", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    private Lunar() {
    }

    /** 今天的农历，例如 {@code 农历八月十九}；取不到时返回空串 */
    static String today() {
        try {
            ChineseCalendar cc = new ChineseCalendar();
            cc.setTime(new Date());
            int m = cc.get(ChineseCalendar.MONTH);         // 0 = 正月
            int d = cc.get(ChineseCalendar.DAY_OF_MONTH);  // 1..30
            int leap = cc.get(ChineseCalendar.IS_LEAP_MONTH);
            if (m < 0 || m > 11 || d < 1 || d > 30) {
                return "";
            }
            StringBuilder sb = new StringBuilder("农历");
            if (leap == 1) {
                sb.append('闰');
            }
            sb.append(MONTHS[m]).append(dayName(d));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 1 → 初一，10 → 初十，11 → 十一，20 → 二十，21 → 廿一，30 → 三十 */
    static String dayName(int d) {
        if (d < 1 || d > 30) {
            return "";
        }
        if (d == 10) {
            return "初十";
        }
        if (d == 20) {
            return "二十";
        }
        if (d == 30) {
            return "三十";
        }
        return DAY_TENS[d / 10] + DAY_UNITS[d % 10];
    }
}
