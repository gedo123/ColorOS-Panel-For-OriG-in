package io.github.nhckmelody;

import android.app.Application;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * libxposed API 102 入口（多包路由）。
 *
 * <p>作用域（scope.list）：
 * <ul>
 *   <li>{@code com.oplus.melody} —— 白名单/能力表侦察（P1 已完成，保留）</li>
 *   <li>{@code com.heytap.mydevices} —— ColorOS 16 的实际设备面板 + AirPods 状态中枢（P2 目标）</li>
 * </ul></p>
 */
public final class NhckWlModule extends XposedModule {

    private static final String TAG = Dumper.TAG;

    private static final String PKG_MELODY = "com.oplus.melody";
    private static final String PKG_MYDEVICES = "com.heytap.mydevices";

    private static final AtomicBoolean INSTALLED_MELODY = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED_MYDEVICES = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED_UI = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "module loaded in " + param.getProcessName()
                + " framework=" + getFrameworkName() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        route(param.getPackageName(), param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        route(param.getPackageName(), param.getClassLoader());
    }

    private void route(String pkg, ClassLoader loader) {
        if (loader == null) {
            Log.e(TAG, "host ClassLoader is null for " + pkg);
            return;
        }
        String proc;
        boolean isSub;
        try {
            proc = Application.getProcessName();
            isSub = proc != null && proc.contains(":");
        } catch (Throwable t) {
            // 极少数情况下（onPackageLoaded 阶段）getProcessName 可能失败
            proc = "<unknown>";
            isSub = false;
        }

        if (PKG_MELODY.equals(pkg)) {
            // melody 有主进程 + :fg；白名单探测只需主进程一份
            if (isSub) return;
            if (!INSTALLED_MELODY.compareAndSet(false, true)) return;
            try {
                Dumper.log("### melody 侦察装载 (proc=" + proc + ")");
                new WlHookInstaller(this, loader).install();
            } catch (Throwable t) {
                INSTALLED_MELODY.set(false);
                Dumper.log("melody 安装失败: " + t);
            }
            return;
        }

        if (PKG_MYDEVICES.equals(pkg)) {
            // ⚠️ 实测：ColorOS 16 的设备面板 Activity
            // (com.oplus.mydevices.bluetooth.BlueToothDetailActivity)
            // 运行在 **:cards 子进程** 里，所以子进程必须装载（不能像 melody 那样跳过）。
            if (!INSTALLED_MYDEVICES.compareAndSet(false, true)) return;
            try {
                Dumper.log("### mydevices 侦察装载 (proc=" + proc + ")"
                        + (isSub ? "  [子进程]" : ""));
                new MyDevicesHookInstaller(this, loader).install();
                // ★ MyDevicesBoot 已停用：
                //   它 Hook AirpodsStatusObserver 的构造器且使用 ExceptionMode.PASSTHROUGH
                //   （模块异常会直接抛给宿主），属于侦察阶段产物。
                //   实测大量 Hook 会把宿主方法 deoptimize 到解释器执行，
                //   导致 com.heytap.mydevices:cards 打开面板时 SIGABRT 崩溃。
                // MyDevicesBoot.attach(this, loader);
            } catch (Throwable t) {
                INSTALLED_MYDEVICES.set(false);
                Dumper.log("mydevices 安装失败: " + t);
            }
        }
    }
}
