import java.lang.reflect.Method;

/**
 * 高频循环版：每 150ms 压一次 FOR_COMMUNICATION=NONE，持续 N 毫秒（默认 30s）。
 * 目的：验证"只压 force use"能否让通话流落到 HDMI。
 *
 * 用法： CLASSPATH=fix.jar app_process /system/bin PhoneFixLoop [持续时间ms]
 */
public class PhoneFixLoop {
    public static void main(String[] args) throws Exception {
        long dur = 30000L;
        if (args != null && args.length > 0) {
            try { dur = Long.parseLong(args[0]); } catch (Throwable ignore) {}
        }
        Class<?> as = Class.forName("android.media.AudioSystem");
        Method setForceUse = as.getDeclaredMethod("setForceUse", int.class, int.class);
        setForceUse.setAccessible(true);
        Method setPhoneState = as.getDeclaredMethod("setPhoneState", int.class);
        setPhoneState.setAccessible(true);

        long end = System.currentTimeMillis() + dur;
        int n = 0;
        while (System.currentTimeMillis() < end) {
            setForceUse.invoke(null, 0, 0);   // FOR_COMMUNICATION, FORCE_NONE
            setPhoneState.invoke(null, 0);    // MODE_NORMAL（预期被挡）
            n++;
            Thread.sleep(150L);
        }
        System.out.println("PHONEFIXLOOP done n=" + n);
        System.out.flush();
    }
}
