package com.laopeng.homekeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机兜底：确保桌面被拉起一次（幂等，系统通常已自行拉起 HOME）。
 *
 * <p>额外负责：本机 ROM 每次重启都会把显示密度重置回 320，且 WMS 不读
 * {@code Settings.Secure.display_density_forced}，唯一生效方式是执行
 * {@code wm density}。App 身份的 {@code wm} 会被 INTERACT_ACROSS_USERS_FULL 拒，
 * 所以绕道本地 adbd（shell 身份）写回 220。见 {@link AdbShell}。</p>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
        // 开机立即写 220 密度（不等桌面 Activity 起来），不依赖 HOME 被系统拉起
        new Thread(() -> {
            try {
                String r = AdbShell.exec("wm density 220");
                android.util.Log.i("HKBOOTDPI", "boot density write: " + (r == null ? "null" : r.trim()));
            } catch (Throwable ignored) {
            }
        }, "boot-dpi").start();
    }
}
