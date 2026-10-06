package io.github.nhckmelody;

import android.content.Context;
import android.provider.Settings;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 模块配置的**跨进程共享通道**。
 *
 * <p><b>为什么用 Settings.Global</b>：模块 App（{@code io.github.nhckmelody}）与宿主
 * （{@code com.heytap.mydevices}）是<b>不同 UID</b>，彼此读不到对方的
 * SharedPreferences，也不能互写 {@code Android/data}。而：
 * <ul>
 *   <li>宿主<b>读</b> {@code Settings.Global} —— 不需要任何权限 ✅</li>
 *   <li>模块 App <b>写</b> —— 需要 {@code WRITE_SECURE_SETTINGS}，adb 一次性授权</li>
 * </ul>
 * 因此这是最干净的共享方式。</p>
 *
 * <p>读取结果带 <b>5 秒缓存</b>（{@code verbose()} 会被每条日志调用，不能每次都走
 * ContentProvider），所以从 App 里改开关后最多 5 秒生效。</p>
 */
final class NhckConfig {

    /** 面板注入总开关（默认开）。 */
    static final String KEY_ENABLED = "nhck_panel_enabled";
    /** 诊断日志开关（默认关）。 */
    static final String KEY_VERBOSE = "nhck_panel_verbose";
    /** 强制对所有设备注入（默认关，调试用）。 */
    static final String KEY_FORCE_TARGET = "nhck_panel_force_target";

    private static final long CACHE_TTL_MS = 5000;

    private static volatile Context sCtx;
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile long sCacheAt = 0;

    private NhckConfig() {}

    /** 宿主进程拿到 Application 后调用，用于后续读取配置。 */
    static void attach(Context c) {
        try {
            sCtx = c.getApplicationContext();
        } catch (Throwable ignored) {
        }
    }

    static String get(String key, String def) {
        long now = System.currentTimeMillis();
        if (now - sCacheAt > CACHE_TTL_MS) {
            CACHE.clear();
            sCacheAt = now;
        }
        String hit = CACHE.get(key);
        if (hit != null) return hit;
        String v = def;
        Context c = sCtx;
        if (c != null) {
            try {
                String s = Settings.Global.getString(c.getContentResolver(), key);
                if (s != null) v = s;
            } catch (Throwable ignored) {
            }
        }
        CACHE.put(key, v);
        return v;
    }

    /** 面板注入是否启用（总开关）。 */
    static boolean enabled() {
        return !"0".equals(get(KEY_ENABLED, "1"));
    }

    /** 是否输出诊断日志。 */
    static boolean verbose() {
        return "1".equals(get(KEY_VERBOSE, "0"));
    }

    /** 是否强制对所有设备注入（绕过设备判断，调试用）。 */
    static boolean forceTarget() {
        return "1".equals(get(KEY_FORCE_TARGET, "0"));
    }
}
