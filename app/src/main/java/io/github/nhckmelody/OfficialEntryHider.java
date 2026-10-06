package io.github.nhckmelody;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 隐藏官方面板中不需要的条目。
 *
 * <p>目标：「通用设置」标题与其下的「音频设备设置」入口
 * （对第三方耳机无意义，且点进去是空壳页面）。</p>
 *
 * <p><b>为什么用"隐藏"而不是"移除"</b>：这些是 {@code COUIRecyclerView} 的条目，
 * 由 Adapter 绑定产出。直接 {@code removeView} 会在下次绑定/复用时被复原，
 * 而把条目视图设为 {@code GONE} 更稳妥。另外由于可能发生重新绑定，
 * 本方法会被<b>周期性重复施加</b>（见 {@link #scheduleHide}）。</p>
 */
final class OfficialEntryHider {

    /** 需要隐藏的条目文字（精确匹配 TextView 文本）。 */
    private static final String[] HIDE_TEXTS = {
            "音频设备设置",
            "通用设置",
    };

    private OfficialEntryHider() {}

    /**
     * 周期性施加隐藏（应对 Adapter 重新绑定）。
     *
     * @param root 面板根视图
     */
    static void scheduleHide(final View root) {
        if (root == null) return;
        final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] task = new Runnable[1];
        task[0] = new Runnable() {
            int times = 0;
            @Override public void run() {
                try {
                    hide(root);
                } catch (Throwable ignored) {
                }
                // 隐藏改变了官方内容的实际底边 → 重新对齐我们的面板
                try {
                    PanelInjector.realignNow();
                } catch (Throwable ignored) {
                }
                times++;
                // 前 5 次每 500ms 施加一次（覆盖 Adapter 首次绑定与可能的重新绑定），
                // 之后降到每 2s 一次，避免无谓开销。
                if (times < 5) {
                    h.postDelayed(task[0], 500);
                } else if (times < 12) {
                    h.postDelayed(task[0], 2000);
                }
            }
        };
        h.postDelayed(task[0], 300);
    }

    /** 在官方 RecyclerView 内按文字定位并隐藏对应条目。 */
    static void hide(View root) {
        View recycler = findRecycler(root);
        if (!(recycler instanceof ViewGroup)) return;
        ViewGroup rv = (ViewGroup) recycler;
        int hidden = 0;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View item = rv.getChildAt(i);
            if (item.getVisibility() == View.GONE) continue;
            String hit = matchText(item);
            if (hit != null) {
                item.setVisibility(View.GONE);
                hidden++;
                Dumper.diag("[HIDE] 隐藏官方条目: \"" + hit + "\" (recycler#" + i + ")");
            }
        }
        if (hidden > 0) {
            Dumper.diag("[HIDE] 本轮共隐藏 " + hidden + " 个条目");
        }
    }

    /** 在条目子树内查找目标文字，命中则返回该文字。 */
    private static String matchText(View v) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) {
                String t = cs.toString().trim();
                for (String target : HIDE_TEXTS) {
                    if (target.equals(t)) return target;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String r = matchText(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static View findRecycler(View root) {
        if (!(root instanceof ViewGroup)) return null;
        java.util.ArrayDeque<ViewGroup> q = new java.util.ArrayDeque<>();
        q.add((ViewGroup) root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 150) {
            ViewGroup g = q.poll();
            if (g.getClass().getSimpleName().contains("RecyclerView")) return g;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof ViewGroup) q.add((ViewGroup) c);
            }
        }
        return null;
    }
}
