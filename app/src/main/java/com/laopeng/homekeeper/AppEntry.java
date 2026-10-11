package com.laopeng.homekeeper;

import android.graphics.Bitmap;

/** 桌面网格里的一项 */
final class AppEntry {
    String pkg;
    String cls;
    String label;
    Bitmap icon;
    boolean leanback;
    /** 它自己也是一个桌面（Launcher）：排序时统一置底，避免"桌面里排桌面" */
    boolean isLauncher;

    /** 用户从本桌面点开它的次数（本地统计，最贴合使用习惯） */
    int launchCount;
    /** 系统统计的近 30 天启动次数（API 28+，需 GET_USAGE_STATS 授权） */
    long sysLaunchCount;
    /** 系统统计的近 30 天前台时长（毫秒），需 GET_USAGE_STATS 授权，无权限时为 0 */
    long usageMs;
    /** 最后使用时间（本地点击 / 系统记录取较新者） */
    long lastUsed;
}
