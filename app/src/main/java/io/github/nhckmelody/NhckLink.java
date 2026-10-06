package io.github.nhckmelody;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * NiceHCK SPP 链路（运行在 <b>com.heytap.mydevices</b> 的 {@code :cards} 子进程内）。
 *
 * <p>注意：面板实际位于「设备空间」{@code com.heytap.mydevices}，
 * <b>不是</b>「无线耳机」{@code com.oplus.melody}（后者仅保留在作用域中做兼容）。</p>
 *
 * <p>P0 已实测：一级降级（secure RFCOMM + 自定义 UUID）即命中，因此这里保留
 * 三级降级但正常只会走第一级。</p>
 *
 * <p>约束：RFCOMM 独占 → 单例 + 串行写 + 专用 I/O 线程。</p>
 */
public final class NhckLink {

    private static final String TAG = Dumper.TAG;

    /** 名称匹配候选：官方 App 包名 com.yuandao.nicehck 说明是原道(NiceHCK)贴牌。 */
    private static final String[] NAME_HINTS =
            {"YUANDAO", "NiceHCK", "OriG", "EB2S", "NHCK", "原道"};

    private static volatile NhckLink sInstance;

    /**
     * 最近一次成功连接的耳机 MAC（进程内）。
     *
     * <p>用途：设备判断的兜底 —— 若用户改了蓝牙设备名，
     * 仅凭名称匹配会失败，此时可用 MAC 认出目标设备。</p>
     */
    private static volatile String lastGoodMac;

    public static String getLastGoodMac() {
        return lastGoodMac;
    }

    public static NhckLink get() {
        if (sInstance == null) {
            synchronized (NhckLink.class) {
                if (sInstance == null) sInstance = new NhckLink();
            }
        }
        return sInstance;
    }

    private final HandlerThread ioThread;
    private final Handler io;

    // ------------------------------------------------------------------
    // 自动刷新（面板可见期间，每 10 分钟查询一次耳机状态）
    // ------------------------------------------------------------------

    /** 自动刷新间隔：10 分钟。 */
    private static final long AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L;

    private final Handler autoHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable autoTask;

    /**
     * 启动周期性状态刷新。
     *
     * <p>为什么需要：模块不做持续轮询时，耳机侧的变化（例如<b>在耳机上触控切换降噪</b>、
     * 或官方 App 改了设置）面板无法感知，显示会滞后。这里在面板可见期间
     * 每 {@value #AUTO_REFRESH_INTERVAL_MS} 毫秒重新查询一次。</p>
     */
    public void startAutoRefresh() {
        stopAutoRefresh();
        autoTask = new Runnable() {
            @Override public void run() {
                queryAllState("AUTO");
                autoHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS);
            }
        };
        // 打开面板时先立即同步一次（此时可能已连接、握手查询已过时），
        // 之后每 10 分钟刷新一次。
        autoTask.run();
        Dumper.log("[Link] 自动刷新已启动（立即查询一次 + 每 10 分钟一次）");
    }

    /** 一次性下发全部状态查询（降噪 / 电量 / EQ / 固件）。 */
    public void queryAllState(String tag) {
        try {
            Dumper.log("[Link] 状态查询(" + tag + ")");
            send(NhckProtocol.query(NhckProtocol.ANC_QUERY), tag + "_QUERY_ANC");
            send(NhckProtocol.query(NhckProtocol.BATTERY_QUERY), tag + "_QUERY_BAT");
            send(NhckProtocol.query(NhckProtocol.EQ_QUERY), tag + "_QUERY_EQ");
            send(NhckProtocol.query(NhckProtocol.VERSION_QUERY), tag + "_QUERY_VER");
        } catch (Throwable t) {
            Dumper.log("[Link] 状态查询异常: " + t);
        }
    }

    /** 停止周期性刷新（面板不可见时调用，避免无谓的射频开销）。 */
    public void stopAutoRefresh() {
        if (autoTask != null) {
            autoHandler.removeCallbacks(autoTask);
            autoTask = null;
            Dumper.log("[Link] 自动刷新已停止");
        }
    }
    private final NhckProtocol.StreamDecoder decoder = new NhckProtocol.StreamDecoder();

    private volatile Context ctx;
    /** Application 级兜底 Context（Activity context 可能不可用）。 */
    private volatile Context fallbackCtx;
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile InputStream inp;
    private volatile boolean running;
    private volatile BluetoothDevice target;

    public volatile int batteryLeft = -1;
    public volatile int batteryRight = -1;
    public volatile int batteryCase = -1;
    public volatile int ancMode = -1;
    public volatile int eqMode = -1;
    public volatile int firmwareMain = -1;
    public volatile int firmwareSub = -1;
    public volatile boolean dualConn;
    public volatile boolean inEar;
    public volatile boolean wind;
    public volatile boolean gameMode;
    public volatile boolean lowLatency;

    /** UI 刷新回调（由注入的控件注册）。 */
    public interface Listener { void onState(NhckLink link); }

    private final List<Listener> listeners = new ArrayList<>();

    private NhckLink() {
        ioThread = new HandlerThread("nhck-io");
        ioThread.start();
        io = new Handler(ioThread.getLooper());
    }

    /** 优先用传入的 Context（Activity 最好），失败则保留 Application 兜底。 */
    public void attach(Context c) {
        if (c == null) return;
        try {
            Context app = c.getApplicationContext();
            ctx = (app != null) ? app : c;
        } catch (Throwable t) {
            Dumper.log("[Link] attach 取 applicationContext 失败: " + t + "，直接用原 Context");
            ctx = c;
        }
        Dumper.log("[Link] context 已配置: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }

    /** Application 钩子里调用，作为最早、最稳的兜底。 */
    public void attachFallback(Context c) {
        if (c == null) return;
        fallbackCtx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (ctx == null) ctx = fallbackCtx;
        Dumper.log("[Link] fallback context 已配置");
    }

    /**
     * 取可用 Context。
     *
     * <p>实测：在 {@code :cards} 子进程里，若只有 Application context 之外的来源
     * 可能拿不到，所以这里做多级兜底，并在失败时打印原因（不再静默吞异常）。</p>
     */
    private Context obtainContext() {
        Context c = ctx;
        if (c != null) return c;
        c = fallbackCtx;
        if (c != null) {
            ctx = c;
            return c;
        }
        // 最后兜底：反射取当前 Application
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cur = at.getMethod("currentApplication");
            Object app = cur.invoke(null);
            if (app instanceof Context) {
                ctx = (Context) app;
                Dumper.log("[Link] 通过 ActivityThread.currentApplication() 取到 Context");
                return ctx;
            }
        } catch (Throwable t) {
            Dumper.log("[Link] ActivityThread 兜底失败: " + t);
        }
        return null;
    }

    public void addListener(Listener l) {
        synchronized (listeners) { if (!listeners.contains(l)) listeners.add(l); }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) { listeners.remove(l); }
    }

    private void notifyState() {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        for (Listener l : copy) {
            try { l.onState(this); } catch (Throwable ignored) {}
        }
    }

    public boolean isConnected() {
        return running && socket != null && socket.isConnected();
    }

    private BluetoothAdapter adapter() {
        Context c = obtainContext();
        if (c == null) {
            Dumper.log("[Link] obtainContext() 返回 null");
            return null;
        }
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            Dumper.log("[Link] getSystemService(BLUETOOTH_SERVICE) 返回 null");
            return null;
        }
        return bm.getAdapter();
    }

    /** 由面板 Intent 注入的目标 MAC（比枚举已配对设备更可靠）。 */
    private volatile String hintMac;

    public void setHintMac(String mac) {
        if (mac != null && !mac.isEmpty()) {
            hintMac = mac;
            Dumper.log("[Link] 收到 MAC 提示: " + mac);
        }
    }

    @SuppressLint("MissingPermission")
    public BluetoothDevice findTarget() {
        BluetoothAdapter ad = adapter();
        if (ad == null) {
            Dumper.log("[Link] ✗ adapter == null（ctx=" + (ctx == null ? "null" : "ok") + "）");
            return null;
        }
        Dumper.log("[Link] adapter: enabled=" + ad.isEnabled() + " state=" + ad.getState());

        // ① 优先用面板给的 MAC
        String mac = hintMac;
        if (mac != null) {
            try {
                BluetoothDevice d = ad.getRemoteDevice(mac);
                Dumper.log("[Link] ✓ 用 MAC 提示取到设备: " + d.getName() + " (" + d.getAddress() + ")");
                return d;
            } catch (Throwable t) {
                Dumper.log("[Link] MAC 提示取设备失败: " + t);
            }
        }

        // ② 枚举已配对设备
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded == null) {
                Dumper.log("[Link] ✗ getBondedDevices() 返回 null");
                return null;
            }
            Dumper.log("[Link] 已配对设备数: " + bonded.size());
            for (BluetoothDevice d : bonded) {
                String n;
                try {
                    n = d.getName();
                } catch (Throwable t) {
                    n = null;
                }
                Dumper.log("[Link]   - " + d.getAddress() + " name=" + n);
                if (n == null) continue;
                for (String h : NAME_HINTS) {
                    if (n.regionMatches(true, 0, h, 0, h.length())) {
                        Dumper.log("[Link] ✓ 命中目标: " + n);
                        return d;
                    }
                }
            }
            Dumper.log("[Link] ✗ 已配对列表里没有名称匹配的耳机");
        } catch (Throwable t) {
            Dumper.log("[Link] ✗ getBondedDevices 抛异常: " + t.getClass().getName() + ": " + t.getMessage());
        }
        return null;
    }

    /** 连接（异步）。已连接则忽略。 */
    public void connectAsync() {
        io.post(new Runnable() {
            @Override public void run() {
                if (isConnected()) return;
                BluetoothDevice d = target != null ? target : findTarget();
                if (d == null) {
                    Dumper.log("[Link] 未找到目标耳机（名称前缀 " + java.util.Arrays.toString(NAME_HINTS) + "）");
                    return;
                }
                try {
                    connectBlocking(d);
                } catch (Throwable t) {
                    Dumper.log("[Link] 连接失败: " + t);
                    closeInternal();
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void connectBlocking(BluetoothDevice device) throws IOException {
        BluetoothAdapter ad = adapter();
        if (ad == null) throw new IOException("无 BluetoothAdapter");
        if (!ad.isEnabled()) throw new IOException("蓝牙未开启");
        if (ad.isDiscovering()) ad.cancelDiscovery();

        // P0 实测：一级即可命中
        BluetoothSocket s;
        try {
            s = device.createRfcommSocketToServiceRecord(NhckProtocol.SPP_UUID);
            s.connect();
            Dumper.log("[Link] ✓ 建链成功：secure RFCOMM + 自定义 UUID");
        } catch (IOException e1) {
            Dumper.log("[Link] ① secure 失败: " + e1.getMessage());
            try {
                s = device.createInsecureRfcommSocketToServiceRecord(NhckProtocol.SPP_UUID);
                s.connect();
                Dumper.log("[Link] ✓ 建链成功：insecure RFCOMM");
            } catch (IOException e2) {
                Dumper.log("[Link] ② insecure 失败: " + e2.getMessage());
                throw e2;
            }
        }

        socket = s;
        out = s.getOutputStream();
        inp = s.getInputStream();
        target = device;
        running = true;
        // 记录本次成功连接的 MAC —— 供设备判断做兜底
        // （用户若在蓝牙设置里改了设备名，仍能凭 MAC 认出是我们的耳机）
        lastGoodMac = device.getAddress();
        Dumper.log("[Link] 已连接 " + device.getName() + " (" + device.getAddress() + ")");

        // 建链握手查询（P0 验证过的顺序，900ms 间隔）
        final int[] ops = {
                NhckProtocol.VERSION_QUERY, NhckProtocol.BATTERY_QUERY,
                NhckProtocol.ANC_QUERY, NhckProtocol.EQ_QUERY,
                NhckProtocol.GAME_MODE_QUERY, NhckProtocol.LOW_LATENCY_QUERY,
                NhckProtocol.DUAL_CONN_QUERY, NhckProtocol.IN_EAR_QUERY,
                NhckProtocol.WIND_QUERY
        };
        for (int i = 0; i < ops.length; i++) {
            final int op = ops[i];
            io.postDelayed(new Runnable() {
                @Override public void run() { rawSend(NhckProtocol.query(op), "QUERY"); }
            }, 200L + i * 900L);
        }
        startReader();
    }

    private void startReader() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[1024];
                try {
                    while (running) {
                        InputStream in = inp;
                        if (in == null) break;
                        int n = in.read(buf);
                        if (n < 0) break;
                        for (NhckProtocol.Frame f : decoder.feed(buf, n)) {
                            applyFrame(f);
                        }
                    }
                } catch (IOException e) {
                    Dumper.log("[Link] 读线程结束: " + e.getMessage());
                } finally {
                    closeInternal();
                    Dumper.log("[Link] 连接已断开");
                }
            }
        }, "nhck-reader");
        t.setDaemon(true);
        t.start();
    }

    private void applyFrame(NhckProtocol.Frame f) {
        boolean changed = false;
        switch (f.opCode) {
            case NhckProtocol.BATTERY_QUERY:
                batteryLeft = f.b(0); batteryRight = f.b(1);
                if (f.b(2) > 0) batteryCase = f.b(2);
                changed = true; break;
            case NhckProtocol.ANC_QUERY:
                ancMode = f.b(0); changed = true; break;
            case NhckProtocol.EQ_QUERY:
                eqMode = f.b(0); changed = true; break;
            case NhckProtocol.VERSION_QUERY:
                // P0 实测：活代码的 [6]=sub / [7]=main 是正确的（4.08）
                firmwareSub = f.b(0); firmwareMain = f.b(1);
                changed = true; break;
            case NhckProtocol.GAME_MODE_QUERY:   gameMode = f.on(0); changed = true; break;
            case NhckProtocol.LOW_LATENCY_QUERY: lowLatency = f.on(0); changed = true; break;
            case NhckProtocol.DUAL_CONN_QUERY:   dualConn = f.on(0); changed = true; break;
            case NhckProtocol.IN_EAR_QUERY:      inEar = f.on(0); changed = true; break;
            case NhckProtocol.WIND_QUERY:        wind = f.on(0); changed = true; break;
            default: break;
        }
        if (changed) {
            Dumper.log(String.format(java.util.Locale.US,
                    "[Link] RX 0x%04X -> fw=%d.%d bat=%d/%d/%d anc=%d eq=%d",
                    f.opCode, firmwareMain, firmwareSub,
                    batteryLeft, batteryRight, batteryCase, ancMode, eqMode));
            notifyState();
        }
    }

    /** 发送并把 SET 后的回读确认排进队列（P0 验证过的可靠性手段）。 */
    public void send(final byte[] packet, final String note) {
        io.post(new Runnable() {
            @Override public void run() {
                if (!isConnected()) {
                    Dumper.log("[Link] 未连接，尝试重连后重发: " + note);
                    BluetoothDevice d = target != null ? target : findTarget();
                    if (d == null) { Dumper.log("[Link] 无设备，丢弃 " + note); return; }
                    try { connectBlocking(d); }
                    catch (IOException e) { Dumper.log("[Link] 重连失败，丢弃 " + note); return; }
                }
                rawSend(packet, note);
                int op = NhckProtocol.opCodeOf(packet);
                byte[] q = readbackFor(op);
                if (q != null) {
                    io.postDelayed(new Runnable() {
                        @Override public void run() { rawSend(q, "READBACK"); }
                    }, 300);
                }
            }
        });
    }

    private static byte[] readbackFor(int setOp) {
        switch (setOp) {
            case NhckProtocol.ANC_SET:         return NhckProtocol.query(NhckProtocol.ANC_QUERY);
            case NhckProtocol.EQ_SET:          return NhckProtocol.query(NhckProtocol.EQ_QUERY);
            case NhckProtocol.GAME_MODE_SET:   return NhckProtocol.query(NhckProtocol.GAME_MODE_QUERY);
            case NhckProtocol.LOW_LATENCY_SET: return NhckProtocol.query(NhckProtocol.LOW_LATENCY_QUERY);
            case NhckProtocol.DUAL_CONN_SET:   return NhckProtocol.query(NhckProtocol.DUAL_CONN_QUERY);
            case NhckProtocol.IN_EAR_SET:      return NhckProtocol.query(NhckProtocol.IN_EAR_QUERY);
            case NhckProtocol.WIND_SET:        return NhckProtocol.query(NhckProtocol.WIND_QUERY);
            default: return null;
        }
    }

    private void rawSend(byte[] packet, String note) {
        try {
            OutputStream o = out;
            if (o == null) return;
            o.write(packet);
            o.flush();
            Dumper.log("[Link] TX " + note + ": " + NhckProtocol.hex(packet));
        } catch (IOException e) {
            Dumper.log("[Link] 写失败: " + e.getMessage());
            closeInternal();
        }
    }

    public void close() {
        io.post(new Runnable() {
            @Override public void run() { closeInternal(); }
        });
    }

    private void closeInternal() {
        running = false;
        quietClose(inp); quietClose(out); quietClose(socket);
        inp = null; out = null; socket = null;
    }

    private static void quietClose(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }

    /** 供 UI 显示的状态文本（设备名 + 左右耳电量；固件 / 降噪 / EQ 带标签）。 */
    public String summary() {
        if (!isConnected()) return "未连接";
        StringBuilder sb = new StringBuilder();
        BluetoothDevice d = target;
        sb.append(d != null ? String.valueOf(d.getName()) : "NiceHCK");

        // 第一行：设备名 + 左右耳电量（含充电仓）
        boolean anyBat = false;
        StringBuilder bat = new StringBuilder();
        if (batteryLeft >= 0) { bat.append("L ").append(batteryLeft).append('%'); anyBat = true; }
        if (batteryRight >= 0) {
            if (anyBat) bat.append("   ");
            bat.append("R ").append(batteryRight).append('%');
            anyBat = true;
        }
        if (batteryCase > 0) {
            if (anyBat) bat.append("   ");
            bat.append("仓 ").append(batteryCase).append('%');
        }
        if (anyBat) sb.append("   ").append(bat);

        // 第二行：固件 / 降噪 / EQ（均带标签，格式统一）
        sb.append('\n');
        sb.append("固件 ").append(firmwareMain < 0 ? "?" : firmwareMain)
          .append('.').append(firmwareSub < 0 ? "??"
                  : String.format(java.util.Locale.US, "%02d", firmwareSub))
          .append("   降噪：").append(NhckProtocol.ancName(ancMode))
          .append("   EQ：").append(NhckProtocol.eqName(eqMode));
        return sb.toString();
    }
}
