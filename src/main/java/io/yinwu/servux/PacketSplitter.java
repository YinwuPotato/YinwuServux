package io.yinwu.servux;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上游 Servux 的 {@code PacketSplitter} 等价实现（源头是 QuickCarpet / skyrising）。
 *
 * <p><b>谁在用</b>（逐类 javap 扫引用得到的结论，不是推测）：
 * <ul>
 *   <li>客户端：{@code ServuxHudHandler}、{@code ServuxStructuresHandler}</li>
 *   <li>服务端：{@code ServuxHudHandler}、{@code ServuxLitematicaHandler}、
 *       {@code ServuxStructuresHandler}、{@code ServuxTweaksHandler}</li>
 * </ul>
 * <b>{@code servux:entity_data} 两侧都没用分片</b> —— 那边是包类型自带 int 长度，
 * 所以实体通道的收发保持原样，别往这里改。
 *
 * <p><b>线格式</b>：首片前缀 {@code varint 总长}，之后每片 ≤ 单片上限；
 * 接收侧按 {@code (玩家, 通道)} 维护会话，累计到总长即完成。
 *
 * <p><b>常量来自 0.12.2 发布版 jar</b>（与 upstream HEAD 不同！HEAD 是 C2S 1 MB，这里是 32 KB）：
 * S2C 单片 1048571 = 1048576 − 5；C2S 单片 32762 = 32767 − 5；接收累计上限 16 MB。
 */
final class PacketSplitter {

    static final int MAX_TOTAL_PER_PACKET_S2C = 1048576;
    static final int MAX_PAYLOAD_PER_PACKET_S2C = MAX_TOTAL_PER_PACKET_S2C - 5;
    static final int MAX_TOTAL_PER_PACKET_C2S = 32767;
    static final int MAX_PAYLOAD_PER_PACKET_C2S = MAX_TOTAL_PER_PACKET_C2S - 5;
    static final int MAX_RECEIVE_SIZE = 16777216;

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private PacketSplitter() {
    }

    // ---- 发送侧（S2C）----

    /** 按 S2C 上限切分；首片前缀 varint 总长。返回可直接逐片 sendPluginMessage 的负载。 */
    static List<byte[]> split(byte[] payload) {
        List<byte[]> parts = new ArrayList<>();
        int total = payload.length;
        int offset = 0;
        boolean first = true;
        do {
            int len = Math.min(total - offset, MAX_PAYLOAD_PER_PACKET_S2C);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(len + 5));
            try {
                if (first) {
                    buf.writeVarInt(total);
                }
                buf.writeBytes(payload, offset, len);
                parts.add(toArray(buf));
            } finally {
                buf.release();
            }
            offset += len;
            first = false;
        } while (offset < total);
        return parts;
    }

    /**
     * 组一个「包类型 + 原版 NBT」负载。
     * 结构通道用原版 {@code writeNbt}（客户端就是 {@code readNbt()} 读的）；
     * 注意实体通道用的是 masa 自己的 gzip 格式，两者不同，别混用。
     */
    static byte[] typeAndCompound(int packetType, CompoundTag nbt) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            buf.writeVarInt(packetType);
            buf.writeNbt(nbt);
            return toArray(buf);
        } finally {
            buf.release();
        }
    }

    /** 只序列化 NBT（不带类型号）—— 结构数据的每个分片里装的是这个。 */
    static byte[] compoundOnly(CompoundTag nbt) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            buf.writeNbt(nbt);
            return toArray(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * 组一个「包类型 + 原始字节」负载。
     *
     * <p>结构数据的分片是这么发的：把**纯 NBT** 按 {@link #split} 切开，
     * 每一片再各自套上 {@code [varint 2]} 变成一个独立的类型 2 包；
     * 客户端把每个包的 buffer 依次喂给自己的 splitter 会话，重组出完整 NBT 再解析。
     * 也就是说**每一片都要带类型号**，不是"只有首片有头"。
     */
    static byte[] frame(int packetType, byte[] body) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(body.length + 5));
        try {
            buf.writeVarInt(packetType);
            buf.writeBytes(body);
            return toArray(buf);
        } finally {
            buf.release();
        }
    }

    // ---- 接收侧（C2S）----

    /**
     * 收一片。收满返回完整负载，未收满返回 {@code null}。
     *
     * @throws IllegalStateException 声明长度非法或超过 16 MB
     */
    static byte[] receive(UUID player, String channel, byte[] data) {
        String key = player + "|" + channel;
        Session session = SESSIONS.computeIfAbsent(key, k -> new Session());
        synchronized (session) {
            if (session.expected < 0) {
                FriendlyByteBuf head = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                int declared;
                byte[] rest;
                try {
                    declared = head.readVarInt();
                    rest = new byte[head.readableBytes()];
                    head.readBytes(rest);
                } finally {
                    head.release();
                }
                if (declared < 0 || declared > MAX_RECEIVE_SIZE) {
                    SESSIONS.remove(key);
                    throw new IllegalStateException("分片声明的总长为 " + declared
                            + "，超出上限 " + MAX_RECEIVE_SIZE);
                }
                session.expected = declared;
                session.out = new ByteArrayOutputStream(Math.min(declared, 8192));
                session.out.write(rest, 0, rest.length);
            } else {
                session.out.write(data, 0, data.length);
            }

            if (session.out.size() >= session.expected) {
                SESSIONS.remove(key);
                return session.out.toByteArray();
            }
            if (session.out.size() > MAX_RECEIVE_SIZE) {
                SESSIONS.remove(key);
                throw new IllegalStateException("分片累计超过上限 " + MAX_RECEIVE_SIZE);
            }
            return null;
        }
    }

    /** 玩家退出时清掉半截会话，避免泄漏。 */
    static void clear(UUID player) {
        String prefix = player + "|";
        SESSIONS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static byte[] toArray(FriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    private static final class Session {
        private int expected = -1;
        private ByteArrayOutputStream out;
    }
}
