package io.github.nhckmelody;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * P1 侦察安装器：把 melody 的**解密后白名单**挖出来。
 *
 * <p>因为白名单在 APK 里是密文（{@code raw/melody_app_whitelist -> res/AQ.json}），
 * 离线读不出来；但解析后的明文对象一定在内存里，所以用四条互补策略：</p>
 *
 * <ol>
 *   <li><b>命名 Hook</b>：按已知类名/方法名（来自 DEX 侦察）挂精确 Hook</li>
 *   <li><b>反射枚举</b>：遍历已加载的 whitelist 相关类，把方法全部登记</li>
 *   <li><b>类加载监听</b>：Hook {@code ClassLoader.loadClass}，晚加载的类也不漏</li>
 *   <li><b>主动触发</b>：拿到 Application 后反射调用白名单入口，不等用户操作</li>
 * </ol>
 */
public final class WlHookInstaller {

    private static final String TAG = Dumper.TAG;

    /** 来自 DEX 侦察的已知类名（melody 16.10.1）。 */
    private static final String[] WL_CLASSES = {
            "com.oplus.melody.alive.WhitelistProvider",
            "com.oplus.melody.common.data.WhitelistContentDO",
            "com.oplus.melody.common.data.WhitelistConfigDTO",
            "com.oplus.melody.common.data.WhitelistConfigDTO$Function",
            "com.oplus.melody.model.repository.whitelist.WhitelistOutsideSnapshot",
            "com.oplus.melody.model.net.data.WhitelistInfoDO",
            "com.oplus.melody.model.db.ProvisionalWhitelistDao",
            "com.oplus.melody.model.db.ProvisionalWhitelistDao_Impl",
            "com.oplus.melody.model.repository.whitelist.WhitelistRepository",
    };

    /** 方法名关键词（大小写不敏感）。 */
    private static final String[] METHOD_KEYWORDS = {
            "whitelist", "getfunction", "isfunctionsupport", "findwhitelistconfig",
            "getlocalcompatibletype", "readlocal"
    };

    private final XposedModule module;
    private final ClassLoader loader;

    /** 已挂过的成员，避免重复 Hook。 */
    private final List<String> hooked = new ArrayList<>();
    /** 已 dump 过的对象标识，避免同一个白名单被反复打印。 */
    private final List<Integer> dumpedIds = new ArrayList<>();

    private volatile Application app;

    WlHookInstaller(XposedModule module, ClassLoader loader) {
        this.module = module;
        this.loader = loader;
    }

    // ------------------------------------------------------------------

    public void install() {
        Dumper.ensureHeader();
        strategyNamedHooks();
        strategyClassLoadMonitor();
        strategyApplicationHook();
    }

    private synchronized boolean markHooked(String key) {
        if (hooked.contains(key)) return false;
        hooked.add(key);
        if (hooked.size() > 400) hooked.remove(0);
        return true;
    }

    // ------------------------------------------------------------------
    // 策略 1：按已知类名/方法名精确 Hook
    // ------------------------------------------------------------------

    private void strategyNamedHooks() {
        Dumper.log("[策略1] 按已知类名挂命名 Hook");
        for (String cn : WL_CLASSES) {
            Class<?> c = tryLoad(cn);
            if (c == null) {
                Dumper.log("   ✗ 未加载: " + cn);
                continue;
            }
            Dumper.log("   ✓ 已加载: " + cn);
            hookAllMethods(c, cn);
        }
    }

    private Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private void hookAllMethods(Class<?> c, String tag) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) continue;
            String lower = m.getName().toLowerCase(Locale.US);
            boolean hit = false;
            for (String kw : METHOD_KEYWORDS) {
                if (lower.contains(kw)) { hit = true; break; }
            }
            // 无参、返回集合/对象的 getXxx 也登记（数据出口）
            if (!hit && m.getParameterTypes().length == 0
                    && m.getReturnType() != void.class) {
                hit = true;
            }
            if (!hit) continue;
            if (hookOne(m, tag)) n++;
        }
        Dumper.log("   " + tag + ": 挂了 " + n + " 个方法");
    }

    private boolean hookOne(Method m, String tag) {
        String key = m.getDeclaringClass().getName() + "#" + m.getName()
                + "/" + m.getParameterTypes().length;
        if (!markHooked(key)) return false;
        try {
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            try {
                                report(tag, chain, result);
                            } catch (Throwable t) {
                                Log.w(TAG, "report failed", t);
                            }
                            return result;
                        }
                    });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "hook failed: " + key, t);
            return false;
        }
    }

    /** 把一次 Hook 命中的方法签名、参数、返回值写盘。 */
    private void report(String tag, XposedInterface.Chain chain, Object result) {
        Method m = (Method) chain.getExecutable();
        // API 102: Chain.getArgs() 返回 List<Object>（不是 Object[]）
        java.util.List<Object> args = chain.getArgs();

        // 白名单对象只 dump 一次，避免刷屏
        if (result != null && isWhitelistLike(result)) {
            int id = System.identityHashCode(result);
            synchronized (this) {
                if (dumpedIds.contains(id)) return;
                dumpedIds.add(id);
            }
            Dumper.banner("WHITELIST DUMP");
            Dumper.log("命中方法: " + m.getDeclaringClass().getName() + "#" + m.getName());
            Dumper.log("实际类型: " + Dumper.typeOf(result));
            Dumper.log("参数数量: " + (args == null ? 0 : args.size()));
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    Dumper.log("  arg[" + i + "] = " + Dumper.render(args.get(i)));
                }
            }
            Dumper.log("---- 返回值 ----");
            Dumper.write(Dumper.render(result));
            Dumper.log("---- dump 结束 ----");
            return;
        }

        // 其它命中只记方法名（含白名单关键词才有价值）
        String lower = m.getName().toLowerCase(Locale.US);
        if (lower.contains("whitelist") || lower.contains("findwhitelistconfig")
                || lower.contains("getlocalcompatibletype")) {
            Dumper.log("[" + tag + "] " + m.getDeclaringClass().getSimpleName()
                    + "#" + m.getName() + " -> " + shortRender(result));
        }
    }

    private boolean isWhitelistLike(Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        if (n.contains("Whitelist")) return true;
        // 集合里装的是白名单也算
        if (o instanceof java.util.Collection) {
            for (Object e : (java.util.Collection<?>) o) {
                if (e != null && e.getClass().getName().contains("Whitelist")) return true;
            }
            if (((java.util.Collection<?>) o).isEmpty()) {
                // 空集合也可能是白名单返回，看方法名决定
                return n.contains("List") || n.contains("ArrayList");
            }
        }
        if (o.getClass().isArray()) {
            int len = Array.getLength(o);
            if (len > 0) {
                Object e = Array.get(o, 0);
                return e != null && e.getClass().getName().contains("Whitelist");
            }
        }
        return false;
    }

    private String shortRender(Object o) {
        if (o == null) return "null";
        String s = Dumper.render(o);
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    // ------------------------------------------------------------------
    // 策略 2：Hook ClassLoader.loadClass，捕获晚加载的白名单类
    // ------------------------------------------------------------------

    private void strategyClassLoadMonitor() {
        Dumper.log("[策略2] 安装 ClassLoader.loadClass 监听");
        try {
            Method m = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            String name = (String) chain.getArgs().get(0);
                            Object r = chain.proceed();
                            if (name != null && name.contains("Whitelist")) {
                                if (markHooked("CL#" + name)) {
                                    Dumper.log("[类加载] " + name + "  (loader="
                                            + chain.getThisObject().getClass().getName() + ")");
                                }
                            }
                            return r;
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "loadClass hook failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 策略 3：抓 Application，然后主动触发白名单加载
    // ------------------------------------------------------------------

    private void strategyApplicationHook() {
        Dumper.log("[策略3] 安装 Application Hook（用于主动触发）");
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
                                    Dumper.log("[Application] 已获取: " + a.getClass().getName());

                                    // ---- P2：初始化 SPP 链路 + 注册面板注入 ----
                                    try {
                                        NhckLink.get().attach((Application) a);
                                        PanelInjector.register((Application) a);
                                        Dumper.log("[P2] SPP 链路与面板注入已就绪");
                                    } catch (Throwable t) {
                                        Dumper.log("[P2] 初始化失败: " + t);
                                    }

                                    // 延迟触发白名单主动探测，等 App 自己初始化完毕
                                    new android.os.Handler(android.os.Looper.getMainLooper())
                                            .postDelayed(new Runnable() {
                                                @Override public void run() {
                                                    proactiveProbe();
                                                    // P2：同时尝试建立 SPP 连接（与白名单探测互不干扰）
                                                    try {
                                                        NhckLink.get().connectAsync();
                                                    } catch (Throwable t) {
                                                        Dumper.log("[P2] connectAsync 失败: " + t);
                                                    }
                                                }
                                            }, 4000);
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

    /**
     * 策略 4：主动反射调用白名单入口。
     *
     * <p>不依赖用户点 UI。对每个白名单类，先找静态无参 getter（单例），
     * 再找带 "whitelist" 关键词的方法尝试调用。</p>
     */
    private void proactiveProbe() {
        Dumper.banner("主动探测开始");
        for (String cn : WL_CLASSES) {
            Class<?> c = tryLoad(cn);
            if (c == null) continue;
            Dumper.log("--- " + cn + " ---");

            // 4.1 静态无参方法（getInstance / getAllWhitelist ...）
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() == void.class) continue;
                String lower = m.getName().toLowerCase(Locale.US);
                if (!lower.contains("get") && !lower.contains("whitelist")) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    Dumper.log("  static " + m.getName() + "() -> " + shortRender(v));
                } catch (Throwable t) {
                    Dumper.log("  static " + m.getName() + "() 失败: " + brief(t));
                }
            }

            // 4.2 实例方法：先尝试无参构造，再调用白名单相关方法
            Object inst = null;
            try {
                java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                inst = ctor.newInstance();
            } catch (Throwable ignored) {
                // 无无参构造，跳过
            }
            if (inst == null) continue;

            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                String lower = m.getName().toLowerCase(Locale.US);
                if (!lower.contains("whitelist")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length > 2) continue;
                try {
                    m.setAccessible(true);
                    Object[] args = new Object[ps.length];
                    for (int i = 0; i < ps.length; i++) args[i] = defaultFor(ps[i]);
                    Object v = m.invoke(inst, args);
                    Dumper.log("  " + m.getName() + "(" + ps.length + " args) -> " + shortRender(v));
                } catch (Throwable t) {
                    Dumper.log("  " + m.getName() + " 失败: " + brief(t));
                }
            }
        }
        Dumper.banner("主动探测结束");
    }

    private static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    private static String brief(Throwable t) {
        Throwable c = t;
        if (c instanceof java.lang.reflect.InvocationTargetException
                && c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
