package com.laopeng.homekeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 收「录屏 App 正在录」的通知，好让桌面把浮在画面上的东西（悬浮信息条、顶栏时钟/天气）
 * 收起来 —— 否则它们会被一起录进视频里。
 *
 * <p><b>为什么只能这么办</b>：{@code MediaProjection} 抓的是屏幕<b>合成之后</b>的画面，
 * 只要一个窗口还画在屏幕上就一定会入镜；系统也没有公开 API 能把单独一层挖掉
 * （{@code FLAG_SECURE} 只是让那块变黑）。所以唯一的正解就是「录的时候自己躲开」，
 * 发起方是录屏 App。对方超过 90 秒没有心跳，{@link OverlayService} 会自己恢复显示，
 * 免得录屏进程被杀之后浮层再也不回来。
 *
 * <p>手动触发（adb，需要 root —— 该广播受 signature 权限保护）：<br>
 * {@code su -c "am broadcast -a com.xiaopaitech.tvrecorder.REC_STATE --ez on true"}<br>
 * {@code su -c "am broadcast -a com.xiaopaitech.tvrecorder.REC_STATE --ez on false"}<br>
 * 普通 adb shell 不是同签名、也没有该权限，会被系统拦掉。</p>
 */
public class RecStateReceiver extends BroadcastReceiver {

    private static final String TAG = "HKDBG";

    /** 与录屏 App（tv-recorder）约定的 action */
    public static final String ACTION = "com.xiaopaitech.tvrecorder.REC_STATE";

    /** 发送方必须具备的权限（signature 级，与 tv-recorder 同签名自动授予） */
    public static final String PERM = "com.laopeng.homekeeper.permission.REC_STATE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) {
            return;
        }
        boolean on = intent.getBooleanExtra("on", false);
        Log.i(TAG, "rec-state <- " + on);
        OverlayService.setRecording(on);
    }
}
