package com.laopeng.homekeeper;

import android.graphics.Bitmap;

/** 桌面网格里的一项 */
final class AppEntry {
    String pkg;
    String cls;
    String label;
    Bitmap icon;
    /** 显示层二次裁过圆角的缓存（避免每帧重裁） */
    Bitmap iconRounded;
    /** iconRounded 对应的圆角比例，配置变了要重裁 */
    float iconRoundedRatio;
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
