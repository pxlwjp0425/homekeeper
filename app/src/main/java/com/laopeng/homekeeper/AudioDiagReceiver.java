package com.laopeng.homekeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;

/**
 * 音频诊断入口（开发用，平时不参与业务）。
 *
 * <p>为什么要它：本盒子唯一的音频输出是 <b>HDMI Out</b>，「扬声器」这条物理通路根本不存在。
 * 一旦有 App 调 {@code setSpeakerphoneOn(true)}（= {@code setForceUse(FOR_COMMUNICATION, FORCE_SPEAKER)}），
 * 通话类音频（STREAM_VOICE_CALL，如中兴智慧生活实时预览的摄像头声音）就会被送进那个不存在的扬声器
 * → 电视一点声音没有。桌面 App 里有个每秒跑一次的看门狗负责把它拉回 HDMI；
 * 本 Receiver 让人可以丢开手机 UI、直接用 adb 手动复现「被设成外放」这个状态来验证看门狗。</p>
 *
 * <pre>
 * # 只查当前状态（不改变任何东西）
 * adb shell am broadcast -a com.laopeng.homekeeper.DIAG_SPEAKER --ez query true
 *
 * # 手动设成「外放」（看门狗应在 1 秒内把它拉回来）
 * adb shell am broadcast -a com.laopeng.homekeeper.DIAG_SPEAKER --ez on true
 *
 * # 手动设回 HDMI
 * adb shell am broadcast -a com.laopeng.homekeeper.DIAG_SPEAKER --ez on false
 * </pre>
 *
 * <p>结果都写在 logcat 里，tag = {@code HKDBG}。</p>
 */
public class AudioDiagReceiver extends BroadcastReceiver {

    static final String ACTION = "com.laopeng.homekeeper.DIAG_SPEAKER";
    private static final String TAG = "HKDBG";

    @Override
    public void onReceive(Context ctx, Intent in) {
        try {
            final AudioManager am =
                    (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                Log.w(TAG, "diag: no AudioManager");
                return;
            }
            if (in != null && in.hasExtra("query")) {
                Log.i(TAG, "diag-query: speakerphoneOn=" + am.isSpeakerphoneOn()
                        + " mode=" + am.getMode());
                return;
            }
            final boolean on = in != null && in.getBooleanExtra("on", false);
            am.setSpeakerphoneOn(on);
            Log.i(TAG, "diag-set: setSpeakerphoneOn(" + on + ") -> speakerphoneOn="
                    + am.isSpeakerphoneOn() + " mode=" + am.getMode());
        } catch (Throwable t) {
            Log.w(TAG, "diag failed: " + t);
        }
    }
}
