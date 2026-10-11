package com.laopeng.homekeeper;

/**
 * ScrollPager 的 JVM 实跑断言测试（不依赖 Android）。
 * 用真实的幽灵事件序列（本机实测 -1,-1,+1,+1 交替）验证过滤器。
 */
public class ScrollPagerTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "  -> " + detail);
        }
    }

    /** 按给定间隔喂一串值，返回总共触发了多少次（正数=下翻，负数=上翻） */
    private static int[] run(float[] vals, long gap) {
        ScrollPager p = new ScrollPager();
        int down = 0, up = 0;
        long t = 1000L;
        for (float v : vals) {
            int r = p.feed(v, t);
            if (r > 0) down++;
            if (r < 0) up++;
            t += gap;
        }
        return new int[]{down, up};
    }

    public static void main(String[] args) {
        System.out.println("ScrollPager 测试（NEED=" + ScrollPager.NEED
                + " STRONG=" + ScrollPager.STRONG
                + " GAP=" + ScrollPager.GAP_MS
                + " COOLDOWN=" + ScrollPager.COOLDOWN_MS + "）");

        // 1. 幽灵抖动：-1,-1,+1,+1 重复 20 轮（实测本机就是这种）
        float[] ghost = new float[80];
        for (int i = 0; i < 20; i++) {
            ghost[i * 4] = -1f;
            ghost[i * 4 + 1] = -1f;
            ghost[i * 4 + 2] = 1f;
            ghost[i * 4 + 3] = 1f;
        }
        int[] g1 = run(ghost, 300);
        check("幽灵抖动 80 次(300ms 间隔) → 0 次触发", g1[0] == 0 && g1[1] == 0,
                "down=" + g1[0] + " up=" + g1[1]);
        int[] g2 = run(ghost, 120);
        check("幽灵抖动 80 次(120ms 密集) → 0 次触发", g2[0] == 0 && g2[1] == 0,
                "down=" + g2[0] + " up=" + g2[1]);
        int[] g3 = run(ghost, 60);
        check("幽灵抖动 80 次(60ms 极密) → 0 次触发", g3[0] == 0 && g3[1] == 0,
                "down=" + g3[0] + " up=" + g3[1]);

        // 2. 真实向下滚 3 格
        int[] d3 = run(new float[]{1f, 1f, 1f}, 120);
        check("连续 3 次 +1 → 恰好下翻 1 次", d3[0] == 1 && d3[1] == 0,
                "down=" + d3[0] + " up=" + d3[1]);

        // 3. 真实向上滚 3 格
        int[] u3 = run(new float[]{-1f, -1f, -1f}, 120);
        check("连续 3 次 -1 → 恰好上翻 1 次", u3[0] == 0 && u3[1] == 1,
                "down=" + u3[0] + " up=" + u3[1]);

        // 4. 只滚 2 格（不足阈值）→ 不动作
        int[] d2 = run(new float[]{1f, 1f}, 120);
        check("只滚 2 格 → 不动作", d2[0] == 0 && d2[1] == 0,
                "down=" + d2[0] + " up=" + d2[1]);

        // 5. 单次大刻度 v=3（真实滚轮常见）→ 立即触发
        int[] s3 = run(new float[]{3f}, 120);
        check("单次 +3（明确刻度）→ 立即下翻 1 次", s3[0] == 1 && s3[1] == 0,
                "down=" + s3[0] + " up=" + s3[1]);

        // 6. 慢速滚动：间隔 800ms（< GAP 900）仍应触发
        int[] slow = run(new float[]{1f, 1f, 1f}, 800);
        check("慢速 3 格(间隔 800ms) → 下翻 1 次", slow[0] == 1 && slow[1] == 0,
                "down=" + slow[0] + " up=" + slow[1]);

        // 7. 极慢：间隔 1000ms（> GAP）→ 每次都当成新的一次，永不触发
        int[] vslow = run(new float[]{1f, 1f, 1f, 1f, 1f}, 1000);
        check("极慢 5 格(间隔 1000ms) → 不动作", vslow[0] == 0 && vslow[1] == 0,
                "down=" + vslow[0] + " up=" + vslow[1]);

        // 8. 一次长滚 10 格 → 应被冷却压到 1 次，不会连翻
        int[] long_ = run(new float[]{1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f}, 100);
        check("长滚 10 格(100ms) → 只翻 1 次（冷却生效）", long_[0] == 1 && long_[1] == 0,
                "down=" + long_[0] + " up=" + long_[1]);

        // 9. 方向中途反转 → 计数清零，不该误触发
        int[] flip = run(new float[]{1f, 1f, -1f, -1f, 1f, 1f}, 120);
        check("两下两上两下反复 → 不误触发", flip[0] == 0 && flip[1] == 0,
                "down=" + flip[0] + " up=" + flip[1]);

        // 10. 冷却结束后能再次触发
        ScrollPager p = new ScrollPager();
        long t = 1000;
        int fires = 0;
        for (int i = 0; i < 6; i++) {
            if (p.feed(1f, t) > 0) fires++;
            t += 400; // 每 400ms 一格，3 格 1.2s，冷却 0.9s
        }
        check("持续滚动 6 格(400ms) → 触发 1~2 次（冷却后允许再次）",
                fires >= 1 && fires <= 2, "fires=" + fires);

        System.out.println("结果: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
