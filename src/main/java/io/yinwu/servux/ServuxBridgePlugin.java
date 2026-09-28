package io.yinwu.servux;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * YinwuServux —— 在 Canvas / Paper 服务端实现 masa 的 Servux 协议（servux:entity_data 通道），
 * 让客户端 MiniHUD 的这些功能可用：
 * <ul>
 *   <li><b>容器预览</b>：看向箱子/木桶/潜影盒等方块容器时在 HUD 上显示内容（方块实体分支）</li>
 *   <li><b>村民信息</b>、箱子船/驴/展示框等实体容器（实体分支）——
 *       MiniHUD 的 {@code OverlayRendererVillagerInfo} 读的就是实体的 NBT（职业/等级/交易都在里面）</li>
 * </ul>
 *
 * <h2>协议（从 servux-fabric-26.3-0.12.2 与 minihud-fabric-26.3-0.41.2 反编译核对）</h2>
 * 通道名 {@code servux:entity_data}；所有包以 <b>varint 类型号</b>开头，类型号不是枚举序号：
 * <pre>
 *   1  S2C_METADATA                     [varint 1][原版 NBT]                     ← 服务端回元数据（必须！）
 *   2  C2S_METADATA_REQUEST             [varint 2][原版 NBT{version}]            ← 客户端握手
 *   3  C2S_BLOCK_ENTITY_REQUEST         [varint 3][BlockPos 打包 long]           ← 请求方块实体（容器）
 *   4  C2S_ENTITY_REQUEST               [varint 4][varint 实体 id]               ← 请求实体（村民/箱子船…）
 *   5  S2C_BLOCK_NBT_RESPONSE_SIMPLE    [varint 5][BlockPos][int 长度][gzip NBT]
 *   6  S2C_ENTITY_NBT_RESPONSE_SIMPLE   [varint 6][varint 实体 id][int 长度][gzip NBT]
 *   7  C2S_UNREGISTER_REPLY             [原版 NBT]
 *   10/11 S2C_NBT_RESPONSE_START / DATA  分片（本插件暂未实现）
 * </pre>
 * "gzip NBT"是 masa 的 {@code DataByteBufUtils} 格式：{@code [int 长度][gzip( [byte 10][UTF 根名][NBT 负载] )]}，
 * 等价于原版 {@code NbtIo.writeAnyTag}（根名为空串）。原版 {@code FriendlyByteBuf.writeNbt} 不压缩，直接用会解析失败。
 *
 * <h2>握手是硬性要求</h2>
 * 客户端的 {@code EntityDataManager.receiveServuxMetadata} 校验：元数据必须含 {@code version=2}
 * 且 {@code servux} 字符串以 {@code "servux-fabric-<客户端MC版本>"} 开头，否则它会注销通道并关掉 ENTITY_DATA_SYNC；
 * <b>握手之前发的数据包会被客户端直接丢弃</b>。
 *
 * <h2>区域线程（Canvas / Folia）</h2>
 * 插件消息回调在玩家所属区域线程上执行，那里只读玩家自身状态（位置/世界）；
 * 读取方块实体或实体必须用 {@link org.bukkit.Bukkit#getRegionScheduler()} 跳到其所属区域线程，
 * 回包再通过玩家的 {@code EntityScheduler} 跳回玩家线程发送。
 */
public final class ServuxBridgePlugin extends JavaPlugin implements PluginMessageListener, Listener {

    public static final String CHANNEL = "servux:entity_data";

    private static final int S2C_METADATA = 1;
    private static final int C2S_METADATA_REQUEST = 2;
    private static final int C2S_BLOCK_ENTITY_REQUEST = 3;
    private static final int C2S_ENTITY_REQUEST = 4;
    private static final int S2C_BLOCK_NBT_RESPONSE_SIMPLE = 5;
    private static final int S2C_ENTITY_NBT_RESPONSE_SIMPLE = 6;
    private static final int C2S_UNREGISTER_REPLY = 7;

    /** Servux 协议版本，客户端要求 == 2。 */
    private static final int PROTOCOL_VERSION = 2;
    /** 单个插件消息的硬上限（MC 的 custom payload 上限 32767）。 */
    private static final int MAX_PAYLOAD = 32767;

    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();
    private final Map<UUID, int[]> rateWindows = new ConcurrentHashMap<>();

    // ---- config.yml ----
    private String permission = "";
    private String servuxVersionString = "servux-fabric-26.3";
    private double maxDistance = 8.0D;
    private int maxRequestsPerSecond = 10;
    private boolean allowOtherPlayerInventory = false;
    private boolean debug = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("[servux] 已启用：通道=" + CHANNEL
                + "，权限=" + (permission.isEmpty() ? "（所有人）" : permission)
                + "，元数据版本串=" + servuxVersionString
                + "，最大距离=" + (maxDistance <= 0 ? "不限" : maxDistance + " 格")
                + "，限速=" + (maxRequestsPerSecond <= 0 ? "不限" : maxRequestsPerSecond + "/秒")
                + "，可读他人物品栏=" + allowOtherPlayerInventory);
        getLogger().info("[servux] 支持：方块容器（类型 3/5）+ 实体（类型 4/6，村民信息/箱子船等）");
        if (permission.isEmpty()) {
            getLogger().warning("[servux] 注意：permission 为空 = 任何玩家都能读取附近容器与实体的数据。"
                    + "若要限制，请在 config.yml 填 yinwu.servux.use 并用 LuckPerms 授权。");
        }
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        registered.clear();
        rateWindows.clear();
        getLogger().info("[servux] 已停用");
    }

    private void loadSettings() {
        reloadConfig();
        permission = String.valueOf(getConfig().getString("permission", "")).trim();
        servuxVersionString = String.valueOf(getConfig().getString("servux-version-string", "servux-fabric-26.3")).trim();
        maxDistance = getConfig().getDouble("max-distance", 8.0D);
        maxRequestsPerSecond = getConfig().getInt("max-requests-per-second", 10);
        allowOtherPlayerInventory = getConfig().getBoolean("allow-other-player-inventory", false);
        debug = getConfig().getBoolean("debug", false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        registered.remove(id);
        rateWindows.remove(id);
    }

    // ------------------------------------------------------------------
    // 收包：本方法在玩家所属区域线程上执行（Folia/Canvas），只碰玩家自身状态
    // ------------------------------------------------------------------
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(message));
        try {
            int type = in.readVarInt();
            switch (type) {
                case C2S_METADATA_REQUEST -> handleMetadataRequest(player, in);
                case C2S_BLOCK_ENTITY_REQUEST -> handleBlockEntityRequest(player, in.readBlockPos());
                case C2S_ENTITY_REQUEST -> handleEntityRequest(player, in.readVarInt());
                case C2S_UNREGISTER_REPLY -> {
                    registered.remove(player.getUniqueId());
                    if (debug) {
                        getLogger().info("[servux] " + player.getName() + " 已注销");
                    }
                }
                default -> {
                    if (debug) {
                        getLogger().info("[servux] 忽略未处理的包类型 " + type + "（来自 " + player.getName() + "）");
                    }
                }
            }
        } catch (Throwable t) {
            getLogger().warning("[servux] 处理 " + player.getName() + " 的包时出错：" + t);
        } finally {
            in.release();
        }
    }

    /** 类型 2：客户端握手。必须回类型 1 元数据，否则 MiniHUD 会关掉功能并注销通道。 */
    private void handleMetadataRequest(Player player, FriendlyByteBuf in) {
        int clientVersion = -1;
        try {
            CompoundTag request = in.readNbt();
            if (request != null && request.contains("version")) {
                clientVersion = request.getInt("version").orElse(-1);
            }
        } catch (Throwable ignored) {
            // 客户端没带 NBT 也无妨，按版本不足处理
        }
        if (clientVersion < PROTOCOL_VERSION) {
            getLogger().warning("[servux] " + player.getName() + " 的协议版本为 " + clientVersion
                    + "，低于要求的 " + PROTOCOL_VERSION + "，拒绝");
            return;
        }
        if (!hasPermission(player)) {
            if (debug) {
                getLogger().info("[servux] " + player.getName() + " 无权限，拒绝握手");
            }
            return;
        }

        CompoundTag metadata = new CompoundTag();
        metadata.putInt("version", PROTOCOL_VERSION);
        metadata.putString("servux", servuxVersionString);
        metadata.putString("provider", "YinwuServux (Canvas)");

        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        try {
            out.writeVarInt(S2C_METADATA);
            out.writeNbt(metadata);
            send(player, out);
        } finally {
            out.release();
        }

        registered.add(player.getUniqueId());
        if (debug) {
            getLogger().info("[servux] 已与 " + player.getName() + " 完成握手（客户端协议版本 " + clientVersion + "）");
        }
    }

    // ------------------------------------------------------------------
    // 类型 3：方块实体（容器）—— 跳到方块所属区域线程
    // ------------------------------------------------------------------
    private void handleBlockEntityRequest(Player player, BlockPos pos) {
        UUID id = player.getUniqueId();
        if (!registered.contains(id) || !hasPermission(player) || !allowRequest(id)) {
            return;
        }
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        if (tooFar(eye, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)) {
            debugLog(player, "方块 " + pos + " 超出 " + maxDistance + " 格，忽略");
            return;
        }

        Bukkit.getRegionScheduler().execute(this, world, pos.getX() >> 4, pos.getZ() >> 4, () -> {
            byte[] payload;
            try {
                ServerLevel level = ((CraftWorld) world).getHandle();
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity == null) {
                    return;
                }
                CompoundTag nbt = blockEntity.saveWithFullMetadata(level.registryAccess());
                payload = buildBlockResponse(pos, nbt);
            } catch (Throwable t) {
                getLogger().warning("[servux] 读取方块实体 " + pos + " 失败：" + t);
                return;
            }
            sendFromPlayerThread(player, payload, "方块实体 " + pos);
        });
    }

    // ------------------------------------------------------------------
    // 类型 4：实体（村民信息 / 箱子船 / 驴 / 展示框…）—— 在玩家所在区域线程里查
    // ------------------------------------------------------------------
    private void handleEntityRequest(Player player, int entityId) {
        UUID id = player.getUniqueId();
        if (!registered.contains(id) || !hasPermission(player) || !allowRequest(id)) {
            return;
        }
        World world = player.getWorld();
        Location eye = player.getEyeLocation();

        // 客户端只会请求"正看着"的实体，所以它必然在玩家附近 → 在玩家所在区域线程查
        Bukkit.getRegionScheduler().execute(this, eye, () -> {
            byte[] payload;
            try {
                ServerLevel level = ((CraftWorld) world).getHandle();
                Entity entity = level.getEntity(entityId);
                if (entity == null) {
                    debugLog(player, "找不到实体 id=" + entityId);
                    return;
                }
                if (tooFar(eye, entity.getX(), entity.getY(), entity.getZ())) {
                    debugLog(player, "实体 id=" + entityId + " 超出 " + maxDistance + " 格，忽略");
                    return;
                }
                CompoundTag nbt = new CompoundTag();
                TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                entity.saveWithoutId(output);
                nbt = output.buildResult();
                Identifier typeId = EntityType.getKey(entity.getType());
                if (typeId != null) {
                    nbt.putString("id", typeId.toString());
                }
                // 玩家实体：默认不把别人的物品栏/末影箱内容给出去（与 Servux 的默认行为一致）
                if (entity instanceof net.minecraft.world.entity.player.Player
                        && !entity.getUUID().equals(player.getUniqueId())
                        && !allowOtherPlayerInventory) {
                    nbt.put("Inventory", new ListTag());
                    nbt.put("EnderItems", new ListTag());
                }
                payload = buildEntityResponse(entityId, nbt);
                if (debug) {
                    // MiniHUD 靠客户端实体的 Brain 记忆 JOB_SITE 决定把文字牌画在职业方块还是村民头顶，
                    // 而 Brain 是从这里发过去的 NBT 灌进客户端的，所以这条日志是排查位置问题的关键。
                    getLogger().info("[servux] 实体 id=" + entityId + " NBT: 含Brain=" + nbt.contains("Brain")
                            + "，压缩后 " + payload.length + " 字节");
                }
            } catch (Throwable t) {
                getLogger().warning("[servux] 读取实体 id=" + entityId + " 失败：" + t);
                return;
            }
            sendFromPlayerThread(player, payload, "实体 id=" + entityId);
        });
    }

    /** 回到玩家所属区域线程再发包。 */
    private void sendFromPlayerThread(Player player, byte[] payload, String what) {
        player.getScheduler().execute(this, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(this, CHANNEL, payload);
                debugLog(player, "已发送 " + what + " 的数据（" + payload.length + " 字节）");
            }
        }, null, 1L);
    }

    /** 类型 5 响应：[varint 5][BlockPos][int 长度][gzip NBT] */
    private byte[] buildBlockResponse(BlockPos pos, CompoundTag nbt) throws IOException {
        byte[] gz = masaGzip(nbt);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(gz.length + 24));
        try {
            buf.writeVarInt(S2C_BLOCK_NBT_RESPONSE_SIMPLE);
            buf.writeBlockPos(pos);
            buf.writeInt(gz.length);
            buf.writeBytes(gz);
            return toArray(buf);
        } finally {
            buf.release();
        }
    }

    /** 类型 6 响应：[varint 6][varint 实体 id][int 长度][gzip NBT] */
    private byte[] buildEntityResponse(int entityId, CompoundTag nbt) throws IOException {
        byte[] gz = masaGzip(nbt);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(gz.length + 24));
        try {
            buf.writeVarInt(S2C_ENTITY_NBT_RESPONSE_SIMPLE);
            buf.writeVarInt(entityId);
            buf.writeInt(gz.length);
            buf.writeBytes(gz);
            return toArray(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * masa 的 NBT 序列化：gzip( [byte 10][UTF 根名(空)][NBT 负载] )，等价于 NbtIo.writeAnyTag。
     * 与 Servux 的 DataByteBufUtils 完全一致。
     */
    private byte[] masaGzip(CompoundTag nbt) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(1024);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(compressed))) {
            out.writeByte(nbt.getId());   // 10 = TAG_Compound
            out.writeUTF("");             // 根名称为空
            nbt.write(out);
        }
        byte[] gz = compressed.toByteArray();
        if (gz.length + 32 > MAX_PAYLOAD) {
            throw new IOException("NBT 压缩后 " + gz.length + " 字节，超出单个插件消息上限"
                    + "（Servux 此时会用 10/11 分片包，本插件暂未实现）");
        }
        return gz;
    }

    private static byte[] toArray(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    private void send(Player player, FriendlyByteBuf buf) {
        player.sendPluginMessage(this, CHANNEL, toArray(buf));
    }

    private boolean tooFar(Location eye, double x, double y, double z) {
        if (maxDistance <= 0) {
            return false;
        }
        double dx = x - eye.getX();
        double dy = y - eye.getY();
        double dz = z - eye.getZ();
        return dx * dx + dy * dy + dz * dz > maxDistance * maxDistance;
    }

    private boolean hasPermission(Player player) {
        return permission.isEmpty() || player.hasPermission(permission);
    }

    /** 每秒每玩家限速，防止改包客户端刷请求。 */
    private boolean allowRequest(UUID id) {
        if (maxRequestsPerSecond <= 0) {
            return true;
        }
        long second = System.currentTimeMillis() / 1000L;
        int[] window = rateWindows.computeIfAbsent(id, k -> new int[]{-1, 0});
        synchronized (window) {
            if (window[0] != (int) second) {
                window[0] = (int) second;
                window[1] = 0;
            }
            return ++window[1] <= maxRequestsPerSecond;
        }
    }

    private void debugLog(Player player, String message) {
        if (debug) {
            getLogger().info("[servux] " + player.getName() + "：" + message);
        }
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage("§a[servux] 配置已重载");
                getLogger().info("[servux] 配置已重载");
                return true;
            }
            default -> {
                sender.sendMessage("§b[servux] 通道: " + CHANNEL
                        + "，协议版本: " + PROTOCOL_VERSION
                        + "，元数据版本串: " + servuxVersionString);
                sender.sendMessage("§b[servux] 支持: 方块容器(3/5) + 实体(4/6)；权限: "
                        + (permission.isEmpty() ? "（所有人）" : permission)
                        + "，最大距离: " + (maxDistance <= 0 ? "不限" : maxDistance + " 格")
                        + "，限速: " + (maxRequestsPerSecond <= 0 ? "不限" : maxRequestsPerSecond + "/秒"));
                sender.sendMessage("§b[servux] 已握手的玩家: " + registered.size()
                        + "，可读他人物品栏: " + allowOtherPlayerInventory);
                sender.sendMessage("§7用法: /" + label + " reload");
                return true;
            }
        }
    }
}
