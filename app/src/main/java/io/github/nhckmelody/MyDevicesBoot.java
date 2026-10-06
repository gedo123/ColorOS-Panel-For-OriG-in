package io.github.nhckmelody;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * mydevices 侧引导：捕获 {@code AirpodsStatusObserver} 的真实单例。
 *
 * <p>P2 侦察已确认：该类是 <b>Kotlin 懒初始化 object</b>
 * （{@code access$getINSTANCE$cp()} 在启动时返回 {@code null}），
 * 所以「启动时反射取单例」这条路走不通，必须改为
 * <b>Hook 其构造器</b>，在宿主首次实例化时接管。</p>
 */
final class MyDevicesBoot {

    private static final String OBSERVER =
            "com.heytap.mydevices.core.bluetooth.AirpodsStatusObserver";

    /** 捕获到的真实实例。 */
    private static volatile Object observerInstance;

    private MyDevicesBoot() {}

    static Object getObserverInstance() {
        return observerInstance;
    }

    static void attach(XposedModule module, ClassLoader loader) {
        try {
            Class<?> c = Class.forName(OBSERVER, false, loader);
            Dumper.log("[MD-Boot] 找到 AirpodsStatusObserver: " + c.getName());

            // ① Hook 构造器 —— 懒初始化 object 的真正入口
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                try {
                    module.hook(ctor)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    Object self = chain.getThisObject();
                                    if (self != null) {
                                        observerInstance = self;
                                        Dumper.log("[MD-Boot] ✓ 捕获 AirpodsStatusObserver 实例: "
                                                + Integer.toHexString(System.identityHashCode(self)));
                                        try {
                                            dumpObserverFields(self);
                                        } catch (Throwable t) {
                                            Dumper.log("[MD-Boot] dump 字段失败: " + t);
                                        }
                                    }
                                    return r;
                                }
                            });
                    Dumper.log("[MD-Boot] 已 Hook 构造器 " + ctor);
                } catch (Throwable t) {
                    Dumper.log("[MD-Boot] Hook 构造器失败: " + t);
                }
            }

            // ② Hook addCallback / registerBluetoothCallback —— 观测谁注册了回调
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.US);
                if (!n.equals("addcallback") && !n.equals("registerbluetoothcallback")) continue;
                try {
                    module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Dumper.log("[MD-Boot] → " + chain.getExecutable().getName()
                                            + " 被调用，参数=" + briefArgs(chain.getArgs()));
                                    Object r = chain.proceed();
                                    if (observerInstance == null) {
                                        observerInstance = chain.getThisObject();
                                    }
                                    return r;
                                }
                            });
                    Dumper.log("[MD-Boot] 已 Hook " + m.getName());
                } catch (Throwable t) {
                    Dumper.log("[MD-Boot] Hook " + m.getName() + " 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Dumper.log("[MD-Boot] 初始化失败: " + t);
        }
    }

    private static String briefArgs(List<Object> args) {
        if (args == null || args.isEmpty()) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            Object a = args.get(i);
            if (a == null) sb.append("null");
            else sb.append(a.getClass().getSimpleName()).append('@')
                    .append(Integer.toHexString(System.identityHashCode(a)));
        }
        return sb.append(')').toString();
    }

    /** 反射查看 observer 内部字段，确认回调容器类型与当前内容。 */
    private static void dumpObserverFields(Object inst) {
        Class<?> c = inst.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String v;
                try {
                    f.setAccessible(true);
                    Object val = f.get(inst);
                    v = (val == null) ? "null" : Dumper.typeOf(val) + " = " + brief(val);
                } catch (Throwable t) {
                    v = "<无法读取: " + t.getClass().getSimpleName() + ">";
                }
                Dumper.log("[MD-Boot]   field " + f.getName() + " (" + f.getType().getSimpleName() + ") " + v);
            }
            c = c.getSuperclass();
        }
    }

    private static String brief(Object o) {
        String s = String.valueOf(o);
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
