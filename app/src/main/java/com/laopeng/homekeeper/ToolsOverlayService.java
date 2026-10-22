package com.laopeng.homekeeper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 可交互悬浮工具条：把「清内存 / 录屏开关 / 重启 / 关机」四个按钮浮在<b>其它 App 之上</b>
 * （酷喵放剧时不切走、不打断，只是盖一块小工具卡片在最上层）。
 *
 * <p>和 {@link OverlayService}（纯显示、NOT_TOUCHABLE）不同，本条<b>可点、遥控器可导航</b>：
 * 焦点落在卡片上时上下左右能选按钮、OK 触发。底层 App 的遥控器焦点不受影响——
 * 卡片自身 {@code FLAG_NOT_TOUCH_MODAL}，只有点到卡片上才截获按键。</p>
 *
 * <p>四个动作全后台执行，都不抢前台：
 * <ul>
 *   <li>清内存 —— 广播 {@code com.laopeng.memclean.CTRL cleannow} 给内存清理 App</li>
 *   <li>录屏开/停 —— 广播 {@code com.laopeng.tvrecorder.CTRL start/stop} 给录屏 App</li>
 *   <li>重启 —— {@link AdbShell#exec(String) adb reboot}（二次确认）</li>
 *   <li>关机 —— {@code reboot -p}（待机，遥控器可开；二次确认）</li>
 * </ul>
 * </p>
 *
 * <p>电源类是破坏性操作，故每次都要二次确认，防遥控器误触。开关由 config.json 的
 * {@code tools.enabled} 控制，由 {@link MainActivity} 启停本 Service。</p>
 */
public class ToolsOverlayService extends Service {

    private static final String TAG = "HKTOOLS";

    /** 目标 App 的包名（CtrlReceiver 都是 exported，跨 App 广播可达；用显式组件名更稳） */
    private static final String PKG_MEMCLEAN = "com.xiaopaitech.memclean";
    private static final String PKG_TVREC = "com.xiaopaitech.tvrecorder";
    private static final String RCV_MEMCLEAN = "com.laopeng.memclean.CtrlReceiver";
    private static final String RCV_TVREC = "com.laopeng.tvrecorder.CtrlReceiver";
    private static final String ACT_MEMCLEAN = "com.laopeng.memclean.CTRL";
    private static final String ACT_TVREC = "com.laopeng.tvrecorder.CTRL";

    private static volatile ToolsOverlayService INSTANCE;

    private WindowManager wm;
    private LinearLayout card;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private View btnMem;
    private View btnRec;
    private View btnReboot;
    private View btnOff;
    private int recOn = -1;   // 录屏态未知 / 0 关 / 1 开
    private LinearLayout confirmView;   // 二次确认 overlay（复用 wm，不依赖 activity token）
    private WindowManager.LayoutParams confirmLp;
    private Runnable statusHideToken;   // 提示自动隐藏的延迟任务句柄
    private View closeBtn;              // 右上角关闭开关
    private IconDrawable recIcon;        // 录屏按钮的 ▶/■ 图标

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        if (!canDraw()) {
            Log.w(TAG, "no SYSTEM_ALERT_WINDOW, exit");
            stopSelf();
            return;
        }
        try {
            buildCard();
        } catch (Throwable t) {
            Log.w(TAG, "build failed: " + t);
            stopSelf();
            return;
        }
        Log.i(TAG, "tools overlay up");
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        dismissConfirm();
        if (wm != null && card != null) {
            try {
                wm.removeView(card);
            } catch (Throwable ignored) {
            }
        }
        card = null;
        super.onDestroy();
    }

    static boolean running() {
        return INSTANCE != null;
    }

    static void start(Context c) {
        try {
            c.startService(new Intent(c, ToolsOverlayService.class));
        } catch (Throwable t) {
            Log.w(TAG, "start failed: " + t);
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, ToolsOverlayService.class));
        } catch (Throwable ignored) {
        }
    }

    private boolean canDraw() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return android.provider.Settings.canDrawOverlays(this);
            }
            return checkSelfPermission("android.permission.SYSTEM_ALERT_WINDOW")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- UI ----------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildCard() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        // 外框背景降到 18% 不透：只是一层淡淡的框，把四个小框"框"起来，不挡画面
        card.setBackground(round(0x2E101418, 14, 0x22FFFFFF));

        // 单行工具条：✕（最左，与四个小框同一行、平齐）+ 状态提示（默认 GONE，撑中间）+ 四个小框
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        closeBtn = mkCloseBtn();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(54), dp(54));
        clp.rightMargin = dp(6);
        clp.gravity = Gravity.CENTER;
        row.addView(closeBtn, clp);

        status = new TextView(this);
        status.setText("");
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(dp(8), dp(3), dp(8), dp(3));
        status.setVisibility(View.GONE);
        status.setBackground(round(0x55000000, 12, 0x22FFFFFF));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        status.setGravity(Gravity.CENTER);
        row.addView(status, slp);

        // 四个小框：定宽等高、四个完全一样大；副标签已去掉，只留主文字
        btnMem = mkBtn("清内存", 0x998FB8E0, this::onMem, false);
        btnRec = mkBtn("录屏", 0x99A0E0B0, this::onRec, true);
        btnReboot = mkBtn("重启", 0x998FB8E0, this::onReboot, false);
        btnOff = mkBtn("关机", 0x99E0C08F, this::onOff, false);

        row.addView(btnMem, eqBox());
        row.addView(btnRec, eqBox());
        row.addView(btnReboot, eqBox());
        row.addView(btnOff, eqBox());

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        card.addView(row, rlp);

        // 卡片贴右上角放，尽量靠右上。盒子 1080p、override 密度 220（dp 系数 1.375）
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                // 不模态、不影响下层焦点，但卡片自身可获焦可点 —— 遥控器/触摸都能操作
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(8);           // 距右边缘更近，尽量靠右上
        lp.y = dp(8);          // 单行后卡片更矮，距顶也可再近

        wm.addView(card, lp);

        // 初始探测录屏态（查 tvrecorder 的 status 太慢，先用本地静态量；录屏 App 会广播 REC_STATE）
        refreshRecState();
    }

    /** 卡片小框：可获焦 + 焦点高亮，遥控器左右选、OK 触发。
     *  定宽等高（四个一样大）、文字居中、淡色不夺目；录屏框带 ▶/■ 图标。 */
    private View mkBtn(String t, int accent, Runnable act, boolean isRec) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(6), dp(8), dp(6));
        box.setClickable(true);
        box.setFocusable(true);
        box.setFocusableInTouchMode(false);
        int ac = accent;
        // 无焦点：透明底 + 淡描边；焦点：底提亮 + 高亮描边
        box.setBackground(round(0x10FFFFFF, 10, 0x44FFFFFF));
        box.setOnFocusChangeListener((v, f) ->
                v.setBackground(round(f ? 0x30FFFFFF : 0x10FFFFFF, 10,
                        f ? ac : 0x44FFFFFF)));

        // 录屏框：根据当前录态在文字前显示 ▶（开始）或 ■（停止）
        if (isRec) {
            recIcon = new IconDrawable(getResources());
            recIcon.setRecorded(recOn);
            View iconView = new View(this);
            int iw = recIcon.getIntrinsicWidth(), ih = recIcon.getIntrinsicHeight();
            iconView.setBackground(recIcon);
            LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(iw, ih);
            iclp.rightMargin = dp(5);
            iclp.gravity = Gravity.CENTER_VERTICAL;
            box.addView(iconView, iclp);
        }

        TextView a = new TextView(this);
        a.setText(t);
        a.setTextColor(0x99FFFFFF);   // 淡色、不夺目
        a.setTextSize(15);
        a.setGravity(Gravity.CENTER);
        a.setSingleLine(true);
        box.addView(a);

        // 遥控器 OK：焦点在按钮上按中键/确定键时触发（触摸点按同样走这里）
        box.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_CENTER
                        || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                act.run();
                return true;
            }
            return false;
        });
        box.setOnClickListener(v -> act.run());
        return box;
    }

    /** 四个小框等宽等高：固定 110dp 宽、54dp 高，互相 6dp 间距，保证一样大 */
    private LinearLayout.LayoutParams eqBox() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(110), dp(54));
        p.rightMargin = dp(6);
        p.gravity = Gravity.CENTER;
        return p;
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(1, stroke);
        return g;
    }

    // ---------------- 动作 ----------------

    private void refreshRecState() {
        recOn = OverlayService.isRecording() ? 1 : 0;
        if (recIcon != null) {
            recIcon.setRecorded(recOn == 1 ? 1 : 0);   // 图标随录态在 ▶（未录）/ ■（录中）间切换
        }
    }

    private void onMem() {
        postStatus("清理内存中…");
        new Thread(() -> {
            final boolean ok = sendCtrl(PKG_MEMCLEAN, RCV_MEMCLEAN, ACT_MEMCLEAN, "cleannow", null);
            ui.post(() -> postStatus(ok ? "内存已清理" : "清内存失败（该 App 未装？）"));
        }, "tool-mem").start();
    }

    private void onRec() {
        refreshRecState();
        final boolean on = recOn == 1;   // 现在录着 → 停；没录 → 开
        postStatus(on ? "停止录屏…" : "开始录屏…");
        new Thread(() -> {
            final boolean ok = sendCtrl(PKG_TVREC, RCV_TVREC, ACT_TVREC, on ? "stop" : "start", null);
            ui.post(() -> {
                postStatus(ok ? (on ? "已停止录屏" : "已启动录屏") : "录屏操作失败（该 App 未装？）");
                refreshRecState();
            });
        }, "tool-rec").start();
    }

    /** 右上角关闭开关：点它把工具条收掉，随时能再打开（MainActivity 会按 config 重拉起） */
    private View mkCloseBtn() {
        TextView c = new TextView(this);
        c.setText("✕");
        c.setTextColor(0x99FFFFFF);
        c.setTextSize(15);
        c.setGravity(Gravity.CENTER);
        c.setFocusable(true);
        c.setClickable(true);
        c.setBackground(round(0x10FFFFFF, 10, 0x33FFFFFF));
        c.setOnFocusChangeListener((v, f) ->
                v.setBackground(round(f ? 0x30FFFFFF : 0x10FFFFFF, 10,
                        f ? 0x99FFFFFF : 0x33FFFFFF)));
        c.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_CENTER
                        || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                onToggleClose();
                return true;
            }
            return false;
        });
        c.setOnClickListener(v -> onToggleClose());
        return c;
    }

    /** 关闭/重新展开工具条。关闭即把卡片移走；再点（若还留着开关）则重新展开。 */
    private void onToggleClose() {
        if (card == null) return;
        postStatus("工具条已关闭");
        hideCard();
    }

    private void hideCard() {
        if (wm == null) return;
        if (card != null) {
            try {
                wm.removeView(card);
            } catch (Throwable ignored) {
            }
            card = null;
        }
        dismissConfirm();
        // 收掉后 2s 再停 service；这样用户误触可短暂恢复
        ui.postDelayed(ToolsOverlayService.this::onCardHidden, 2000);
    }

    private void onCardHidden() {
        stop(this);
    }

    /**
     * 手绘的录制状态图标：未录时画「▶ 开始」三角形，录中时画「■ 停止」方块。
     * 用 {@link PathShape}（三角）/ {@link RoundRectShape}（方块）两个 Shape 做 PathShape
     * 太绕，这里直接画两个 Path 切换，颜色淡（0x99 白）不夺目。
     */
    private static class IconDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();
        private final Path rect = new Path();
        private int recorded;
        private final int w, h;

        IconDrawable(android.content.res.Resources r) {
            int u = (int) (r.getDisplayMetrics().density * 2);
            w = u * 9; h = u * 9;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(0x99FFFFFF);
            // 三角 ▶：在 9u×9u 视口内居中
            float cx = u * 4.5f, cy = u * 4.5f;
            float t = u * 2.6f;
            tri.moveTo(cx - t * 0.7f, cy - t);
            tri.lineTo(cx - t * 0.7f, cy + t);
            tri.lineTo(cx + t, cy);
            tri.close();
            // 方块 ■
            float s = u * 2.0f;
            rect.addRect(cx - s, cy - s, cx + s, cy + s, Path.Direction.CW);
        }

        void setRecorded(int on) {
            recorded = on;
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            c.drawPath((recorded == 1) ? rect : tri, p);
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void onReboot() {
        final String cmd = ThemeStore.load(this).toolsRebootCmd;
        if (cmd.isEmpty()) {
            postStatus("未配置重启命令（config.json 的 tools.rebootCmd）");
            return;
        }
        confirm("热重启", "盒子将立即重新启动，正在看的内容会中断。确定？", () -> {
            postStatus("重启中…");
            runCmd(cmd, "tool-reboot");
        });
    }

    private void onOff() {
        final String cmd = ThemeStore.load(this).toolsOffCmd;
        if (cmd.isEmpty()) {
            postStatus("未配置关机命令（config.json 的 tools.offCmd）");
            return;
        }
        confirm("关机", "进入待机，遥控器电源键可再开机。确定？", () -> {
            postStatus("关机中…");
            runCmd(cmd, "tool-off");
        });
    }

    /** 走本机 adbd 执行一条 shell 命令（config 里给的完整命令，含 root 前缀） */
    private void runCmd(String cmd, String threadName) {
        new Thread(() -> {
            final String r = AdbShell.exec(cmd);
            Log.i(TAG, threadName + " -> " + r);
        }, threadName).start();
    }

    /** 破坏性操作的二次确认。
     *  <b>不能</b>用 {@code AlertDialog}——Service 没有 window token，弹它会 {@code BadTokenException}
     *  直接崩掉本进程。改用<b>复用卡片同一个 WindowManager 的另一块 overlay 视图</b>：
     *  一块居中的半透明卡片，「确定 / 取消」两枚按钮，遥控器/触摸都能点，不挡底层 CIBN 播放。 */
    private void confirm(String title, String msg, Runnable yes) {
        if (confirmView != null) return;   // 已在确认中，忽略
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(24), dp(20), dp(24), dp(20));
            box.setBackground(round(0xF0101418, 22, 0x80FFFFFF));

            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(20);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(t);

            TextView m = new TextView(this);
            m.setText(msg);
            m.setTextColor(0xE0C8CEDC);
            m.setTextSize(13);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(8);
            box.addView(m, mlp);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rl.topMargin = dp(16);
            box.addView(row, rl);

            TextView cancel = mkConfirmBtn("取消", 0xFF8D95A5, () -> dismissConfirm());
            TextView ok = mkConfirmBtn("确定", 0xFF4EA1FF, () -> {
                dismissConfirm();
                yes.run();
            });
            row.addView(cancel);
            row.addView(ok);

            WindowManager.LayoutParams clp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            clp.gravity = Gravity.CENTER;

            confirmView = box;
            confirmLp = clp;
            wm.addView(box, clp);
            ui.postDelayed(() -> ok.requestFocus(), 200);
        } catch (Throwable t) {
            Log.w(TAG, "confirm overlay failed: " + t);
            postStatus("确认框创建失败");
        }
    }

    private TextView mkConfirmBtn(String text, int color, Runnable act) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(16);
        b.setPadding(dp(22), dp(10), dp(22), dp(10));
        b.setBackground(round(0xFF1A202A, 12, 0xFF2A3546));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, f) ->
                v.setBackground(round(f ? 0xFF2A3546 : 0xFF1A202A, 12, f ? color : 0xFF2A3546)));
        b.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_CENTER
                        || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                act.run();
                return true;
            }
            return false;
        });
        b.setOnClickListener(v -> act.run());
        return b;
    }

    private void dismissConfirm() {
        if (confirmView != null && wm != null) {
            try {
                wm.removeView(confirmView);
            } catch (Throwable ignored) {
            }
        }
        confirmView = null;
        confirmLp = null;
        if (status != null) status.setText("就绪");
    }

    /** 发一条 explicit 广播给目标 App 的 CtrlReceiver。
     *  先查对方组件装没装；没装直接返回 false，装了就 sendBroadcast 出去（对方会自行执行并打 logcat）。 */
    private boolean sendCtrl(String pkg, String rcv, String action, String cmd, String extra) {
        try {
            // 组件装没装：能解析到该 receiver 才继续，避免对着空包发
            final android.content.ComponentName cn = new android.content.ComponentName(pkg, rcv);
            final android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            if (pi == null) {
                return false;
            }
            final Intent i = new Intent(action);
            i.setComponent(cn);
            i.putExtra("cmd", cmd);
            if (extra != null) {
                i.putExtra("list", extra);
            }
            sendBroadcast(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendCtrl fail: " + t);
            return false;
        }
    }

    private void postStatus(String s) {
        if (status != null) {
            status.setText(s);
            status.setVisibility(View.VISIBLE);
            // 提示 2.5s 后自动隐藏，盖在画面上不留字
            ui.removeCallbacksAndMessages(statusHideToken);
            ui.postDelayed(statusHideToken = new Runnable() {
                @Override
                public void run() {
                    if (status != null) status.setVisibility(View.GONE);
                }
            }, 2500);
        }
        Log.i(TAG, s);
    }
}
