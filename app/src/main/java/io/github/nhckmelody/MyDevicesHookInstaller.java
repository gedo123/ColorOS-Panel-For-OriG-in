package io.github.nhckmelody;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * mydevices（com.heytap.mydevices）侦察 + 数据层接入。
 *
 * <p>ColorOS 16 上蓝牙设备详情面板已从 melody 迁移到 mydevices：
 * {@code com.heytap.mydevices/com.oplus.mydevices.bluetooth.BlueToothDetailActivity}。</p>
 *
 * <p>关键发现：{@code core/bluetooth/AirpodsStatusObserver} 是 AirPods 状态中枢，
 * 方法名<em>未混淆</em>，形成一套可复用的具名回调契约：
 * {@code StatusCallback.onBatteryLevelChanged/onNoiseModeChanged/onWearStatusChanged}
 * 与出站口 {@code notifyBatteryLevelChanged/notifyNoiseModeChanged/...}。</p>
 *
 * <p>本策略先用「Hook 全部具名方法 + 反射 dump 参数与返回值」把真实签名与数据模型摸清，
 * 再据此接入 NiceHCK 数据。</p>
 */
public final class MyDevicesHookInstaller {

    private static final String TAG = Dumper.TAG;

    /** 要侦察/拦截的类（方法名未混淆，反射枚举即可）。 */
    private static final String[] TARGETS = {
            // ---- 面板 Activity（ColorOS 16 的设备详情页）----
            "com.oplus.mydevices.bluetooth.BlueToothDetailActivity",
            // ---- ★ 底部弹窗本体：控件要注入到这里，不能注入 Activity content ----
            "com.oplus.mydevices.bluetooth.fragment.BlueToothPanelFragment",
            "com.oplus.mydevices.bluetooth.fragment.BtPreferenceFragment",
            // ---- 面板内的官方 Preference（可复用其样式）----
            "com.oplus.mydevices.bluetooth.BlueToothCardPreference",
            "com.oplus.mydevices.bluetooth.BlueToothConnectStatePreference",
            "com.oplus.mydevices.bluetooth.BlueToothHorizontalCardPreference",
            // ---- AirPods 状态中枢 ----
            "com.heytap.mydevices.core.bluetooth.AirpodsStatusObserver",
            "com.heytap.mydevices.core.bluetooth.AirpodsStatusObserver$StatusCallback",
            "com.heytap.mydevices.core.bluetooth.AirpodsBluetoothManager",
            "com.heytap.mydevices.core.device.airpods.AirpodsDeviceInfo",
            "com.heytap.mydevices.core.device.airpods.DeviceInfo",
            "com.heytap.mydevices.core.device.airpods.AirpodsManager",
            "com.oplus.mydevices.deviceui.devicecard.airpods.AirpodsData",
            "com.oplus.mydevices.deviceui.devicecard.airpods.AirpodsFragment",
            "com.oplus.mydevices.deviceui.devicecard.viewmodel.BlueToothViewModel",
            // ---- ★ 产品白名单 / 设备能力判定（P4 打击点）----
            "com.heytap.mydevices.core.persist.ProductWhiteListConfig",
            "com.oplus.mydevices.smarthome.whitelist.CardProductWhiteListConfig",
            // ---- ★★ 设备能力扫描 / AirPods 数据模型转换（P4 核心）----
            "com.heytap.mydevices.core.device.BlueToothScannerImpl",
            "com.heytap.mydevices.core.bluetooth.noisereduction.NoiseReductionCommand",
            "com.oplus.mydevices.smarthome.ProductManager",
    };

    /** Activity 生命周期观测/注入点。 */
    private static final String[] ACTIVITY_LIFECYCLE = {
            "onResume", "onCreate", "onStart", "onWindowFocusChanged", "onPause", "onDestroy",
            "finish", "onBackPressed",
    };

    /** 方法名关键词：命中即挂（大小写不敏感）。 */
    private static final String[] KEYWORDS = {
            "noisemode", "batterylevel", "wearstatus", "airpods", "notify", "callback",
            "converttoairpods", "processdevicestate", "getbluetoothboneddevices",
            "getcarddata", "issupport",
    };

    /**
     * Fragment 生命周期观测：面板内容由 Fragment 承载，
     * Hook 它们比 Hook Activity 更可靠（Activity 可能在模块装载前就已 resume）。
     */
    private static final String[] FRAGMENT_LIFECYCLE = {
            "onCreate", "onViewCreated", "onResume", "onStart", "onDestroyView", "onPause",
    };

    /**
     * 精确观测名单：这些方法承载「降噪值映射」与「卡片数据」两条关键信息，
     * 对它们的参数/返回值做「只读观测」（不修改任何行为）。
     */
    private static final String[][] OBSERVE = {
            // 类简名, 方法名
            {"AirpodsStatusObserver", "notifyNoiseModeChanged"},
            {"AirpodsStatusObserver", "notifyBatteryLevelChanged"},
            {"AirpodsStatusObserver", "notifyWearStatusChanged"},
            {"AirpodsStatusObserver", "addCallback"},
            {"AirpodsStatusObserver", "removeCallback"},
            {"AirpodsStatusObserver", "registerBluetoothCallback"},
            {"AirpodsData", "onNoiseModeChanged"},
            {"AirpodsData", "onBatteryLevelChanged"},
            {"AirpodsData", "onWearStatusChanged"},
            {"AirpodsData", "o"},
            {"AirpodsFragment", "addNoiseList"},
            {"AirpodsFragment", "setNoiseModeToOff"},
            {"AirpodsFragment", "initData"},
            {"AirpodsFragment", "initClickListener"},
            {"AirpodsBluetoothManager", "F"},
    };

    private final XposedModule module;
    private final ClassLoader loader;

    private final List<String> hooked = new ArrayList<>();
    private final List<Integer> dumped = new ArrayList<>();
    private volatile Application app;

    MyDevicesHookInstaller(XposedModule module, ClassLoader loader) {
        this.module = module;
        this.loader = loader;
    }

    // ------------------------------------------------------------------

    public void install() {
        Dumper.ensureHeader();
        Dumper.banner("mydevices 侦察开始");
        for (String cn : TARGETS) {
            Class<?> c = tryLoad(cn);
            if (c == null) {
                Dumper.log("✗ 未加载: " + cn);
                continue;
            }
            Dumper.log("✓ 已加载: " + cn);
            // 方法形状 dump 属于侦察产物：每次进程启动都会为 ~20 个类输出全部方法，
            // 量大且无生产价值，因此归入 RECON。
            if (RECON) dumpClassShape(c);
            hookMethods(c, cn);
        }
        hookApplication();
    }

    private Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private synchronized boolean mark(String key) {
        if (hooked.contains(key)) return false;
        hooked.add(key);
        if (hooked.size() > 600) hooked.remove(0);
        return true;
    }

    /** 打印类的方法与字段形状（含参数类型），用于确认真实签名。 */
    private void dumpClassShape(Class<?> c) {
        Dumper.log("  --- 方法形状 ---");
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (n++ > 40) { Dumper.log("  ...(更多省略)"); break; }
            StringBuilder sb = new StringBuilder();
            sb.append("    ").append(Modifier.isStatic(m.getModifiers()) ? "static " : "")
              .append(simple(m.getReturnType())).append(' ').append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(simple(ps[i]));
            }
            sb.append(')');
            Dumper.log(sb.toString());
        }
    }

    private static String simple(Class<?> c) {
        if (c == null) return "?";
        if (c.isArray()) return simple(c.getComponentType()) + "[]";
        String n = c.getName();
        int i = n.lastIndexOf('.');
        return (i >= 0) ? n.substring(i + 1) : n;
    }

    /** 挂该类中所有关键词命中的方法。 */
    private void hookMethods(Class<?> c, String tag) {
        int count = 0;
        String simple = simple(c);

        // ★★ 侦察 Hook 已关闭（RECON=false）★★
        // 原因：这些"关键词观测 Hook"把宿主大量方法 deoptimize 到解释器执行
        // （崩溃栈里全是 nterp_helper），会导致 com.heytap.mydevices:cards
        // 在打开面板时 SIGABRT 崩溃 —— 表现为"面板根本出不来"。
        // 侦察阶段的信息早已取全，因此这里不再安装任何观测 Hook。
        if (RECON) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                String lower = m.getName().toLowerCase(Locale.US);
                boolean hit = false;
                for (String k : KEYWORDS) {
                    if (lower.contains(k)) { hit = true; break; }
                }
                // 精确观测名单里的也算命中（如 AirpodsFragment.addNoiseList）
                if (!hit) {
                    for (String[] pair : OBSERVE) {
                        if (pair[0].equals(simple) && pair[1].equals(m.getName())) { hit = true; break; }
                    }
                }
                if (!hit) continue;
                if (hookOne(m, tag)) count++;
            }
            Dumper.log("  " + simple + ": 挂了 " + count + " 个方法");
        }

        // 额外：Fragment 生命周期（只对 Fragment 类挂）
        if (isFragment(c)) {
            int lc = 0;
            for (Method m : c.getDeclaredMethods()) {
                for (String lname : FRAGMENT_LIFECYCLE) {
                    if (!lname.equals(m.getName())) continue;
                    if (hookLifecycle(m, simple)) lc++;
                }
            }
            if (lc > 0) Dumper.log("  " + simple + ": 额外挂了 " + lc + " 个生命周期方法");

            // ★ 弹窗内的注入点（两个 Fragment 都要挂）
            if ("BlueToothPanelFragment".equals(simple)
                    || "BtPreferenceFragment".equals(simple)) {
                hookPanelFragmentInjection(c);
            }
        }

        // ★ 产品白名单观测：同样归入侦察，已关闭
        if (RECON && "ProductWhiteListConfig".equals(simple)) {
            hookWhitelistConfig(c);
        }

        // 额外：Activity 生命周期（面板 Activity —— 这是注入的可靠时机）
        if (isActivity(c)) {
            int ac = 0;
            for (Method m : c.getDeclaredMethods()) {
                for (String lname : ACTIVITY_LIFECYCLE) {
                    if (!lname.equals(m.getName())) continue;
                    if (hookActivityLifecycle(m, simple)) ac++;
                }
            }
            if (ac > 0) Dumper.log("  " + simple + ": 额外挂了 " + ac + " 个 Activity 生命周期方法");
        }
    }

    /**
     * 侦察开关。
     *
     * <p><b>必须保持 false</b>：true 会安装大量"关键词观测 Hook"，把宿主方法
     * deoptimize 到解释器执行，实测导致 {@code com.heytap.mydevices:cards}
     * 在打开面板时 SIGABRT 崩溃（面板完全无法显示）。</p>
     *
     * <p>需要重新侦察（例如系统大版本升级后重新定位锚点）时才临时改为 true。</p>
     */
    private static final boolean RECON = false;


    /**
     * 产品白名单配置观测（P4 打击点）。
     *
     * <p>实测：{@code ProductWhiteListConfig} 的方法名被 R8 混淆成单字母（a–s），
     * 且白名单是懒加载字段 {@code productWhiteList}。因此策略是
     * <b>把方法全挂上，每次调用后反射 dump 实例字段</b>，靠「字段值变化」定位真实结构。</p>
     */
    private void hookWhitelistConfig(Class<?> c) {
        // ① 构造器：实例一出生就 dump
        for (final java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
            String key = "WL#ctor" + ctor.getParameterCount();
            if (!mark(key)) continue;
            try {
                module.hook(ctor)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    Dumper.banner("ProductWhiteListConfig 实例创建");
                                    dumpInstanceFields(self, "WL");
                                } catch (Throwable t) {
                                    Dumper.log("[WL] 构造后 dump 失败: " + t);
                                }
                                return r;
                            }
                        });
                Dumper.log("  [WL] 已 Hook 构造器 " + ctor);
            } catch (Throwable t) {
                Dumper.log("  [WL] Hook 构造器失败: " + t);
            }
        }

        // ② 所有非静态、参数 ≤2 的方法：调用后 dump
        int n = 0;
        for (final Method m : c.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length > 2) continue;
            String key = "WL#" + m.getName() + "/" + m.getParameterTypes().length;
            if (!mark(key)) continue;
            try {
                module.hook(m)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    Dumper.log("[WL] " + chain.getExecutable().getName()
                                            + "() -> " + trunc(String.valueOf(r), 200));
                                    Object wl = readField(self, "productWhiteList");
                                    if (wl != null && !isEmptyish(wl)
                                            && markOnce("wl-dumped", wl)) {
                                        Dumper.banner("★ productWhiteList 内容");
                                        Dumper.write(Dumper.render(wl));
                                    }
                                } catch (Throwable t) {
                                    Dumper.log("[WL] dump 失败: " + t);
                                }
                                return r;
                            }
                        });
                n++;
            } catch (Throwable t) {
                // 忽略单个失败
            }
        }
        Dumper.log("  [WL] 共 Hook " + n + " 个方法");
    }

    /** 反射遍历实例字段并打印（含父类）。 */
    private void dumpInstanceFields(Object inst, String tag) {
        if (inst == null) return;
        Class<?> c = inst.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String v;
                try {
                    f.setAccessible(true);
                    Object val = f.get(inst);
                    if (val == null) v = "null";
                    else if (isEmptyish(val)) v = Dumper.typeOf(val) + " (empty)";
                    else v = Dumper.typeOf(val) + " = " + trunc(Dumper.render(val), 1200);
                } catch (Throwable t) {
                    v = "<不可读: " + t.getClass().getSimpleName() + ">";
                }
                Dumper.log("[" + tag + "] field " + f.getName()
                        + " (" + f.getType().getSimpleName() + ") " + v);
            }
            c = c.getSuperclass();
        }
    }

    private static Object readField(Object inst, String name) {
        if (inst == null) return null;
        for (Class<?> k = inst.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(inst);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean isEmptyish(Object o) {
        if (o == null) return true;
        if (o instanceof java.util.Collection) return ((java.util.Collection<?>) o).isEmpty();
        if (o instanceof java.util.Map) return ((java.util.Map<?, ?>) o).isEmpty();
        if (o instanceof String) return ((String) o).isEmpty();
        return false;
    }

    private final List<String> onceKeys = new ArrayList<>();

    private synchronized boolean markOnce(String key, Object identity) {
        String k = key + "#" + System.identityHashCode(identity);
        if (onceKeys.contains(k)) return false;
        onceKeys.add(k);
        return true;
    }

    private static String trunc(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static boolean isActivity(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if ("android.app.Activity".equals(n)
                    || n.contains("androidx.activity.ComponentActivity")) return true;
        }
        return false;
    }

    /**
     * Activity 生命周期 Hook：进入时打印，完成后触发 UI 注入。
     *
     * <p>比 {@code registerActivityLifecycleCallbacks} 可靠 —— 后者在
     * Activity 已 resume 的情况下收不到事件，而类级 Hook 在 Activity
     * 任何一次生命周期回调都会命中。</p>
     */
    private boolean hookActivityLifecycle(final Method m, final String cls) {
        String key = "AC#" + m.getDeclaringClass().getName() + "#" + m.getName();
        if (!mark(key)) return false;
        final boolean isResumeLike = "onResume".equals(m.getName())
                || "onWindowFocusChanged".equals(m.getName());
        final boolean isCreate = "onCreate".equals(m.getName());
        try {
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Dumper.log("[AC] " + cls + "#" + m.getName() + " 进入");
                            // onCreate：暴露 Intent extras，取目标设备 MAC
                            if (isCreate) {
                                try {
                                    Object self = chain.getThisObject();
                                    if (self instanceof android.app.Activity) {
                                        android.content.Intent it =
                                                ((android.app.Activity) self).getIntent();
                                        dumpIntent(it);
                                    }
                                } catch (Throwable t) {
                                    Dumper.log("[AC] dumpIntent 失败: " + t);
                                }
                            }
                            Object r = chain.proceed();
                            Dumper.log("[AC] " + cls + "#" + m.getName() + " 完成");
                            // finish/onPause/onDestroy 时打印调用栈 —— 用于定位「面板自动关闭」的真凶
                            if ("finish".equals(m.getName())
                                    || "onPause".equals(m.getName())
                                    || "onDestroy".equals(m.getName())
                                    || "onBackPressed".equals(m.getName())) {
                                dumpStack("[AC] " + cls + "#" + m.getName());
                            }
                            if (isResumeLike) {
                                Object self = chain.getThisObject();
                                if (self instanceof android.app.Activity) {
                                    final android.app.Activity act = (android.app.Activity) self;
                                    try {
                                        act.getWindow().getDecorView().post(new Runnable() {
                                            @Override public void run() {
                                                try {
                                                    PanelInjector.injectNow(act);
                                                } catch (Throwable t) {
                                                    Dumper.log("[AC] 注入异常: " + t);
                                                }
                                            }
                                        });
                                    } catch (Throwable t) {
                                        Dumper.log("[AC] post 注入失败: " + t);
                                    }
                                }
                            }
                            return r;
                        }
                    });
            return true;
        } catch (Throwable t) {
            Dumper.log("[AC] Hook " + cls + "#" + m.getName() + " 失败: " + t);
            return false;
        }
    }

    /** 打印当前线程调用栈（裁剪到前 22 帧），用于定位「谁调用了 finish」。 */
    private void dumpStack(String tag) {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder(tag).append(" 调用栈:");
            int n = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("java.lang.Thread")) continue;
                if (cn.startsWith("io.github.nhckmelody")) continue;
                if (cn.startsWith("io.github.libxposed")) continue;
                sb.append("\n[AC]     at ").append(e);
                if (++n >= 22) break;
            }
            Dumper.log(sb.toString());
        } catch (Throwable t) {
            Dumper.log("[AC] dumpStack 失败: " + t);
        }
    }

    /**
     * 打印 Intent 全部 extras，并尝试识别出目标设备 MAC。
     *
     * <p>面板是带 extras 启动的
     * （{@code ACTION_DEVICE_DETAILED_PANEL (has extras)}），
     * 里面极可能带 MAC —— 这比枚举已配对设备可靠得多。</p>
     */
    private void dumpIntent(android.content.Intent it) {
        if (it == null) {
            Dumper.log("[AC] intent == null");
            return;
        }
        Dumper.log("[AC] intent action=" + it.getAction() + " data=" + it.getData());
        android.os.Bundle ex = it.getExtras();
        if (ex == null) {
            Dumper.log("[AC] intent 无 extras");
            return;
        }
        String title = null;
        String mac = null;
        for (String k : ex.keySet()) {
            Object v = ex.get(k);
            Dumper.log("[AC]   extra " + k + " = " + v);
            if (v instanceof String) {
                String s = (String) v;
                // 形如 AA:BB:CC:DD:EE:FF —— 任一 extra 都试
                if (s.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
                    Dumper.log("[AC]   ★ 识别为 MAC (key=" + k + "): " + s);
                    mac = s;
                    try {
                        NhckLink.get().setHintMac(s);
                    } catch (Throwable t2) {
                        Dumper.log("[AC] setHintMac 失败: " + t2);
                    }
                } else if ("device_title".equals(k) || "device_name".equals(k)) {
                    title = s;
                }
            }
        }

        // ★ 判定本次面板是否属于"我们的目标设备"（原道 / NiceHCK 系列）。
        //   若不是，则后续不注入控制面板 —— 避免给车机、别家耳机等
        //   无关设备凭空插入我们的控件。
        boolean byName = matchesTarget(title);
        String known = NhckLink.getLastGoodMac();
        boolean byMac = (mac != null && known != null && mac.equalsIgnoreCase(known));
        boolean isTarget = byName || byMac;
        PanelInjector.setTargetDevice(isTarget);
        Dumper.log("[AC] 目标设备判定: title=\"" + title + "\" mac=" + mac
                + " → " + (isTarget
                        ? ("是本模块目标 ✓（将注入面板，依据=" + (byName ? "名称" : "MAC") + "）")
                        : "非目标 ✗（不注入）"));
    }

    /** 设备名是否命中原道 / NiceHCK 系列。 */
    private static boolean matchesTarget(String title) {
        if (title == null || title.isEmpty()) return false;
        String up = title.toUpperCase(Locale.US);
        for (String h : TARGET_NAME_HINTS) {
            if (up.contains(h.toUpperCase(Locale.US))) return true;
        }
        return false;
    }

    /** 目标设备名称特征（与 {@code NhckLink.NAME_HINTS} 保持一致）。 */
    private static final String[] TARGET_NAME_HINTS = {
            "YUANDAO", "NiceHCK", "OriG", "EB2S", "NHCK", "原道",
    };

    private static boolean isFragment(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.contains("androidx.fragment.app.Fragment")
                    || n.contains("android.app.Fragment")) return true;
        }
        return false;
    }

    /**
     * 弹窗内注入。
     *
     * <p>实测方法名（没有 {@code onViewCreated}）：
     * <ul>
     *   <li>{@code BtPreferenceFragment#onCreateView} —— 返回弹窗根 View，最佳注入点</li>
     *   <li>{@code BtPreferenceFragment#initView} / {@code BlueToothPanelFragment#initView}</li>
     *   <li>{@code dismissPanel} —— 面板关闭方法，一并溯源</li>
     * </ul></p>
     */
    private void hookPanelFragmentInjection(Class<?> c) {
        for (final Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            boolean isCreateView = "onCreateView".equals(name);
            boolean isInitView = "initView".equals(name);
            boolean isDismiss = "dismissPanel".equals(name);
            if (!isCreateView && !isInitView && !isDismiss) continue;

            String key = "PANEL#" + c.getName() + "#" + name;
            if (!mark(key)) continue;
            try {
                module.hook(m)
                        // ★ 关键修正：注入类 Hook 绝不能 PASSTHROUGH ——
                        //   我们的异常会被直接抛给宿主，导致面板创建失败（实测：面板一闪即消失）。
                        //   这里不设置，用框架默认的保护模式，把模块错误挡在宿主之外。
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                String mname = chain.getExecutable().getName();
                                final boolean isCreateView = "onCreateView".equals(mname);
                                final boolean isInitView = "initView".equals(mname);

                                // 只在进入前做「纯读取」日志；真正的注入推迟到 post()
                                try {
                                    Dumper.log("[PANEL] → " + mname + " 进入");
                                    if ("dismissPanel".equals(mname)) {
                                        dumpStack("[PANEL] dismissPanel");
                                    }
                                } catch (Throwable ignored) {}

                                Object r = chain.proceed();

                                // ★ 所有注入动作都在独立 try/catch 内，且用 post() 推迟到
                                //   宿主视图创建流程结束之后执行 —— 绝不影响宿主生命周期。
                                try {
                                    if (isCreateView && r instanceof ViewGroup) {
                                        final ViewGroup host = (ViewGroup) r;
                                        host.post(new Runnable() {
                                            @Override public void run() {
                                                try {
                                                    PanelInjector.injectIntoContainer(host);
                                                } catch (Throwable t) {
                                                    Dumper.log("[PANEL] post 注入失败: " + t);
                                                }
                                            }
                                        });
                                    } else if (isInitView) {
                                        Object self = chain.getThisObject();
                                        Object view = invokeNoArg(self, "getView");
                                        if (view instanceof ViewGroup) {
                                            final ViewGroup host = (ViewGroup) view;
                                            host.post(new Runnable() {
                                                @Override public void run() {
                                                    try {
                                                        PanelInjector.injectIntoContainer(host);
                                                    } catch (Throwable t) {
                                                        Dumper.log("[PANEL] post 注入失败: " + t);
                                                    }
                                                }
                                            });
                                        }
                                    }
                                } catch (Throwable t) {
                                    Dumper.log("[PANEL] 注入调度失败（已忽略，不影响宿主）: " + t);
                                }
                                return r;
                            }
                        });
                Dumper.log("  [PANEL] 已 Hook " + c.getSimpleName() + "#" + name);
            } catch (Throwable t) {
                Dumper.log("  [PANEL] Hook " + name + " 失败: " + t);
            }
        }
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        if (target == null) return null;
        java.lang.reflect.Method mm = null;
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                mm = k.getDeclaredMethod(name);
                break;
            } catch (NoSuchMethodException ignored) {}
        }
        if (mm == null) return null;
        mm.setAccessible(true);
        return mm.invoke(target);
    }

    /** Fragment 生命周期只记录一行（不 dump 参数），用于确认 UI 时序。 */
    private boolean hookLifecycle(final Method m, final String cls) {
        String key = "LC#" + m.getDeclaringClass().getName() + "#" + m.getName();
        if (!mark(key)) return false;
        try {
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Dumper.log("[LC] " + cls + "#" + m.getName() + " 进入");
                            Object r = chain.proceed();
                            Dumper.log("[LC] " + cls + "#" + m.getName() + " 完成");
                            return r;
                        }
                    });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hookOne(final Method m, final String tag) {
        String key = m.getDeclaringClass().getName() + "#" + m.getName()
                + "/" + m.getParameterTypes().length;
        if (!mark(key)) return false;
        try {
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            try {
                                report(m, chain, result);
                            } catch (Throwable t) {
                                Log.w(TAG, "report failed", t);
                            }
                            return result;
                        }
                    });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "hook failed " + key, t);
            return false;
        }
    }

    private void report(Method m, XposedInterface.Chain chain, Object result) {
        String methodName = m.getName();
        String lower = methodName.toLowerCase(Locale.US);
        String cls = simple(m.getDeclaringClass());

        // 只观测与「降噪值映射 / 卡片数据 / 回调注册」相关的三类
        boolean interesting = lower.contains("noisemode")
                || lower.contains("batterylevel")
                || lower.contains("wearstatus")
                || lower.contains("notify")
                || lower.equals("addcallback")
                || lower.equals("removecallback")
                || lower.equals("addnoiselist")
                || lower.equals("setnoisemodetooff")
                || lower.equals("initdata")
                || lower.equals("registerbluetoothcallback");

        if (!interesting) return;

        // 同一 (类.方法|参数签名) 只详细 dump 首次，之后只打一行摘要（避免刷屏）
        StringBuilder sig = new StringBuilder(cls).append('.').append(methodName);
        for (Class<?> p : m.getParameterTypes()) sig.append('|').append(simple(p));

        boolean first;
        synchronized (this) {
            int h = sig.toString().hashCode();
            first = !dumped.contains(h);
            if (first) dumped.add(h);
        }

        if (!first) {
            Dumper.log(String.format("[MD] %s.%s(%d args) -> %s",
                    cls, methodName, m.getParameterTypes().length, brief(result)));
            return;
        }

        Dumper.banner("mydevices 观测: " + cls + "#" + methodName);
        Dumper.log("完整签名: " + m);
        List<Object> args = chain.getArgs();
        Dumper.log("参数数量: " + (args == null ? 0 : args.size()));
        if (args != null) {
            for (int i = 0; i < args.size(); i++) {
                Object a = args.get(i);
                Dumper.log("  arg[" + i + "] type=" + (a == null ? "null" : Dumper.typeOf(a)));
                Dumper.log("  arg[" + i + "] = " + shortRender(a));
            }
        }
        Dumper.log("返回值 = " + shortRender(result));
    }

    private String brief(Object o) {
        if (o == null) return "null";
        String s = String.valueOf(o);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private String shortRender(Object o) {
        if (o == null) return "null";
        String s = Dumper.render(o);
        return s.length() > 1500 ? s.substring(0, 1500) + "...<trunc>" : s;
    }

    // ------------------------------------------------------------------

    private void hookApplication() {
        try {
            Method m = Instrumentation.class.getMethod(
                    "callApplicationOnCreate", Application.class);
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object a = chain.getArgs().get(0);
                                if (a instanceof Application) {
                                    app = (Application) a;
                                    Dumper.log("[MD] Application = " + a.getClass().getName());
                                    // 最早、最稳的 Context 兜底
                                    try {
                                        NhckLink.get().attachFallback((Application) a);
                                    } catch (Throwable t) {
                                        Dumper.log("[MD] attachFallback 失败: " + t);
                                    }
                                    // 配置通道：让宿主能读取 Settings.Global 里的模块开关
                                    try {
                                        NhckConfig.attach((Application) a);
                                        Dumper.log("[MD] 配置通道已就绪（面板注入="
                                                + NhckConfig.enabled()
                                                + " 诊断日志=" + NhckConfig.verbose()
                                                + " 强制注入=" + NhckConfig.forceTarget() + "）");
                                    } catch (Throwable t) {
                                        Dumper.log("[MD] NhckConfig.attach 失败: " + t);
                                    }
                                    // 注册面板注入（Activity 类 Hook 是主路径，这里只是保险）
                                    try {
                                        PanelInjector.register((Application) a);
                                    } catch (Throwable t) {
                                        Dumper.log("[MD] PanelInjector 注册失败: " + t);
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "app hook body failed", t);
                            }
                            return r;
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Instrumentation hook failed", t);
        }
    }
}
