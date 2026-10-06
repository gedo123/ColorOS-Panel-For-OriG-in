package io.github.nhckmelody;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 NiceHCK 控制区注入官方「无线耳机」面板。
 *
 * <p><b>为什么用 ActivityLifecycleCallbacks 而不是 Hook Activity 类</b>：
 * melody 的面板 Activity 受 {@code oplus.permission.OPLUS_COMPONENT_SAFE} 保护，
 * 且 R8 可能改动部分类；而 {@code registerActivityLifecycleCallbacks} 只需一个
 * {@code Application} 实例，识别靠类名<em>后缀</em>，抗混淆能力最强。</p>
 *
 * <p>注入容器选择顺序：ScrollView 内容区 &gt; 最大子 View 的 ViewGroup &gt; 根容器。
 * 首次运行会把候选容器层级全部打印出来，便于按真实结构调整。</p>
 */
public final class PanelInjector {

    private static final String TAG = Dumper.TAG;
    private static final String VIEW_TAG = "nhck_panel_v1";

    /**
     * 当前已注入的面板实例（唯一）。
     *
     * <p><b>为什么必须有这个</b>：{@code OfficialStylePanel} 里的
     * {@code ANC_ITEMS} 是静态数组，其 ImageView 引用只指向"最后一次构建"的那套。
     * 如果面板被重复注入（onResume / onWindowFocusChanged 各触发一次），
     * 就会同时存在多套视图，而刷新只作用于其中一套 →
     * 表现为「点击后屏幕不变，重开面板才生效」。
     * 因此这里强制保证<b>任一时刻只有一个注入实例</b>。</p>
     */
    private static volatile View sInjectedPanel;

    /**
     * 本次面板是否属于"我们的目标设备"（原道 / NiceHCK 系列）。
     *
     * <p>由 {@code MyDevicesHookInstaller} 在 Activity {@code onCreate}
     * 解析面板 Intent 后设置。默认 <b>false</b>：只有确认是本模块目标设备时
     * 才注入控制面板，避免给车机、别家耳机等无关设备凭空插入我们的控件。</p>
     */
    private static volatile boolean sIsTargetDevice = false;

    static void setTargetDevice(boolean target) {
        sIsTargetDevice = target;
    }


    /** 当前注入面板的"重新对齐"动作（隐藏官方条目后需要再次调用）。 */
    private static volatile Runnable sRealign;

    /**
     * 重新执行面板对齐。
     *
     * <p>用途：隐藏官方条目（设为 GONE）会改变官方内容的实际底边，
     * 而首次对齐发生在隐藏之前 → 会留下空白。隐藏后再次调用即可贴齐。</p>
     */
    static void realignNow() {
        Runnable r = sRealign;
        if (r == null) return;
        try {
            r.run();
        } catch (Throwable t) {
            Dumper.log("[PANEL] 重新对齐失败（已忽略）: " + t);
        }
    }

    /** 面板 Activity 类名后缀（R8 不会改动 Manifest 引用的组件名）。 */
    private static final String[] PANEL_SUFFIXES = {
            "DetailMainActivity",        // melody（ColorOS <16）
            "OneSpaceDetailActivity",    // melody OneSpace
            "BlueToothDetailActivity",   // mydevices（ColorOS 16 实际面板）
            "AirpodsSettingActivity",    // mydevices AirPods 设置页
            "AirpodsSettingMiddleActivity",
    };

    /** 是否对所有 Activity 都尝试注入（调试期开启，便于发现真实面板）。 */
    private static final boolean INJECT_ANY_ACTIVITY = true;

    /** 已记录过的 Activity 类名，避免重复打印。 */
    private static final List<String> SEEN_ACTIVITIES = new ArrayList<>();

    private PanelInjector() {}

    public static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                String cn = activity.getClass().getName();
                synchronized (SEEN_ACTIVITIES) {
                    if (!SEEN_ACTIVITIES.contains(cn)) {
                        SEEN_ACTIVITIES.add(cn);
                        Dumper.log("[UI] Activity 出现: " + cn
                                + (isPanel(activity) ? "   ← 面板候选" : ""));
                    }
                }
                if (!INJECT_ANY_ACTIVITY && !isPanel(activity)) return;
                View decor = activity.getWindow() == null
                        ? null : activity.getWindow().getDecorView();
                if (decor == null) return;

                // ★ Activity 级注入已停用（ACTIVITY_LEVEL_INJECTION=false）。
                //   原因：ColorOS 16 的面板是 COUIBottomSheetDialogFragment，
                //   正确注入点是弹窗内部的 contentLayout（见 injectIntoContainer）。
                //   而 Activity 级注入落在 android.R.id.content —— 位于弹窗【之外】，
                //   会在面板顶部漏出一条重复的控制区（实测用户反馈）。
                if (!ACTIVITY_LEVEL_INJECTION) {
                    Dumper.diag("[UI] 已跳过 Activity 级注入（改用弹窗内注入）: "
                            + activity.getClass().getName());
                    return;
                }
                decor.post(new Runnable() {
                    @Override public void run() {
                        try {
                            inject(activity, decor);
                        } catch (Throwable t) {
                            Dumper.log("[UI] 注入异常: " + t);
                        }
                    }
                });
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
        Dumper.log("[UI] 已注册 ActivityLifecycleCallbacks (any=" + INJECT_ANY_ACTIVITY + ")");
    }

    private static boolean isPanel(Activity a) {
        String n = a.getClass().getName();
        for (String s : PANEL_SUFFIXES) {
            if (n.endsWith(s)) return true;
        }
        return false;
    }

    /**
     * 注入到指定的容器（弹窗内部）。
     *
     * <p>与 {@link #injectNow(Activity)} 的区别：弹窗是 {@code COUIBottomSheetDialogFragment}，
     * 把控件放在 Activity 的 content 里会被弹窗当作"外部触摸"而 dismiss，
     * 因此必须注入到<em>弹窗自己的视图</em>里。</p>
     */
    public static void injectIntoContainer(ViewGroup container) {
        if (container == null) return;

        // ★ 总开关：信息页（Settings.Global）里可关闭控制面板注入
        if (!NhckConfig.enabled()) {
            Dumper.log("[PANEL] 控制面板已被设置项关闭，跳过注入");
            return;
        }

        // ★ 设备判断：非目标设备（车机 / 别家耳机…）一律不注入
        //   （信息页里的「强制注入所有设备」可绕过，用于调试）
        if (!sIsTargetDevice && !NhckConfig.forceTarget()) {
            Dumper.log("[PANEL] 非目标设备，跳过注入");
            return;
        }

        try {
            // ★ 单一实例守卫：必须同时满足
            //   ① 仍附加在当前窗口（isAttachedToWindow）
            //   ② 它的根视图就是本次要注入的这个容器所在的根视图
            //   否则说明是上一次面板遗留的实例（切界面复用时 getParent() 仍非 null，
            //   会导致旧实例飘在窗口层、而新面板没有注入 —— 实测出现过这个 bug）。
            View existing = sInjectedPanel;
            if (existing != null) {
                boolean attached = false;
                try {
                    attached = existing.isAttachedToWindow();
                } catch (Throwable ignored) {
                }
                boolean sameRoot = false;
                try {
                    View curRoot = container.getRootView();
                    sameRoot = (curRoot != null)
                            && (existing.getRootView() == curRoot)
                            && (existing.getParent() != null);
                } catch (Throwable ignored) {
                }
                if (attached && sameRoot) {
                    Dumper.diag("[PANEL] 守卫：实例仍附加于当前窗口 → 复用");
                    return;
                }
                Dumper.diag("[PANEL] 守卫：旧实例已失效（attached=" + attached
                        + " sameRoot=" + sameRoot + "）→ 移除并重建");
                try {
                    if (existing.getParent() instanceof ViewGroup) {
                        ((ViewGroup) existing.getParent()).removeView(existing);
                    }
                } catch (Throwable ignored) {
                }
                sInjectedPanel = null;
            }

            // 双保险：按 tag 在当前根视图里查（仅当确实附加在窗口上才复用）
            View root2 = container.getRootView();
            View byTag = (root2 == null) ? null : root2.findViewWithTag(VIEW_TAG);
            if (byTag != null) {
                boolean ok = false;
                try {
                    ok = byTag.isAttachedToWindow() && (byTag.getParent() != null);
                } catch (Throwable ignored) {
                }
                if (ok) {
                    Dumper.diag("[PANEL] 守卫：tag 命中且已附加 → 复用");
                    sInjectedPanel = byTag;
                    return;
                }
                Dumper.log("[PANEL] 守卫：tag 命中的实例未附加 → 忽略并重建");
            }
            Dumper.diag("[PANEL] 守卫：无有效既有实例 → 新建注入");

            // ★ 打印视图树（整体 try/catch —— 视图可能尚未 attach，getResources 等会抛异常）
            try {
                Dumper.log("");
                Dumper.diag("[PANEL] ================ 弹窗视图树 ================");
                dumpTree(container, 0, 0);
            } catch (Throwable t) {
                Dumper.diag("[PANEL] 视图树打印失败（已忽略）: " + t);
            }

            Dumper.log("[PANEL] 弹窗容器: " + container.getClass().getName()
                    + " children=" + container.getChildCount());

            // ---- 消除官方内容区尾部的空白 ----
            // 视图树 dump 显示 COUIRecyclerView 内有多个「空的 FrameLayout 占位」：
            //   #1 h=96  ← 电量与「已连接」之间的【间隔条】—— 必须保留！
            //   #5 h=144 ← 尾部空白
            //   #6 h=96  ← 尾部空白
            // ⚠️ 早期版本把所有空占位都压扁，连中间的间隔条也删了，
            //    导致「已连接」被顶到电量正下方（实测用户反馈）。
            //    现在只压扁「最后一个有内容子视图【之后】」的空占位。
            try {
                View recycler = findRecycler(container);
                if (recycler instanceof ViewGroup) {
                    ViewGroup rv = (ViewGroup) recycler;
                    Dumper.diag("[PANEL] recycler 高=" + rv.getHeight()
                            + " kids=" + rv.getChildCount());

                    // 先找出最后一个「有内容」的子视图下标
                    int lastContentIdx = -1;
                    for (int i = 0; i < rv.getChildCount(); i++) {
                        View ch = rv.getChildAt(i);
                        boolean empty = (ch instanceof ViewGroup)
                                && ((ViewGroup) ch).getChildCount() == 0;
                        if (!empty) lastContentIdx = i;
                    }
                    Dumper.diag("[PANEL] 最后一个有内容的子视图下标=" + lastContentIdx
                            + " / 共 " + rv.getChildCount() + " 个");

                    int collapsed = 0;
                    for (int i = lastContentIdx + 1; i < rv.getChildCount(); i++) {
                        View ch = rv.getChildAt(i);
                        if (!(ch instanceof ViewGroup)) continue;
                        ViewGroup cg = (ViewGroup) ch;
                        int h = cg.getHeight();
                        // 只处理尾部：空占位且高度可观
                        if (cg.getChildCount() == 0 && h > 8) {
                            ViewGroup.LayoutParams lp = cg.getLayoutParams();
                            if (lp != null && lp.height != 0) {
                                lp.height = 0;
                                cg.setLayoutParams(lp);
                                collapsed++;
                                Dumper.diag("[PANEL]   压扁尾部空占位 #" + i + " "
                                        + cg.getClass().getSimpleName()
                                        + " id=" + idNameOf(cg) + " 原高=" + h);
                            }
                        }
                    }
                    Dumper.diag("[PANEL] 共压扁 " + collapsed + " 个【尾部】空占位"
                            + "（中间的间隔条已保留）");
                    // 注意：不再收紧 list_container / recycler 高度 ——
                    // 实测那样会挤压官方内容，使我们的面板压到「音频设备设置」卡片上。
                    // 位置调节统一交给下面的 gapPx（空白高度）。
                }
            } catch (Throwable t) {
                Dumper.log("[PANEL] 消除尾部空白失败（已忽略）: " + t);
            }

            // ---- 精确测量：官方内容实际结束位置 / RecyclerView 内的空白高度 ----
            // 目的：把我们的面板上移"空白高度"，正好填掉空白而不压到官方卡片。
            int gapPx = 0;
            try {
                View recycler = findRecycler(container);
                if (recycler instanceof ViewGroup) {
                    ViewGroup rv = (ViewGroup) recycler;
                    int rvTopInWindow = locY(rv);
                    int rvBottomInWindow = rvTopInWindow + rv.getHeight();

                    // RecyclerView 内「最后一个有内容的子视图」的底边
                    int lastContentBottom = rvTopInWindow;
                    StringBuilder lines = new StringBuilder();
                    for (int i = 0; i < rv.getChildCount(); i++) {
                        View ch = rv.getChildAt(i);
                        int top = rvTopInWindow + ch.getTop();
                        int bottom = top + ch.getHeight();
                        boolean empty = (ch instanceof ViewGroup)
                                && ((ViewGroup) ch).getChildCount() == 0;
                        lines.append("\n    #").append(i).append(' ')
                             .append(ch.getClass().getSimpleName())
                             .append(" id=").append(idNameOf(ch))
                             .append(" top=").append(ch.getTop())
                             .append(" h=").append(ch.getHeight())
                             .append(" vis=").append(ch.getVisibility())
                             .append(empty ? " [空]" : "");
                        if (!empty && bottom > lastContentBottom) lastContentBottom = bottom;
                    }
                    Dumper.diag("[PANEL] recycler 位置: topInWindow=" + rvTopInWindow
                            + " h=" + rv.getHeight() + " bottomInWindow=" + rvBottomInWindow
                            + " 最后有内容子视图底边=" + lastContentBottom);
                    Dumper.diag("[PANEL] recycler 子视图明细:" + lines);

                    gapPx = rvBottomInWindow - lastContentBottom;
                    if (gapPx < 0) gapPx = 0;
                    Dumper.diag("[PANEL] ★ 可填补空白高度 gapPx=" + gapPx);
                }
            } catch (Throwable t) {
                Dumper.log("[PANEL] 空白测量失败（已忽略）: " + t);
            }

            final View panel = buildPanel(container.getContext(), container);

            // ---- 定位：把面板放进官方内容流内，紧跟 RecyclerView ----
            // 实测弹窗结构：… → LinearLayout(contentLayout) → CoordinatorLayout
            //                  → FrameLayout(list_container) → COUIRecyclerView(recycler_view)
            // 我们的面板应作为 CoordinatorLayout 的下一个兄弟（RecyclerView 之后），
            // 这样它会紧贴官方内容、填掉卡片下方的空白，且不额外抬高官方面板。
            ViewGroup host = null;
            int insertIndex = -1;

            try {
                View recycler = findRecycler(container);
                if (recycler != null && recycler.getParent() instanceof ViewGroup) {
                    ViewGroup co = (ViewGroup) recycler.getParent();     // list_container
                    if (co.getParent() instanceof ViewGroup) {
                        ViewGroup coord = (ViewGroup) co.getParent();    // CoordinatorLayout
                        // 再往上一层是 contentLayout（LinearLayout）——官方内容流所在
                        if (coord.getParent() instanceof LinearLayout) {
                            host = (LinearLayout) coord.getParent();
                            insertIndex = host.indexOfChild(coord) + 1;
                            Dumper.log("[PANEL] 定位策略：插入 contentLayout（官方内容流）index="
                                    + insertIndex + " host=" + host.getClass().getSimpleName());
                        } else {
                            host = coord;
                            insertIndex = coord.indexOfChild(co) + 1;
                            Dumper.log("[PANEL] 定位策略：插入 CoordinatorLayout index=" + insertIndex);
                        }
                    }
                }
            } catch (Throwable t) {
                Dumper.log("[PANEL] 定位计算失败: " + t);
            }

            if (host == null) {
                host = pickFlexibleHost(container);
                insertIndex = -1;
                Dumper.log("[PANEL] 定位策略：回退到默认宿主 "
                        + host.getClass().getSimpleName());
            }

            // 放进官方内容流：包一层限高滚动，保证功能开关可达（不撑爆弹窗）
            // ★ 不再包自己的 ScrollView ——
            //   实测：面板内嵌 ScrollView 会与官方 RecyclerView 形成【两套滚动】，
            //   同一页上下滑动行为割裂（用户反馈）。
            //   现在让面板直接融入官方内容流，由官方的滚动统一接管。
            // 面板本体：包一层 ScrollView（高度稍后按"剩余空间"动态计算）。
            // 目的：我们的面板不在官方滚动流内（是 RecyclerView 的兄弟节点），
            // 若不给自己滚动能力，超出屏幕的部分将永久够不到。
            // 高度策略见下方 post 对齐逻辑：min(内容高, 剩余空间) ——
            // 放得下就不出现滚动条（视觉上等价于一屏显示完），放不下才内部滚动。
            View toAdd = wrapScrollable(container.getContext(), panel);
            toAdd.setTag(VIEW_TAG);
            sInjectedPanel = toAdd;
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);

            if (insertIndex >= 0) {
                host.addView(toAdd, insertIndex, lp);
            } else {
                host.addView(toAdd, lp);
            }

            // 位置完全交给下面的「布局后对齐」（computeAlignShift），
            // 不再用固定负 margin —— 实测固定值会偏移过大而压住官方卡片。

            // ★ 位置微调：布局完成后自动对齐。
            //   之前用 gapPx(240) 做固定负 margin，实测偏移过大（压住「音频设备设置」）。
            //   改为「布局后测量」——把面板下移到官方最后一行内容的底边之下，
            //   只留固定间距，既填空白又不重叠。
            //   间距取 28dp：10dp 时面板顶边仍会压到弹窗的内容裁剪边界，
            //   露出弹窗外遮罩层（表现为顶部一条浅灰，与官方底色不一致）。
            //   间距取 10dp：用户要求继续上移，只留最小间隔不与官方卡片贴死。
            final int fixedGap = dp(container.getContext(), 10);
            final View finalPanel = panel;
            final Runnable alignTask = new Runnable() {
                @Override public void run() {
                    try {
                        int shift = computeAlignShift(toAdd, fixedGap);
                        if (shift != 0) {
                            toAdd.setTranslationY(toAdd.getTranslationY() + shift);
                            Dumper.log("[PANEL] ✓ 对齐微调 translationY += " + shift);
                        } else {
                            Dumper.diag("[PANEL] 对齐微调：无需移动");
                        }
                    } catch (Throwable t) {
                        Dumper.log("[PANEL] 对齐微调失败: " + t);
                    }
                }
            };
            sRealign = alignTask;
            toAdd.post(alignTask);

            Dumper.log("[PANEL] ✓ 已注入（官方内容流内）: " + host.getClass().getSimpleName()
                    + " (id=" + idNameOf(host) + ") index=" + insertIndex);

            // ★ 诊断：向上打印祖先链（用于确定弹窗 Behavior 挂在哪一层）
            try {
                Dumper.log("");
                Dumper.log("[PANEL] ================ 弹窗祖先链（向上） ================");
                View cur = container;
                for (int up = 0; up < 10 && cur != null; up++) {
                    ViewGroup.LayoutParams alp = cur.getLayoutParams();
                    String lpDesc = (alp == null) ? "null"
                            : alp.getClass().getSimpleName() + "(h=" + alp.height
                              + ",w=" + alp.width + ")";
                    String beh = "none";
                    if (alp != null) {
                        try {
                            java.lang.reflect.Method g = alp.getClass().getMethod("getBehavior");
                            Object b = g.invoke(lp);
                            if (b != null) beh = b.getClass().getName();
                        } catch (Throwable ignored) {
                        }
                    }
                    Dumper.diag("[PANEL]   ^" + up + " "
                            + cur.getClass().getName()
                            + " id=" + idNameOf(cur)
                            + " " + cur.getWidth() + "x" + cur.getHeight()
                            + " lp=" + lpDesc
                            + " behavior=" + beh);
                    cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
                }
            } catch (Throwable t) {
                Dumper.diag("[PANEL] 祖先链打印失败（已忽略）: " + t);
            }

            // ---- 展开底部弹窗：已停用 ----
            // 实测副作用：展开会改变布局基准，使下方「布局后对齐」(translationY)
            // 计算失准 → 面板被顶到弹窗上方、中间出现大片空白。
            // 因此回退到未展开状态（对齐逻辑正是基于该状态验证通过的）。
            // try { expandBottomSheet(container); } catch (Throwable t) { ... }

            // 把官方面板里的「通用耳机图」替换为原道官方产品图。
            // 延后到布局完成后再做：该 ImageView 由宿主在数据绑定阶段设置图片，
            // 过早替换会被宿主覆盖。
            final View panelRoot = container.getRootView();
            final Context hostCtx = container.getContext();
            toAdd.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        DeviceImage.replaceHeadsetImage(hostCtx, panelRoot);
                    } catch (Throwable t) {
                        Dumper.log("[IMG] post 替换失败: " + t);
                    }
                }
            }, 800);

            // 隐藏官方无意义条目（「通用设置」+「音频设备设置」）
            try {
                OfficialEntryHider.scheduleHide(panelRoot);
            } catch (Throwable t) {
                Dumper.log("[HIDE] 调度失败: " + t);
            }
        } catch (Throwable t) {
            Dumper.log("[PANEL] injectIntoContainer 失败（已忽略）: " + t);
        }
    }

    /** 递归打印视图树（限深限宽），带 id/尺寸/子数/是否可点击。 */
    private static void dumpTree(View v, int depth, int index) {
        try {
            if (depth > 14) return;
            StringBuilder sb = new StringBuilder("[PANEL] ");
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append('#').append(index).append(' ')
              .append(v.getClass().getSimpleName())
              .append(" id=").append(idNameOf(v))
              .append(" ").append(v.getWidth()).append('x').append(v.getHeight())
              .append(" vis=").append(v.getVisibility())
              .append(v.isClickable() ? " CLICKABLE" : "")
              .append(v instanceof ViewGroup ? (" kids=" + ((ViewGroup) v).getChildCount()) : "");
            Dumper.diag(sb.toString());
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = Math.min(g.getChildCount(), 12);
                for (int i = 0; i < n; i++) {
                    dumpTree(g.getChildAt(i), depth + 1, i);
                }
            }
        } catch (Throwable t) {
            Dumper.diag("[PANEL] dumpTree 节点异常（已忽略）: " + t.getClass().getSimpleName());
        }
    }

    private static String idNameOf(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "none";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(v.getId());
        }
    }

    /**
     * 弹窗内部的容器选择（比 Activity 侧宽松）。
     *
     * <p>弹窗视图通常是 {@code FrameLayout/LinearLayout} + 内部的
     * {@code RecyclerView}（Preference 列表）。优先选：
     * 有 id 的容器 → 子 View 最多的容器 → 传入容器本身。</p>
     */
    /**
     * 弹窗内部的容器选择。
     *
     * <p>实测弹窗结构（来自视图树 dump）：
     * <pre>
     * COUIPanelContentLayout id=coui_panel_content_layout
     *  └ LinearLayout id=panel_content
     *     └ LinearLayout
     *        ├ COUIToolbar id=bottom_sheet_toolbar
     *        └ FrameLayout id=panel_container
     *           └ ConstraintLayout
     *              └ FrameLayout id=onespace_item_list
     *                 └ ConstraintLayout
     *                    └ LinearLayout id=contentLayout      ← ★ 安全的普通容器
     *                       └ CoordinatorLayout
     *                          ├ FrameLayout id=list_container
     *                          │  └ COUIRecyclerView id=recycler_view   ← 不能直接插
     *                          └ AppCompatTextView id=empty
     * </pre></p>
     *
     * <p><b>关键</b>：绝不能把 View 直接加进 {@code RecyclerView} 的 children ——
     * 它的子视图由 LayoutManager + Adapter 管理，手插非 adapter 项会导致
     * 布局错乱/被回收/不显示。必须插到它的<em>普通 ViewGroup 父级</em>。</p>
     */
    /**
     * 计算面板需要下移的像素数，使其恰好位于「官方最后一行内容」之下。
     *
     * <p>做法：找到官方面板中最后一个<em>有内容的</em>可见子视图（即「音频设备设置」卡片），
     * 取它的底边；若面板顶边高于该底边 + 间距，则需要下移。</p>
     *
     * @return 需要下移的像素（正数=下移，0=无需移动）
     */
    private static int computeAlignShift(View panel, int gap) {
        View root = panel.getRootView();
        if (root == null) return 0;

        int[] p = new int[2];
        panel.getLocationInWindow(p);
        int panelTop = p[1];

        // 找 RecyclerView 内最后一个有内容的子视图的底边（窗口坐标）
        View recycler = findRecycler((ViewGroup) root);
        if (recycler == null) return 0;
        int[] r = new int[2];
        recycler.getLocationInWindow(r);
        int rvTop = r[1];

        int lastBottom = rvTop;
        if (recycler instanceof ViewGroup) {
            ViewGroup rv = (ViewGroup) recycler;
            for (int i = 0; i < rv.getChildCount(); i++) {
                View ch = rv.getChildAt(i);
                if (ch.getVisibility() != View.VISIBLE) continue;
                if (ch instanceof ViewGroup && ((ViewGroup) ch).getChildCount() == 0) continue;
                int bottom = rvTop + ch.getTop() + ch.getHeight();
                if (bottom > lastBottom) lastBottom = bottom;
            }
        }

        int wantTop = lastBottom + gap;
        int shift = wantTop - panelTop;
        Dumper.diag("[PANEL] 对齐测量: panelTop=" + panelTop
                + " 官方内容底边=" + lastBottom + " 目标top=" + wantTop
                + " 需移动=" + shift);
        return shift;
    }

    /**
     * 把 ColorOS 底部弹窗展开为全屏。
     *
     * <p><b>实测方法名（非 androidx 命名；之前按 androidx 猜名导致全部失败）</b>：
     * <pre>
     * com.coui.appcompat.panel.COUIBottomSheetBehavior
     *     static from(View)          // 从视图取 Behavior
     *     setPanelPeekHeight(int)    // 不是 setPeekHeight
     *     setPanelState(int)         // 不是 setState
     *     setFitToContents(boolean)
     * com.coui.appcompat.panel.COUIPanelPercentFrameLayout   // design_bottom_sheet 本体
     *     setMaxHeight(int)
     * </pre></p>
     */
    private static void expandBottomSheet(View container) {
        View root = container.getRootView();
        if (root == null) return;
        Context ctx = container.getContext();
        final String HOST = "com.heytap.mydevices";

        int dockedId = 0;
        try {
            dockedId = ctx.getResources().getIdentifier(
                    "design_bottom_sheet", "id", HOST);
        } catch (Throwable ignored) {
        }
        View docked = (dockedId != 0) ? root.findViewById(dockedId) : null;
        Dumper.diag("[PANEL] 展开尝试: docked(design_bottom_sheet)=" + docked);
        if (docked == null) return;

        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;

        // ---- ① COUIBottomSheetBehavior.from(docked) ----
        Object behavior = null;
        try {
            Class<?> bc = Class.forName("com.coui.appcompat.panel.COUIBottomSheetBehavior");
            java.lang.reflect.Method from = bc.getMethod("from", View.class);
            behavior = from.invoke(null, docked);
            Dumper.log("[PANEL] COUIBottomSheetBehavior.from() -> " + behavior);
        } catch (Throwable t) {
            Dumper.log("[PANEL] Behavior.from 失败: " + t);
        }

        if (behavior != null) {
            try {
                java.lang.reflect.Method m =
                        behavior.getClass().getMethod("setPanelPeekHeight", int.class);
                m.invoke(behavior, screenH);
                Dumper.log("[PANEL] ✓ setPanelPeekHeight(" + screenH + ")");
            } catch (Throwable t) {
                Dumper.log("[PANEL] setPanelPeekHeight 失败: " + t.getClass().getSimpleName());
            }
            try {
                java.lang.reflect.Method m =
                        behavior.getClass().getMethod("setFitToContents", boolean.class);
                m.invoke(behavior, true);
                Dumper.log("[PANEL] ✓ setFitToContents(true)");
            } catch (Throwable t) {
                Dumper.log("[PANEL] setFitToContents 失败: " + t.getClass().getSimpleName());
            }
            try {
                java.lang.reflect.Method m =
                        behavior.getClass().getMethod("setPanelState", int.class);
                m.invoke(behavior, 3);
                Dumper.log("[PANEL] ✓ setPanelState(3=EXPANDED)");
            } catch (Throwable t) {
                Dumper.log("[PANEL] setPanelState 失败: " + t.getClass().getSimpleName());
            }
        }

        // ---- ② COUIPanelPercentFrameLayout.setMaxHeight(屏高) ----
        try {
            java.lang.reflect.Method m = docked.getClass().getMethod("setMaxHeight", int.class);
            m.invoke(docked, screenH);
            Dumper.log("[PANEL] ✓ setMaxHeight(" + screenH + ")");
        } catch (Throwable t) {
            Dumper.log("[PANEL] setMaxHeight 失败: " + t.getClass().getSimpleName());
        }
        try {
            ViewGroup.LayoutParams lp = docked.getLayoutParams();
            if (lp != null && lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                docked.setLayoutParams(lp);
                Dumper.log("[PANEL] ✓ design_bottom_sheet lp.height = MATCH_PARENT");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 视图在窗口中的 Y 坐标。 */
    private static int locY(View v) {
        int[] xy = new int[2];
        try {
            v.getLocationInWindow(xy);
        } catch (Throwable t) {
            return 0;
        }
        return xy[1];
    }

    /** 反射调用 protected 的 {@code View#computeVerticalScrollRange()}。 */
    private static int scrollRange(View v) {
        try {
            java.lang.reflect.Method m =
                    View.class.getDeclaredMethod("computeVerticalScrollRange");
            m.setAccessible(true);
            Object r = m.invoke(v);
            return (r instanceof Integer) ? (Integer) r : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static ViewGroup pickFlexibleHost(ViewGroup root) {
        View recycler = findRecycler(root);
        if (recycler != null) {
            // 打印从 RecyclerView 向上的完整父级链 —— 便于精确选层
            StringBuilder chain = new StringBuilder();
            View cur = recycler;
            ViewGroup chosen = null;
            for (int up = 0; up < 7 && cur != null; up++) {
                chain.append(up).append('=')
                     .append(cur.getClass().getSimpleName())
                     .append('(').append(idNameOf(cur)).append(')');
                if (cur.getParent() instanceof ViewGroup) {
                    chain.append(" → ");
                    ViewGroup p = (ViewGroup) cur.getParent();
                    // 选第 3 层父级（实测为 LinearLayout id=contentLayout / CoordinatorLayout）
                    if (up == 2) chosen = p;
                    cur = p;
                } else {
                    break;
                }
            }
            Dumper.log("[PANEL] RecyclerView 父级链: " + chain);
            if (chosen != null) {
                Dumper.log("[PANEL] 选定父级第 3 层: " + chosen.getClass().getSimpleName()
                        + " (id=" + idNameOf(chosen) + ") kids=" + chosen.getChildCount());
                return chosen;
            }
            if (recycler.getParent() instanceof ViewGroup) {
                return (ViewGroup) recycler.getParent();
            }
        }

        // ① 直接可用：子 View 数量适中的 ViewGroup（弹窗根容器）
        if (root.getChildCount() > 0 && root.getChildCount() < 12) return root;

        // ② 广度优先找一个"最像内容区"的容器（避开 RecyclerView）
        ViewGroup best = root;
        int bestScore = -1;
        java.util.ArrayDeque<ViewGroup> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 80) {
            ViewGroup g = queue.poll();
            int score = 0;
            if (g.getId() != View.NO_ID) score += 10;
            if (g.getChildCount() > 0) score += 5;
            if (g.getChildCount() < 10) score += 3;
            String sn = g.getClass().getSimpleName();
            if (sn.contains("ScrollView")) score += 6;
            if (sn.contains("RecyclerView")) score -= 100;
            if (score > bestScore) { bestScore = score; best = g; }
            for (int i = 0; i < g.getChildCount() && i < 20; i++) {
                View ch = g.getChildAt(i);
                if (ch instanceof ViewGroup) queue.add((ViewGroup) ch);
            }
        }
        return best;
    }

    /** 广度优先找第一个 RecyclerView（用于定位其父级容器）。 */
    private static View findRecycler(View root) {
        if (!(root instanceof ViewGroup)) return null;
        java.util.ArrayDeque<ViewGroup> queue = new java.util.ArrayDeque<>();
        queue.add((ViewGroup) root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 120) {
            ViewGroup g = queue.poll();
            String sn = g.getClass().getSimpleName();
            if (sn.contains("RecyclerView")) return g;
            for (int i = 0; i < g.getChildCount() && i < 20; i++) {
                View ch = g.getChildAt(i);
                if (ch instanceof ViewGroup) queue.add((ViewGroup) ch);
            }
        }
        return null;
    }

    /**
     * 直接对指定 Activity 执行注入。
     *
     * <p>供 Activity 类级 Hook 调用 —— 比 {@code registerActivityLifecycleCallbacks}
     * 可靠，因为后者在 Activity 已 resume 时收不到事件。</p>
     */
    public static void injectNow(Activity activity) {
        if (activity == null) return;
        // 把 Activity 的 Context 配给链路层 —— 某些进程里 Application context
        // 取不到 BluetoothManager，Activity context 更可靠。
        try {
            NhckLink.get().attach(activity);
        } catch (Throwable ignored) {}

        // ★ Activity 级注入已停用：ColorOS 16 的正确注入点是弹窗内部
        //   （见 injectIntoContainer）。此处仅借 Activity 拿 Context。
        if (!ACTIVITY_LEVEL_INJECTION) {
            Dumper.diag("[UI] injectNow: 仅附加 Context，跳过 Activity 级注入");
            return;
        }

        View decor = activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
        if (decor == null) return;
        inject(activity, decor);
    }

    /**
     * 是否启用「Activity 级注入」。
     *
     * <p><b>必须保持 false</b>：ColorOS 16 的面板是
     * {@code COUIBottomSheetDialogFragment}，正确注入点是<b>弹窗内部</b>的
     * {@code contentLayout}。Activity 级注入落在 {@code android.R.id.content}，
     * 位于弹窗之外 → 会在面板顶部漏出一条重复的控制区（实测）。</p>
     */
    private static final boolean ACTIVITY_LEVEL_INJECTION = false;

    private static void inject(Activity activity, View decor) {
        // 允许「重新定位」：onCreate 时容器还空着（Fragment 未填充），
        // onResume 时容器已就绪。如果已注入但找到了更好的宿主，就搬过去。
        View existing = decor.findViewWithTag(VIEW_TAG);

        Dumper.log("");
        Dumper.log("[UI] 检测到面板: " + activity.getClass().getName()
                + (existing != null ? "（已有注入，尝试重新定位）" : ""));

        // 1) 扫描并打印容器候选
        List<ViewGroup> candidates = new ArrayList<>();
        collectGroups(decor, candidates, 0);
        Dumper.log("[UI] ViewGroup 候选数: " + candidates.size());
        for (int i = 0; i < candidates.size() && i < 25; i++) {
            ViewGroup g = candidates.get(i);
            Dumper.log(String.format("[UI]   #%d %s  children=%d  area=%d  id=%s",
                    i, g.getClass().getSimpleName(), g.getChildCount(),
                    g.getWidth() * g.getHeight(),
                    idName(activity, g)));
        }

        // 2) 选最佳容器（收紧条件，避免往无关界面乱注入）
        ViewGroup host = pickHost(decor, candidates);
        if (host == null) {
            Dumper.log("[UI] ✗ 无合适容器，跳过该 Activity");
            return;
        }

        // 3) 如果已注入且宿主未变，直接返回（幂等）
        if (existing != null && existing.getParent() == host) {
            Dumper.log("[UI] 宿主未变，跳过重复注入");
            return;
        }
        if (existing != null && existing.getParent() instanceof ViewGroup) {
            ((ViewGroup) existing.getParent()).removeView(existing);
            Dumper.log("[UI] 已从旧宿主移除，准备重新注入");
        }

        Dumper.log("[UI] 选中容器: " + host.getClass().getName() + " children=" + host.getChildCount());

        // 4) 构建并插入
        View panel = (existing != null) ? existing : buildPanel(activity, null);
        try {
            host.addView(panel);
            Dumper.log("[UI] ✓ 面板已注入");
        } catch (Throwable t) {
            Dumper.log("[UI] ✗ addView 失败: " + t);
        }
    }

    private static void collectGroups(View v, List<ViewGroup> out, int depth) {
        if (depth > 30) return;
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        out.add(g);
        for (int i = 0; i < g.getChildCount(); i++) {
            collectGroups(g.getChildAt(i), out, depth + 1);
        }
    }

    private static ViewGroup pickHost(View root, List<ViewGroup> candidates) {
        // ① 最强信号：ScrollView 内容区（设置页/详情页几乎必有）
        for (ViewGroup g : candidates) {
            if (g instanceof ScrollView) {
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c instanceof ViewGroup && ((ViewGroup) c).getChildCount() > 0) {
                        return (ViewGroup) c;
                    }
                }
            }
        }
        // ② id=content 的 ContentFrameLayout —— 承载 Fragment 的内容容器
        //    （实测在 BlueToothDetailActivity#onCreate 时它是空的，Fragment 稍后填充；
        //      因此必须允许「空容器」，否则会误判为无可用容器）
        for (ViewGroup g : candidates) {
            int id = g.getId();
            if (id == android.R.id.content) return g;
            if ("content".equals(nameOf(g))) return g;
        }
        // ③ 面积足够大且子 View 数量 >= 3 的 ViewGroup
        ViewGroup best = null;
        int bestArea = 0;
        for (ViewGroup g : candidates) {
            if (g.getChildCount() < 3) continue;
            int area = g.getWidth() * g.getHeight();
            if (area <= 0) continue;
            if (area > bestArea) { bestArea = area; best = g; }
        }
        if (best != null) return best;
        // ④ 兜底：面积最大、已测量且有子 View 的容器（排除 DecorView 本身）
        for (ViewGroup g : candidates) {
            if (g.getChildCount() == 0) continue;
            String sn = g.getClass().getSimpleName();
            if ("DecorView".equals(sn)) continue;
            int area = g.getWidth() * g.getHeight();
            if (area > bestArea) { bestArea = area; best = g; }
        }
        return best;
    }

    /** 通过资源名反查 View 的 id 名（部分容器没有 android.R.id.content 但资源名为 content）。 */
    private static String nameOf(View v) {
        try {
            Context c = v.getContext();
            int id = v.getId();
            if (id == View.NO_ID) return "none";
            return c.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "none";
        }
    }

    private static String idName(Context ctx, View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "none";
        try { return ctx.getResources().getResourceEntryName(id); }
        catch (Throwable t) { return "0x" + Integer.toHexString(id); }
    }

    // ------------------------------------------------------------------
    // 控件构建
    // ------------------------------------------------------------------

    /** 缓存：从官方面板视图上实测到的背景色。 */
    private static volatile int sSampledPanelBg = 0;

    /**
     * 面板底色 —— 直接**从官方面板视图上采样**，保证与官方完全一致。
     *
     * <p>采样顺序：
     * ① {@code coui_panel_content_layout} / {@code panel_content} / {@code panel_container}
     * 等官方弹窗容器的背景色
     * ② 命名资源回退 {@code coui_color_background_with_card} / {@code coui_color_background}
     * ③ 最终回退 {@code #F0F1F3}</p>
     *
     * @param container 官方弹窗内的容器（用它取根视图来采样）
     */
    private static int panelBgColor(Context ctx, View container) {
        if (sSampledPanelBg != 0) return sSampledPanelBg;
        final int fallback = 0xFFF0F1F3;
        try {
            View root = (container != null) ? container.getRootView() : null;
            if (root != null) {
                int sampled = samplePanelBg(root);
                if (sampled != 0) {
                    sSampledPanelBg = sampled;
                    Dumper.log("[UI] ★ 面板底色采样自官方面板 = #"
                            + String.format("%08X", sampled));
                    return sampled;
                }
                Dumper.log("[UI] 官方面板未找到纯色背景，改用命名资源");
            }
        } catch (Throwable t) {
            Dumper.log("[UI] 底色采样失败: " + t);
        }
        for (String name : new String[]{
                "coui_color_background_with_card",
                "coui_color_background",
                "coui_color_surface",
                "airpods_background_color"}) {
            try {
                int id = ctx.getResources().getIdentifier(name, "color", "com.heytap.mydevices");
                if (id == 0) {
                    id = ctx.getResources().getIdentifier(name, "color", ctx.getPackageName());
                }
                if (id != 0) {
                    int c = ctx.getColor(id);
                    if (c != 0) {
                        sSampledPanelBg = c;
                        Dumper.log("[UI] 面板底色取自资源 " + name
                                + " = #" + String.format("%08X", c));
                        return c;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Dumper.log("[UI] 面板底色使用回退值 #" + String.format("%08X", fallback));
        return fallback;
    }

    /** 在官方视图树里找一个「不透明背景」并返回其颜色。 */
    private static int samplePanelBg(View v) {
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String nm = null;
                try {
                    nm = v.getResources().getResourceEntryName(id);
                } catch (Throwable ignored) {
                }
                if (nm != null && (nm.contains("panel_content") || nm.contains("panel_container")
                        || nm.contains("coui_panel"))) {
                    int c = colorOf(v);
                    if (c != 0) {
                        Dumper.diag("[UI]   采样候选 " + nm + " -> #" + String.format("%08X", c));
                        return c;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    int c = samplePanelBg(g.getChildAt(i));
                    if (c != 0) return c;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 取视图背景的纯色（非纯色/透明返回 0）。 */
    private static int colorOf(View v) {
        try {
            android.graphics.drawable.Drawable d = v.getBackground();
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) d).getColor();
                if (android.graphics.Color.alpha(c) == 255) return c;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /**
     * 弹窗内注入用的「限高滚动」容器。
     *
     * <p>实测：把整块内容直接 addView 进弹窗底部容器时，区块高度会超出
     * {@code COUIBottomSheetDialogFragment} 的可视区 → 底部的功能开关被裁掉。
     * 因此包一层 {@link ScrollView} 并限制最大高度（约屏高 45%）。</p>
     */
    private static View wrapScrollable(Context ctx, View content) {
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(false);
        sv.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        // 80%：完整容纳全部内容（标题+状态行+4 档降噪+5 项 EQ+4 个开关+查询按钮）。
        // 72% 时底部「重新查询状态」仍会被裁掉。
        int maxH = (int) (screenH * 0.80f);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));
        return sv;
    }

    private static View buildPanel(final Context ctx, final View container) {
        final NhckLink link = NhckLink.get();

        LinearLayout box = new LinearLayout(ctx);
        box.setTag(VIEW_TAG);
        box.setOrientation(LinearLayout.VERTICAL);
        // 压缩内边距，把垂直空间留给控件本身
        box.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        // 底色：与官方面板一致（#F0F1F2，采样自 coui_panel_content_layout）。
        //
        // 注意：官方面板底色是【实心】的；早期版本曾改成"全透明"以期透出官方模糊层，
        // 实测反而呈现"像被磨砂"的脏感（露出下层模糊），因此这里恢复为实心底色。
        box.setBackgroundColor(panelBgColor(ctx, container));

        // 触摸事件观测：如果连 ACTION_DOWN 都收不到，说明被上层弹窗拦截
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                Dumper.log("[UI] box touch action=" + e.getAction());
                return false;   // 不消费，继续传递
            }
        });

        // 信息卡片（两行排版）：
        //   行1：第三方注入（Unofficial）
        //   行2：降噪：深度降噪；EQ：均衡中正；固件：4.08
        LinearLayout infoCard = OfficialStylePanel.card(ctx);

        int darkColor = 0;
        try {
            int id = ctx.getResources().getIdentifier(
                    "coui_color_label_primary", "color", "com.heytap.mydevices");
            if (id == 0) {
                id = ctx.getResources().getIdentifier(
                        "coui_color_label_primary", "color", ctx.getPackageName());
            }
            if (id != 0) darkColor = ctx.getColor(id);
        } catch (Throwable ignored) {
        }
        final int labelColor = (darkColor != 0) ? darkColor : 0xFF1A1A1A;
        final int bodyColor = 0xFF757575;   // text_secondary 同色

        // 行1：标签（单独一行）
        TextView verTag = new TextView(ctx);
        verTag.setText("第三方注入（Unofficial）");
        verTag.setTextSize(13f);
        verTag.setAlpha(1f);
        verTag.setTextColor(labelColor);
        infoCard.addView(verTag);

        // 行2：降噪 / EQ / 固件（统一「键：值；」格式）
        final TextView status = new TextView(ctx);
        status.setTextSize(14f);
        status.setPadding(0, dp(ctx, 4), 0, 0);
        status.setTextColor(bodyColor);
        infoCard.addView(status);
        box.addView(infoCard);

        // 行2 内容的刷新函数（首次与每次状态回读都调用）
        final Runnable refreshInfo = new Runnable() {
            @Override public void run() {
                NhckLink l = link;
                status.setText("降噪：" + NhckProtocol.ancName(l.ancMode)
                        + "；EQ：" + NhckProtocol.eqName(l.eqMode)
                        + "；固件：" + (l.firmwareMain < 0 ? "?" : l.firmwareMain)
                        + "." + (l.firmwareSub < 0 ? "??"
                        : String.format(java.util.Locale.US, "%02d", l.firmwareSub)));
            }
        };
        refreshInfo.run();

        // ---- 降噪：官方风格（白卡片承载 + 蓝/灰圆底图标）----
        try {
            LinearLayout ancCard = OfficialStylePanel.card(ctx);
            ancCard.addView(OfficialStylePanel.buildAncRow(ctx, link));
            box.addView(ancCard);
        } catch (Throwable t) {
            Dumper.log("[UI] 官方风格降噪控件构建失败，降级为文字按钮: " + t);
            box.addView(fallbackAncRow(ctx, link));
        }

        // ---- EQ：官方风格（同一张卡片样式）----
        try {
            LinearLayout eqCard = OfficialStylePanel.card(ctx);
            eqCard.addView(OfficialStylePanel.buildEqRow(ctx, link));
            box.addView(eqCard);
        } catch (Throwable t) {
            Dumper.log("[UI] 官方风格 EQ 控件构建失败: " + t);
        }

        // ---- 功能开关（游戏模式 / 入耳检测 / 双连接 / 抗风噪）----
        try {
            box.addView(OfficialStylePanel.buildFeatureSwitches(ctx, link));
        } catch (Throwable t) {
            Dumper.log("[UI] 功能开关构建失败: " + t);
        }

        // ---- 手动刷新按钮：已移除 ----
        // 改为自动刷新（面板可见期间每 10 分钟查询一次，见 NhckLink#startAutoRefresh）。

        // ---- 状态联动 ----
        final NhckLink.Listener listener = new NhckLink.Listener() {
            @Override public void onState(NhckLink l) {
                status.post(refreshInfo);
            }
        };
        link.addListener(listener);
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                refreshInfo.run();
                // 面板可见期间开启自动刷新（每 10 分钟一次）
                link.startAutoRefresh();
            }
            @Override public void onViewDetachedFromWindow(View v) {
                link.removeListener(listener);
                link.stopAutoRefresh();
                // 显式断开 SPP：RFCOMM 是独占资源，面板关闭后应释放，
                // 这样官方 NiceHCK App 等其它程序才能正常连接耳机。
                try {
                    link.close();
                    Dumper.log("[Link] 面板关闭 → 已请求断开 SPP");
                } catch (Throwable t) {
                    Dumper.log("[Link] 断开失败（已忽略）: " + t);
                }
            }
        });

        // 面板出现即确保连接
        link.connectAsync();

        return box;
    }

    /** 降级方案：宿主 drawable 缺失时用纯文字按钮（功能不受影响）。 */
    private static View fallbackAncRow(final Context ctx, final NhckLink link) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final String[] labels = {"降噪", "通透", "关闭"};
        final int[] vals = {NhckProtocol.ANC_NORMAL, NhckProtocol.ANC_TRANSPARENT,
                NhckProtocol.ANC_OFF};
        for (int i = 0; i < vals.length; i++) {
            final Button b = new Button(ctx);
            final String label = labels[i];
            b.setText(label);
            b.setTextSize(12f);
            final int v = vals[i];
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    Dumper.log("[UI] 点击 降噪=" + NhckProtocol.ancName(v));
                    b.setText(label + "✓");
                    link.send(NhckProtocol.setAnc(v), "SET_ANC " + NhckProtocol.ancName(v));
                }
            });
            row.addView(b, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private static Button mkToggle(Context ctx, String label, boolean[] state, final Runnable action) {        Button b = new Button(ctx);
        b.setText(label);
        b.setTextSize(12f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }
}
