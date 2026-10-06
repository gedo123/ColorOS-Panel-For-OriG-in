package io.github.nhckmelody;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 通用反射对象 Dumper。
 *
 * <p>设计目标：<b>不依赖任何具体字段名</b>。因为 melody 的 DTO 字段名可能被 R8
 * 改写，硬编码字段名会一版一崩。这里通过反射把对象的存取器/字段全部读出来，
 * 序列化成可读文本。</p>
 *
 * <p>输出位置（宿主 App 在进程内可写，且 shell 可直接 pull）：
 * <ul>
 *   <li>{@code /sdcard/Android/data/com.oplus.melody/files/nhck-wl-dump.log}（首选）</li>
 *   <li>{@code /data/local/tmp/nhck-wl-dump.log}（兜底）</li>
 * </ul></p>
 */
public final class Dumper {

    public static final String TAG = "NhckWl";

    /**
     * 输出候选路径，按顺序尝试，第一个写成功即止。
     *
     * <p>必须覆盖所有目标宿主进程可写的目录：melody 与 mydevices 是两个不同 App，
     * 各自的 {@code /sdcard/Android/data/<pkg>/files/} 才是可靠可写的位置
     * （App 无权限写 /sdcard 根目录）。</p>
     */
    private static final String[] OUT_PATHS = {
            "/sdcard/Android/data/com.heytap.mydevices/files/nhck-wl-dump.log",
            "/sdcard/Android/data/com.oplus.melody/files/nhck-wl-dump.log",
            "/data/local/tmp/nhck-wl-dump.log",
    };

    /** 实际生效的输出路径（首个写成功的），供日志打印。 */
    private static volatile String activePath = null;

    public static String getActivePath() {
        return activePath;
    }

    /** 递归深度上限，防止环形结构爆栈。 */
    private static final int MAX_DEPTH = 6;
    /** 集合最多打印元素数。 */
    private static final int MAX_LIST = 200;
    /** 单次 dump 最多字符数。 */
    private static final int MAX_CHARS = 400_000;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile boolean headerWritten = false;

    /**
     * 诊断级日志开关（生产默认关闭）。
     *
     * <p>为什么要有这一级：诊断日志（视图树、对齐、隐藏条目、刷新追踪…）
     * <b>量大且常常在 UI 线程调用</b>，会产生无谓的存储 IO 与轻微卡顿。
     * 这些信息只在开发/排障时需要。</p>
     */
    private static volatile boolean VERBOSE = false;

    /**
     * 诊断开关的「运行时标记文件」。
     *
     * <p>存在该文件即开启诊断日志 —— 便于日后排障时<b>不必重新编译模块</b>：
     * <pre>adb shell su -c 'touch /sdcard/Android/data/com.heytap.mydevices/files/nhck-verbose'</pre>
     * 删除即关闭。</p>
     */
    private static final String VERBOSE_FLAG =
            "/sdcard/Android/data/com.heytap.mydevices/files/nhck-verbose";

    private static volatile long verboseCheckedAt = 0;

    public static void setVerbose(boolean v) {
        VERBOSE = v;
        verboseCheckedAt = System.currentTimeMillis();
    }

    /** 是否输出诊断日志（编译器开关 / 运行时标记文件 / 信息页里的开关）。 */
    private static boolean verboseEnabled() {
        if (VERBOSE) return true;
        // 信息页（Settings.Global）里的开关优先级最高，自带 5s 缓存
        try {
            if (NhckConfig.verbose()) {
                VERBOSE = true;
                return true;
            }
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        if (now - verboseCheckedAt < 5000) return false;   // 5s 内不重复查文件
        verboseCheckedAt = now;
        try {
            if (new File(VERBOSE_FLAG).exists()) {
                VERBOSE = true;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 诊断级日志：仅在诊断开关打开时输出。 */
    public static void diag(String msg) {
        if (!verboseEnabled()) return;
        log(msg);
    }

    private Dumper() {}

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    public static void log(String msg) {
        Log.i(TAG, msg);
        write("[" + TS.format(new Date()) + "] " + msg);
    }

    // ---- 日志写入：复用句柄 + 缓冲 + 大小上限（避免每条日志 open/close）----

    /** 单个日志文件大小上限：2 MB，超出后自动清空重建（避免无限增长）。 */
    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;

    private static final Object WRITE_LOCK = new Object();
    private static java.io.Writer sWriter;
    private static long sWritten;

    /**
     * 写入一行日志。
     *
     * <p>相比早期实现（每条 `new FileWriter` + flush + close，共 4 次系统调用），
     * 这里<b>复用同一个句柄</b>，把开销降到一次 write + 一次 flush；
     * 并在超过 {@link #MAX_LOG_BYTES} 时自动清空重建。</p>
     */
    public static void write(String line) {
        synchronized (WRITE_LOCK) {
            try {
                if (sWriter == null && !openWriter()) return;
                sWriter.write(line);
                sWriter.write('\n');
                sWriter.flush();
                sWritten += line.length() + 1;
                if (sWritten > MAX_LOG_BYTES) {
                    rotate();
                }
            } catch (Throwable t) {
                // 写失败（例如目录被回收）→ 丢弃句柄，下次重新尝试
                closeWriter();
            }
        }
    }

    /** 打开日志文件（追加模式），按候选路径依次尝试。 */
    private static boolean openWriter() {
        for (String path : OUT_PATHS) {
            try {
                File f = new File(path);
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                java.io.Writer w = new java.io.BufferedWriter(
                        new java.io.OutputStreamWriter(
                                new java.io.FileOutputStream(f, true), "UTF-8"), 8192);
                sWriter = w;
                sWritten = f.exists() ? f.length() : 0;
                activePath = path;
                return true;
            } catch (Throwable ignored) {
                // 试下一个路径
            }
        }
        return false;
    }

    private static void closeWriter() {
        try {
            if (sWriter != null) sWriter.close();
        } catch (Throwable ignored) {
        }
        sWriter = null;
    }

    /** 超过上限：清空重建，保证长期使用不会无限增长。 */
    private static void rotate() {
        closeWriter();
        String path = activePath;
        if (path != null) {
            try {
                new File(path).delete();
            } catch (Throwable ignored) {
            }
        }
        sWritten = 0;
        openWriter();
        if (sWriter != null) {
            try {
                sWriter.write("[" + TS.format(new Date())
                        + "] --- 日志超过上限，已重置 ---\n");
                sWriter.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void banner(String title) {
        log("");
        log("================================================================");
        log("### " + title);
        log("================================================================");
    }

    // ------------------------------------------------------------------
    // 对象序列化
    // ------------------------------------------------------------------

    /**
     * 把一个对象渲染成文本。优先走 getter（更贴近业务语义），
     * getter 不可用时退回字段直读。
     */
    public static String render(Object o) {
        StringBuilder sb = new StringBuilder(4096);
        try {
            renderInto(sb, o, 0, new IdentityHashMap<Object, Boolean>());
        } catch (Throwable t) {
            sb.append("<render failed: ").append(t).append('>');
        }
        if (sb.length() > MAX_CHARS) {
            sb.setLength(MAX_CHARS);
            sb.append("\n...<truncated>");
        }
        return sb.toString();
    }

    private static void renderInto(StringBuilder sb, Object o, int depth,
                                   IdentityHashMap<Object, Boolean> seen) {
        if (o == null) {
            sb.append("null");
            return;
        }
        if (depth > MAX_DEPTH) {
            sb.append("<max-depth>");
            return;
        }

        Class<?> c = o.getClass();

        // ---- 简单类型直接输出 ----
        if (o instanceof String) {
            sb.append('"').append(o).append('"');
            return;
        }
        if (o instanceof Number || o instanceof Boolean || o instanceof Character) {
            sb.append(o);
            return;
        }
        if (o instanceof Enum) {
            sb.append(((Enum<?>) o).name());
            return;
        }
        if (c.isArray()) {
            int n = Array.getLength(o);
            sb.append('[');
            for (int i = 0; i < n && i < MAX_LIST; i++) {
                if (i > 0) sb.append(", ");
                renderInto(sb, Array.get(o, i), depth + 1, seen);
            }
            if (n > MAX_LIST) sb.append(", ...+").append(n - MAX_LIST);
            sb.append(']');
            return;
        }
        if (o instanceof Collection) {
            Collection<?> col = (Collection<?>) o;
            sb.append("(size=").append(col.size()).append(") [");
            int i = 0;
            for (Object e : col) {
                if (i >= MAX_LIST) { sb.append(", ..."); break; }
                if (i > 0) sb.append(", ");
                renderInto(sb, e, depth + 1, seen);
                i++;
            }
            sb.append(']');
            return;
        }
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            sb.append("(size=").append(m.size()).append(") {");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i >= MAX_LIST) { sb.append(", ..."); break; }
                if (i > 0) sb.append(", ");
                sb.append(String.valueOf(e.getKey())).append('=');
                renderInto(sb, e.getValue(), depth + 1, seen);
                i++;
            }
            sb.append('}');
            return;
        }

        // ---- 复杂对象：防环 ----
        if (seen.containsKey(o)) {
            sb.append("<cycle ").append(simple(c)).append('>');
            return;
        }
        seen.put(o, Boolean.TRUE);
        try {
            sb.append(simple(c)).append('{');

            boolean first = true;

            // 1) 无参 getter / is 开头布尔存取器
            int printed = 0;
            for (Method m : c.getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                String n = m.getName();
                boolean isGetter = (n.startsWith("get") && n.length() > 3)
                        || (n.startsWith("is") && n.length() > 2);
                if (!isGetter) continue;
                if ("getClass".equals(n)) continue;
                if (m.getReturnType() == void.class) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (printed >= 160) { sb.append(", ..."); break; }
                Object v;
                try {
                    m.setAccessible(true);
                    v = m.invoke(o);
                } catch (Throwable t) {
                    continue;
                }
                if (!first) sb.append(", ");
                first = false;
                sb.append(n).append('=');
                renderInto(sb, v, depth + 1, seen);
                printed++;
            }

            // 2) 兜底：公开/私有字段（有些 DTO 的字段没有 getter）
            if (printed == 0) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.isSynthetic()) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(f.getName()).append('=');
                    renderInto(sb, v, depth + 1, seen);
                }
            }

            sb.append('}');
        } finally {
            seen.remove(o);
        }
    }

    /** 打印对象的完整类名（含父类），便于确认混淆后的真实类型。 */
    public static String typeOf(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(k.getName());
        }
        return sb.toString();
    }

    private static String simple(Class<?> c) {
        String n = c.getName();
        int i = n.lastIndexOf('.');
        return (i >= 0) ? n.substring(i + 1) : n;
    }

    public static void ensureHeader() {
        if (headerWritten) return;
        headerWritten = true;
        write("");
        write("################ NHCK 模块已加载 ################");
        write("进程: " + android.app.Application.getProcessName());
        if (activePath != null) {
            write("输出文件: " + activePath);
        } else {
            write("⚠ 无任何输出路径可写！候选: " + java.util.Arrays.toString(OUT_PATHS));
        }
    }
}
