package io.github.nhckmelody;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * NiceHCK 经典蓝牙 SPP 协议（P0 已在真机逐条验证）。
 *
 * <p>验证环境：OnePlus PJF110 / ColorOS 16.0.1.301 / 耳机 YUANDAO OriG in 固件 4.08。</p>
 *
 * <p>已实测确认：
 * <ul>
 *   <li>建链路径 = secure RFCOMM + 自定义 UUID（一级命中，无需 channel 兜底）</li>
 *   <li>固件响应 {@code [6]=sub, [7]=main} → 4.08</li>
 *   <li>{@code reserved} 字节强制 0x00（非 0 无响应）</li>
 *   <li>SET 指令会回一个 {@code 0x02XX} 且值等于设定值的确认帧</li>
 * </ul></p>
 */
public final class NhckProtocol {

    private NhckProtocol() {}

    /** 自定义 RFCOMM service-record UUID（尾部 8 字节 = ASCII "NHCKCTRL"）。 */
    public static final UUID SPP_UUID =
            UUID.fromString("0000a100-1000-8000-4e48-434b4354524c");

    public static final byte MAGIC = 0x4E;

    // ---- opcode：0x01xx = 查询/上报，0x02xx = 设置 ----
    public static final int VERSION_QUERY     = 0x0003;
    public static final int BATTERY_QUERY     = 0x0005;
    public static final int ANC_QUERY         = 0x0101;
    public static final int ANC_SET           = 0x0201;
    public static final int EQ_QUERY          = 0x0107;
    public static final int EQ_SET            = 0x0207;
    public static final int GAME_MODE_QUERY   = 0x0108;
    public static final int GAME_MODE_SET     = 0x0208;
    public static final int LOW_LATENCY_QUERY = 0x0106;
    public static final int LOW_LATENCY_SET   = 0x0206;
    public static final int DUAL_CONN_QUERY   = 0x0105;
    public static final int DUAL_CONN_SET     = 0x0205;
    public static final int IN_EAR_QUERY      = 0x0109;
    public static final int IN_EAR_SET        = 0x0209;
    public static final int CODEC_SET         = 0x0204;
    public static final int WIND_QUERY        = 0x01E1;
    public static final int WIND_SET          = 0x02E1;

    // ---- ANC 模式（实测：0x00/0x01/0x02/0x03 四档来回切换成功）----
    public static final int ANC_OFF = 0x00;
    public static final int ANC_TRANSPARENT = 0x01;
    public static final int ANC_NORMAL = 0x02;
    public static final int ANC_DEEP = 0x03;

    // ---- EQ 预设（实测：0x01/0x02/0x05 切换成功）----
    public static final int EQ_BLUE = 0x00;
    public static final int EQ_BALANCED = 0x01;
    public static final int EQ_BASS = 0x02;
    public static final int EQ_PURE = 0x03;
    public static final int EQ_GAME = 0x04;
    public static final int EQ_FINE = 0x05;
    public static final int EQ_VOCAL = 0x06;

    public static final String[] ANC_NAMES = {"关闭", "通透", "普通降噪", "深度降噪"};
    public static final String[] EQ_NAMES = {
            "悔恨之泪", "均衡中正", "欧美澎湃", "真律还原", "游戏优化", "细腻佳音", "温婉人声"};

    public static String ancName(int v) {
        return (v >= 0 && v < ANC_NAMES.length) ? ANC_NAMES[v] : ("未知(" + v + ")");
    }

    public static String eqName(int v) {
        return (v >= 0 && v < EQ_NAMES.length) ? EQ_NAMES[v] : ("未知(" + v + ")");
    }

    // ------------------------------------------------------------------

    /** 组帧：{@code [0]=0x4E [1..2]=len(LE) [3]=0x00 [4..5]=opcode(LE) [6..]=params} */
    public static byte[] build(int opCode, byte... params) {
        int payloadLength = 3 + params.length;
        byte[] p = new byte[3 + payloadLength];
        p[0] = MAGIC;
        p[1] = (byte) (payloadLength & 0xFF);
        p[2] = (byte) ((payloadLength >> 8) & 0xFF);
        p[3] = 0x00;
        p[4] = (byte) (opCode & 0xFF);
        p[5] = (byte) ((opCode >> 8) & 0xFF);
        System.arraycopy(params, 0, p, 6, params.length);
        return p;
    }

    public static byte[] query(int op) { return build(op); }

    public static byte[] setAnc(int mode) {
        return build(ANC_SET, (byte) mode, (byte) 0x00);   // 固定尾随 0x00
    }

    public static byte[] setEq(int eq) { return build(EQ_SET, (byte) eq); }
    public static byte[] setWind(boolean on) { return build(WIND_SET, (byte) (on ? 1 : 0)); }
    public static byte[] setGameMode(boolean on) { return build(GAME_MODE_SET, (byte) (on ? 1 : 0)); }
    public static byte[] setInEar(boolean on) { return build(IN_EAR_SET, (byte) (on ? 1 : 0)); }
    public static byte[] setDualConn(boolean on) { return build(DUAL_CONN_SET, (byte) (on ? 1 : 0)); }

    public static int opCodeOf(byte[] p) {
        return ((p[5] & 0xFF) << 8) | (p[4] & 0xFF);
    }

    public static String hex(byte[] b) { return hex(b, b.length); }

    public static String hex(byte[] b, int len) {
        int n = Math.min(len, b.length);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b[i]));
        }
        return sb.toString();
    }

    public static final class Frame {
        public final int opCode;
        public final byte[] params;
        public final int totalLength;

        Frame(int opCode, byte[] params, int totalLength) {
            this.opCode = opCode; this.params = params; this.totalLength = totalLength;
        }

        public int b(int i) { return (i < params.length) ? (params[i] & 0xFF) : -1; }
        public boolean on(int i) { return b(i) == 0x01; }
    }

    public static Frame parse(byte[] buf, int off) {
        if (off + 6 > buf.length) return null;
        if (buf[off] != MAGIC) return null;
        int payloadLen = (buf[off + 1] & 0xFF) | ((buf[off + 2] & 0xFF) << 8);
        int total = payloadLen + 3;
        if (off + total > buf.length) return null;
        int paramsLen = Math.max(0, total - 6);
        byte[] params = new byte[paramsLen];
        System.arraycopy(buf, off + 6, params, 0, paramsLen);
        return new Frame(opCodeOf(buf), params, total);
    }

    /** 流式解帧（含 P0 补上的缓冲区上限检查）。 */
    public static final class StreamDecoder {
        private static final int MAX_BUFFER = 4096;
        private final ByteArrayOutputStream acc = new ByteArrayOutputStream(512);

        public List<Frame> feed(byte[] data, int len) {
            acc.write(data, 0, len);
            byte[] buf = acc.toByteArray();
            List<Frame> out = new ArrayList<>(4);
            int pos = 0;
            while (pos < buf.length) {
                if (buf[pos] != MAGIC) { pos++; continue; }
                if (pos + 3 > buf.length) break;
                int total = (buf[pos + 1] & 0xFF) + 3;
                if (total < 6 || total > MAX_BUFFER) { pos++; continue; }
                if (pos + total > buf.length) break;
                Frame f = parse(buf, pos);
                if (f != null) out.add(f);
                pos += total;
            }
            acc.reset();
            if (pos < buf.length) acc.write(buf, pos, buf.length - pos);
            return out;
        }
    }
}
