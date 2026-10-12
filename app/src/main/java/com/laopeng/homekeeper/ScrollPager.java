package com.laopeng.homekeeper;

/**
 * 滚轮切屏的「抖动过滤器」。
 *
 * <p><b>为什么需要它</b>：盒子上的鼠标类输入源（实测 dev=11 / SOURCE_MOUSE）在<b>没人碰</b>的情况下
 * 仍会持续吐出滚轮事件，值恒为 ±1，且成对交替（{@code -1,-1,+1,+1,-1,-1,...}）。
 * 如果直接「见滚轮就翻页」，桌面会自己来回跳（实测 20 秒自己翻了 16 次）。
 * 这个类把这些幽灵事件挡掉，只认真实的、有意图的滚动。</p>
 *
 * <p><b>判据</b>：</p>
 * <ol>
 *   <li>单次幅度 {@code |v| >= 3} → 认为是一次明确的滚轮刻度，立即触发（幽灵事件幅度恒为 1）；</li>
 *   <li>否则要求<b>连续同向</b>累计 {@value #NEED} 次，且相邻两次间隔不超过 {@value #GAP_MS} ms；
 *       方向一变就清零 —— 交替抖动因此永远攒不够；</li>
 *   <li>触发后进入 {@value #COOLDOWN_MS} ms 冷却，避免一次长滚翻好几次屏。</li>
 * </ol>
 *
 * <p>纯逻辑、无 Android 依赖，可单独跑 JVM 测试。</p>
 */
final class ScrollPager {

    /** 连续同向多少次才算「有意滚动」 */
    static final int NEED = 3;
    /** 单次幅度达到多少就直接认（真实滚轮刻度，幽灵事件恒为 ±1） */
    static final float STRONG = 3f;
    /** 相邻两次间隔超过它就当作新的一次滚动 */
    static final long GAP_MS = 900L;
    /** 触发后的冷却时间 */
    static final long COOLDOWN_MS = 900L;

    private int dir = 0;
    private int run = 0;
    private long last = 0L;
    private long cooldownUntil = 0L;

    /**
     * 喂一个滚轮事件。
     *
     * @param v   滚轮量（{@code AXIS_VSCROLL}，向下为正）
     * @param now 事件时间戳（ms）
     * @return {@code +1} = 往下翻一屏，{@code -1} = 往上翻一屏，{@code 0} = 不动作
     */
    int feed(float v, long now) {
        if (v == 0f) {
            return 0;
        }
        final int d = v > 0 ? 1 : -1;

        if (now < cooldownUntil) {
            last = now;
            return 0;
        }
        if (Math.abs(v) >= STRONG) {
            run = 0;
            dir = d;
            last = now;
            cooldownUntil = now + COOLDOWN_MS;
            return d;
        }
        if (d != dir || now - last > GAP_MS) {
            dir = d;
            run = 0;
        }
        last = now;
        run++;
        if (run >= NEED) {
            run = 0;
            cooldownUntil = now + COOLDOWN_MS;
            return d;
        }
        return 0;
    }
}
