package com.laopeng.homekeeper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
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
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        // 卡片背景降到 25% 不透：盖在画面上只是淡淡一层，不挡剧
        card.setBackground(round(0x40101418, 18, 0x33FFFFFF));
        // 卡片最小宽度：保证四按钮横向一排时各自 sub 标签不折行
        card.setMinimumWidth(dp(460));
        // 给卡片一个最小宽度，四个按钮才能均匀撑开、sub 标签不被挤成竖排
        card.setMinimumWidth(dp(430));

        // 标题行
        TextView title = new TextView(this);
        title.setText("工具");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        status = new TextView(this);
        status.setText("");
        status.setTextColor(0xE8FFFFFF);
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(dp(8), dp(3), dp(8), dp(3));
        status.setVisibility(View.GONE);
        status.setBackground(round(0x66000000, 12, 0x33FFFFFF));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp.topMargin = dp(8);
        card.addView(status, slp);

        // 四按钮一排（横向工具条）：各自铺满卡片宽度、右侧小标签说明
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        card.addView(row, rlp);

        btnMem = mkBtn("清内存", "释放后台", 0xFF4EA1FF, this::onMem);
        btnRec = mkBtn("录屏", "开 / 停", 0xFF4ADE80, this::onRec);
        btnReboot = mkBtn("重启", "热重启", 0xFF4EA1FF, this::onReboot);
        btnOff = mkBtn("关机", "待机", 0xFFFFB454, this::onOff);

        row.addView(btnMem, qtr());
        row.addView(btnRec, qtr());
        row.addView(btnReboot, qtr());
        row.addView(btnOff, qtr());

        // 卡片贴右上角放。盒子 1080p 宽 1920px、override 密度 220（dp 系数 1.375）。
        // 直接 px 定位最稳，避开 dp 换算误差。
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
        lp.x = dp(16);          // 距右边缘 16dp
        lp.y = dp(24);          // 距顶 24dp

        wm.addView(card, lp);

        // 初始探测录屏态（查 tvrecorder 的 status 太慢，先用本地静态量；录屏 App 会广播 REC_STATE）
        refreshRecState();
    }

    /** 卡片按钮：可获焦 + 焦点高亮，遥控器左右选、OK 触发。
     *  默认（无焦点）按钮近乎透明，只在获得焦点/按下时才亮，盖在画面上不挡剧。 */
    private View mkBtn(String t, String sub, int accent, Runnable act) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setClickable(true);
        box.setFocusable(true);
        box.setFocusableInTouchMode(false);
        int ac = accent;
        // 无焦点：透明底 + 仅亮色描边；焦点：底提亮 18% + 高亮描边
        box.setBackground(round(0x10FFFFFF, 12, 0x66FFFFFF));
        box.setOnFocusChangeListener((v, f) ->
                v.setBackground(round(f ? 0x30FFFFFF : 0x10FFFFFF, 12,
                        f ? ac : 0x66FFFFFF)));

        TextView a = new TextView(this);
        a.setText(t);
        a.setTextColor(0xEFFFFFFF);   // 白色为主、稍透明，不刺眼
        a.setTextSize(16);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(a);

        TextView b = new TextView(this);
        b.setText(sub);
        b.setTextColor(0xB0FFFFFF);
        b.setTextSize(10);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(1);
        box.addView(b, blp);

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

    /** 横向工具条里每个按钮占 1/4 宽（权重 1），高度随内容；右侧留 6dp 间距 */
    private LinearLayout.LayoutParams qtr() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = dp(6);
        p.topMargin = dp(6);
        p.bottomMargin = dp(2);
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
        if (btnRec != null) {
            final TextView tv = (TextView) ((LinearLayout) btnRec).getChildAt(0);
            tv.setText(recOn == 1 ? "录屏中" : "录屏");
            final TextView sub = (TextView) ((LinearLayout) btnRec).getChildAt(1);
            sub.setText(recOn == 1 ? "点按停止" : "点按开始");
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
