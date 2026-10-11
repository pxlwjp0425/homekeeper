package com.laopeng.homekeeper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * 把桌面切到当贝（com.dangbei.tvlauncher）。
 *
 * 之所以需要本垫片：厂商固件在 PackageManagerService.filterHomeApp 里做了品牌启动器
 * 白名单/黑名单，当贝被写死在黑名单里，任何设置改动都会在开机时被云端策略刷回。
 * 而白名单里登记了几个“从未安装”的包名（com.xiaopaitech.*），本 App 借用其中之一，
 * 因此无论如何重启，系统都会放行本 App 作为 HOME —— 再由本 App 把桌面交给当贝。
 */
final class Target {

    private static final String PKG = "com.dangbei.tvlauncher";
    private static final String CLS = "com.dangbei.launcher.ui.main.MainActivity";
    /** 兜底：目标不可用时回落到盒子自带桌面，避免黑屏 */
    private static final String FALLBACK_PKG = "com.ktcp.launcher";
    private static final String FALLBACK_CLS = "com.ktcp.launcher.MainActivity";

    private Target() {
    }

    static void launch(Context ctx) {
        if (tryExplicit(ctx, PKG, CLS)) {
            return;
        }
        if (tryLaunchIntent(ctx, PKG)) {
            return;
        }
        if (tryExplicit(ctx, FALLBACK_PKG, FALLBACK_CLS)) {
            return;
        }
        tryLaunchIntent(ctx, FALLBACK_PKG);
    }

    private static boolean tryExplicit(Context ctx, String pkg, String cls) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryLaunchIntent(Context ctx, String pkg) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
