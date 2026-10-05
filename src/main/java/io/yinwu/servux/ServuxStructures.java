package io.yinwu.servux;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.util.BoundingBox;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * {@code servux:structures} 通道 —— 把**结构边界框**发给 MiniHUD，让它在多人服上也能渲染结构范围。
 * 这是上游 Servux 的招牌功能（上游 README 原话：0.1.x 时期只有这一个功能）。
 *
 * <p><b>协议（从 0.12.2 发布版 jar 反编译核对，PROTOCOL_VERSION = 3，不是 HEAD 里的 1）</b>：
 * <pre>
 * 1 = S2C_METADATA            原版 NBT
 * 2 = S2C_STRUCTURE_DATA      结构数据（走 PacketSplitter 分片）
 * 3 = C2S_STRUCTURES_REGISTER 客户端订阅（不是 minecraft:register！）
 * 4 = C2S_STRUCTURES_UNREGISTER
 * 5 = S2C_STRUCTURE_DATA_START 大包起始（本实现用 PacketSplitter，暂不发这个）
 * </pre>
 *
 * <p><b>发给客户端的 NBT（键名由客户端 {@code minihud.util.StructureData} 的解析代码确定）</b>：
 * <pre>
 * { Structures: [ { id: "minecraft:village_plains",  // 必须是客户端 StructureType 认得的原版结构名
 *                   ChunkX: int, ChunkZ: int,        // 客户端不读，按原版格式带上
 *                   Children: [ { BB: [I; x1,y1,z1,x2,y2,z2] }, ... ] } ] }
 * </pre>
 * 注意 BB 的**上界是闭区间**（原版 BoundingBox 的 NBT 约定），而 Bukkit 的 {@link BoundingBox}
 * 上界是**开区间** —— 所以写入时要 −1，否则客户端画出来的框会大 1 格。
 *
 * <p><b>为什么读已生成数据而不是按种子算</b>：MC 的结构定位是
 * {@code structureSeed(世界种子低 48 位) + 每个结构集各自的 salt}，不同结构用不同派生随机源，
 * 插件无法可靠复刻；只要区块生成完，结果就在区块数据里。
 */
final class ServuxStructures {

    static final String CHANNEL = "servux:structures";
    static final int PROTOCOL_VERSION = 3;

    static final int S2C_METADATA = 1;
    static final int S2C_STRUCTURE_DATA = 2;
    static final int C2S_STRUCTURES_REGISTER = 3;
    static final int C2S_STRUCTURES_UNREGISTER = 4;

    /** 上游的刷新节奏：40 tick 检查一次，同一结构 30 秒（600 tick）后重发。 */
    private static final long REFRESH_INTERVAL_TICKS = 1L;
    private static final long RESEND_AFTER_TICKS = 600L;
    /** 视距外多算 2 个区块（对齐上游）。 */
    private static final int VIEW_MARGIN_CHUNKS = 2;
    /** 每 tick 最多处理多少个区块 —— Folia 上把首轮同步摊开，避免一次派发几百个区域任务。 */
    private static final int MAX_CHUNKS_PER_TICK = 160;
    /** 去重指纹表的上限，超了就清空重建（防长会话内存膨胀）。 */
    private static final int MAX_FINGERPRINTS = 8192;

    private final ServuxBridgePlugin plugin;

    /** 已订阅的玩家。 */
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();
    /** 玩家当前维度，用于维度切换时清空重发。 */
    private final Map<UUID, String> dimensions = new ConcurrentHashMap<>();
    /** 每个玩家：已发送的区块 → 发送时的 tick（超时重发用）。 */
    private final Map<UUID, Map<Long, Long>> sentChunks = new ConcurrentHashMap<>();
    /** 每个玩家：本批次待读取的区块队列。 */
    private final Map<UUID, ArrayDeque<long[]>> pending = new ConcurrentHashMap<>();
    /** 每个玩家：已发出的结构指纹（结构 id + 包围盒），跨区块去重。 */
    private final Map<UUID, Set<String>> fingerprints = new ConcurrentHashMap<>();
    /** 每个玩家：本批次累积的结构 NBT，凑够就发。 */
    private final Map<UUID, ListTag> batch = new ConcurrentHashMap<>();
    /** 每批结构开始攒的 tick，用于攒太久就先发 */
    private final Map<UUID, Long> batchSince = new ConcurrentHashMap<>();

    ServuxStructures(ServuxBridgePlugin plugin) {
        this.plugin = plugin;
    }

    // ---- 入口 ----

    /**
     * 处理客户端包。
     *
     * <p><b>实测坑（2026-09-29，日志证据："结构通道收到未处理的包类型 10"）</b>：
     * 客户端发来的注册/注销包**没有分片长度前缀** ✗，包体直接就是 {@code [varint 类型][NBT]}。
     * 若按"首片带 varint 总长"去读，就会把类型号当成总长、把 NBT 的 {@code 0x0A}(TAG_Compound)
     * 当成包类型，于是看到类型 10。
     *
     * <p>所以先探测第一个 varint 是不是合法包类型：是 → 直接当完整包用；
     * 不是 → 才按分片交给 {@link PacketSplitter} 累积（为将来可能出现的大包留路）。
     */
    void handle(Player player, byte[] raw) {
        byte[] payload = raw;
        int first = peekVarInt(raw);
        if (first != C2S_STRUCTURES_REGISTER && first != C2S_STRUCTURES_UNREGISTER) {
            try {
                byte[] full = PacketSplitter.receive(player.getUniqueId(), CHANNEL, raw);
                if (full == null) {
                    return; // 还在等后续分片
                }
                payload = full;
            } catch (Throwable t) {
                plugin.getLogger().warning("[servux] 结构通道分片重组失败（" + player.getName() + "）：" + t);
                return;
            }
        }

        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            int type = in.readVarInt();
            switch (type) {
                case C2S_STRUCTURES_REGISTER -> register(player);
                case C2S_STRUCTURES_UNREGISTER -> unregister(player);
                default -> plugin.debugLog(player, "结构通道收到未处理的包类型 " + type);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[servux] 处理结构包出错（" + player.getName() + "）：" + t);
        } finally {
            in.release();
        }
    }

    /** 只探测首个 varint，不改动原数组。 */
    private static int peekVarInt(byte[] data) {
        int value = 0;
        int position = 0;
        while (position < Math.min(data.length, 5)) {
            byte current = data[position++];
            value |= (current & 0x7F) << (7 * (position - 1));
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }

    private void register(Player player) {
        UUID id = player.getUniqueId();
        if (registered.add(id)) {
            dimensions.put(id, player.getWorld().getName());
            sentChunks.remove(id);
            fingerprints.remove(id);
            plugin.debugLog(player, "已订阅结构边界框");
        }
        sendMetadata(player);
        // 首轮同步：下一个刷新周期自然会把队列填满
    }

    private void unregister(Player player) {
        UUID id = player.getUniqueId();
        registered.remove(id);
        dimensions.remove(id);
        sentChunks.remove(id);
        pending.remove(id);
        fingerprints.remove(id);
        batch.remove(id);
        plugin.debugLog(player, "已取消订阅结构边界框");
    }

    void onQuit(UUID id) {
        registered.remove(id);
        dimensions.remove(id);
        sentChunks.remove(id);
        pending.remove(id);
        fingerprints.remove(id);
        batch.remove(id);
    }

    private void sendMetadata(Player player) {
        CompoundTag meta = new CompoundTag();
        meta.putString("id", CHANNEL);
        meta.putInt("version", PROTOCOL_VERSION);
        meta.putInt("timeout", (int) RESEND_AFTER_TICKS);
        // 与实体通道同思路：回客户端自报的版本串，让它自己校验通过。
        // 客户端校验（DataStorage.receiveServuxStrucutresMetadata）：
        //   version == 3 且 servux.startsWith("servux-fabric-" + 客户端的 MC 版本)
        //   注意：客户端同时要求「结构边界框渲染总开关」是开着的，否则它会安静地注销掉。
        meta.putString("servux", plugin.structuresVersionString(player));
        // ⚠ 元数据**不能分片**：客户端对类型 1 直接 fromPacket 解析，
        //   分片会让它把「总长」当成包类型（客户端日志：invalid packet type received）。
        byte[] payload = PacketSplitter.typeAndCompound(S2C_METADATA, meta);
        plugin.sendOnPlayerThread(player, ServuxBridgePlugin.STRUCTURES_CHANNEL,
                List.of(payload), "结构通道元数据（未分片，" + payload.length + " 字节）");
    }

    // ---- 周期任务（由插件每 40 tick 调一次）----

    void tick(long currentTick) {
        for (UUID id : registered) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                continue;
            }
            if (!player.isOnline()) {
                continue;
            }
            String world = player.getWorld().getName();
            String previous = dimensions.put(id, world);
            if (previous != null && !previous.equals(world)) {
                // 维度切换：清空重来（上游同款行为）
                sentChunks.remove(id);
                fingerprints.remove(id);
                pending.remove(id);
                batch.remove(id);
            }
            // 区块读取必须在区块自己的区域线程上做 → 派发到玩家区域线程去排队
            plugin.schedulerAtEntity(player, () -> refill(player, currentTick));
        }
    }

    /** 在玩家的区域线程上：算范围、填队列，然后按上限逐个派发区块读取。 */
    private void refill(Player player, long currentTick) {
        UUID id = player.getUniqueId();
        World world = player.getWorld();
        int view = plugin.getServer().getViewDistance() + VIEW_MARGIN_CHUNKS;
        int centerX = player.getLocation().getBlockX() >> 4;
        int centerZ = player.getLocation().getBlockZ() >> 4;

        Map<Long, Long> sent = sentChunks.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        ArrayDeque<long[]> queue = pending.computeIfAbsent(id, k -> new ArrayDeque<>());

        if (queue.isEmpty()) {
            for (int dx = -view; dx <= view; dx++) {
                for (int dz = -view; dz <= view; dz++) {
                    int cx = centerX + dx;
                    int cz = centerZ + dz;
                    long key = chunkKey(cx, cz);
                    Long last = sent.get(key);
                    if (last != null && currentTick - last < RESEND_AFTER_TICKS) {
                        continue; // 刚发过，跳过
                    }
                    if (!world.isChunkLoaded(cx, cz)) {
                        continue; // 不主动加载/生成区块
                    }
                    queue.add(new long[]{cx, cz});
                }
            }
        }

        int budget = MAX_CHUNKS_PER_TICK;
        List<long[]> take = new ArrayList<>();
        while (budget-- > 0 && !queue.isEmpty()) {
            take.add(queue.poll());
        }
        for (long[] c : take) {
            int cx = (int) c[0];
            int cz = (int) c[1];
            plugin.schedulerAtChunk(world, cx, cz, () -> readChunk(id, world, cx, cz, currentTick));
        }

        // 队列空了就把这一批发出去（还有内容的话）
        Long since = batchSince.get(id);
        if (queue.isEmpty() || (since != null && currentTick - since >= 10L)) {
            flush(id);
        }
    }

    /** 在区块自己的区域线程上读结构。 */
    private void readChunk(UUID playerId, World world, int cx, int cz, long currentTick) {
        try {
            if (!world.isChunkLoaded(cx, cz)) {
                return;
            }
            Chunk chunk = world.getChunkAt(cx, cz); // 已加载，不会触发生成
            List<CompoundTag> entries = new ArrayList<>();
            Set<String> seen = fingerprints.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
            if (seen.size() > MAX_FINGERPRINTS) {
                seen.clear();
            }

            for (GeneratedStructure generated : chunk.getStructures()) {
                Structure structure = generated.getStructure();
                if (structure == null) {
                    continue;
                }
                BoundingBox box = generated.getBoundingBox();
                String fingerprint = structure.key() + "@" + box.getMinX() + "," + box.getMinY() + ","
                        + box.getMinZ() + "," + box.getMaxX() + "," + box.getMaxY() + "," + box.getMaxZ();
                if (!seen.add(fingerprint)) {
                    continue; // 同一结构跨多个区块 → 去重
                }
                entries.add(structureTag(structure, generated, box, cx, cz));
            }

            if (!entries.isEmpty()) {
                ListTag list = batch.computeIfAbsent(playerId, k -> new ListTag());
                batchSince.putIfAbsent(playerId, currentTick);
                synchronized (list) {
                    for (CompoundTag entry : entries) {
                        list.add(entry);
                    }
                }
                sentChunks.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(chunkKey(cx, cz), currentTick);
            } else {
                // 没有结构也要记时间，避免每轮重复扫这个区块
                sentChunks.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(chunkKey(cx, cz), currentTick);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[servux] 读取区块结构出错 (" + cx + "," + cz + ")：" + t);
        }
    }

    /** 组装单个结构的 NBT（键名与客户端 minihud.util.StructureData 的解析一一对应）。 */
    private CompoundTag structureTag(Structure structure, GeneratedStructure generated,
                                     BoundingBox box, int cx, int cz) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", structure.key().toString());
        tag.putInt("ChunkX", cx);
        tag.putInt("ChunkZ", cz);

        ListTag children = new ListTag();
        // 客户端 minihud.util.StructureData 解析每个子项时读的是 id + BB 两个键：
        //   只给 BB 不给 id 会被 getStringOrDefault("id", "?") 兜成 "?" 从而整条丢弃（实测 sC=0）
        String pieceId = structure.key().toString();
        try {
            for (StructurePiece piece : generated.getPieces()) {
                BoundingBox pbox = piece.getBoundingBox();
                if (pbox != null) {
                    children.add(pieceTag(pieceId, pbox));
                }
            }
        } catch (Throwable ignored) {
            // 拿不到 piece 就退回整体框
        }
        if (children.isEmpty()) {
            children.add(pieceTag(pieceId, box));
        }
        tag.put("Children", children);
        return tag;
    }

    /** 单个 piece 的子项：客户端要 id + BB 两个键。 */
    private CompoundTag pieceTag(String pieceId, BoundingBox box) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", pieceId);
        tag.put("BB", boxTag(box));
        return tag;
    }
    /** BB 写成原版约定：上界闭区间（Bukkit 的 BoundingBox 上界是开区间，所以要 −1）。 */
    private IntArrayTag boxTag(BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX());
        int minY = (int) Math.floor(box.getMinY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxX = (int) Math.ceil(box.getMaxX()) - 1;
        int maxY = (int) Math.ceil(box.getMaxY()) - 1;
        int maxZ = (int) Math.ceil(box.getMaxZ()) - 1;
        return new IntArrayTag(new int[]{minX, minY, minZ, maxX, maxY, maxZ});
    }

    /**
     * masa 的结构数据负载格式（与实体通道的 masaGzip 同款，只是不做 32KB 上限检查——
     * 大包交给 PacketSplitter 分片）：
     *   {@code [int 长度][gzip( [byte 10][UTF 空根名][NBT 负载] )]}
     *
     * <p>客户端 {@code DataByteBufUtils.fromByteBuf} 第一件事就是 {@code readInt()} +
     * {@code GZIPInputStream}，所以裸 NBT 一定会被读崩（实测报错固定在 readerIndex(4)）。
     */
    private byte[] masaPacket(CompoundTag nbt) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(2048);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(compressed))) {
            out.writeByte(nbt.getId());   // 10 = TAG_Compound
            out.writeUTF("");             // 根名称为空
            nbt.write(out);
        }
        byte[] gz = compressed.toByteArray();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(gz.length + 4));
        try {
            buf.writeInt(gz.length);
            buf.writeBytes(gz);
            return toArray(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] toArray(FriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }
    /** 把这一批结构作为类型 2 包发出去。
     *
     *  <p>分片方式（照客户端 {@code ServuxStructuresHandler} 的读法）：
     *  纯 NBT → {@link PacketSplitter#split} 切片（首片带 varint 总长）→
     *  **每一片各自套上 {@code [varint 2]}** 发出去；客户端把每片 buffer 喂给自己的
     *  splitter 会话，重组出完整 NBT 再解析。所以类型号是每片都带的。 */
    private void flush(UUID playerId) {
        ListTag list = batch.remove(playerId);
        batchSince.remove(playerId);
        if (list == null || list.isEmpty()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.put("Structures", list);
        byte[] nbt;
        try {
            nbt = masaPacket(payload);
        } catch (IOException e) {
            plugin.getLogger().warning("[servux] 打包结构数据失败：" + e);
            return;
        }   // [int 长度][gzip NBT] —— 客户端 DataByteBufUtils.fromByteBuf 就是这么读的
        List<byte[]> chunks = PacketSplitter.split(nbt);
        List<byte[]> packets = new ArrayList<>(chunks.size());
        for (byte[] chunk : chunks) {
            packets.add(PacketSplitter.frame(S2C_STRUCTURE_DATA, chunk));
        }
        int count = list.size();
        plugin.sendOnPlayerThread(player, ServuxBridgePlugin.STRUCTURES_CHANNEL, packets,
                "结构数据（" + count + " 条），分 " + packets.size() + " 片，NBT " + nbt.length + " 字节");
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
