import java.lang.reflect.Method;

/**
 * Root 侧最小工具：直接把 audio policy 的 phone state 置回 NORMAL，
 * 并把 FOR_COMMUNICATION 的 force use 清成 NONE。
 *
 * 为什么不走 AudioManager：Android 11 的 AudioService 有「mode 所有者」机制，
 * 一旦某个 App setMode(MODE_IN_COMMUNICATION)，其它进程/其它 App 再 setMode 都会被静默拒绝
 * （logcat: setMode(MODE_NORMAL) ... selected mode=MODE_IN_COMMUNICATION by pid=<App>）。
 * AudioSystem 是 native 直通 AudioPolicyService，绕过 Java 层这道锁。
 *
 * 用法： CLASSPATH=fix.jar app_process -Djava.class.path=fix.jar /system/bin PhoneFix
 */
public class PhoneFix {
    public static void main(String[] args) {
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");

            Method setPhoneState = as.getDeclaredMethod("setPhoneState", int.class);
            setPhoneState.setAccessible(true);
            Object r1 = setPhoneState.invoke(null, 0); // MODE_NORMAL

            Method setForceUse = as.getDeclaredMethod("setForceUse", int.class, int.class);
            setForceUse.setAccessible(true);
            Object r2 = setForceUse.invoke(null, 0, 0); // FOR_COMMUNICATION, FORCE_NONE

            System.out.println("PHONEFIX ok setPhoneState=" + r1 + " setForceUse=" + r2);
        } catch (Throwable t) {
            System.out.println("PHONEFIX ERR " + t);
        }
        System.out.flush();
    }
}
