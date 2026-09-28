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
import java.nio.charset.StandardCharsets;
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
 * <h2>多版本（ViaVersion / ViaBackwards）</h2>
 * 代理只翻译游戏协议，<b>不翻译自建通道的负载</b>，所以跨版本兼容只能在插件里做。
 * 反编译核对结论：1.21.11（MiniHUD 0.38.x）、26.2（0.40.x）、26.3（0.41.x）的线上格式<B>完全一致</B>
 * ——包类型编号 1–13 相同、握手都用原版 {@code writeNbt}、数据响应用 masa gzip；
 * 唯一差异是元数据里的 {@code servux} 版本串（要与客户端自己的 MC 版本对应）。
 * 因此插件把客户端自报的 {@code version}/{@code servux} <b>原样回显</b>，无需知道对方版本即可兼容。
 * （更老的 MiniHUD 如 1.20.1 的 0.27.1 根本没有 ServuxEntitiesPacket，无从支持。）
 *
 * <h2>区域线程（Canvas / Folia）</h2>
 * 插件消息回调在玩家所属区域线程上执行，那里只读玩家自身状态（位置/世界）；
 * 读取方块实体或实体必须用 {@link org.bukkit.Bukkit#getRegionScheduler()} 跳到其所属区域线程，
 * 回包再通过玩家的 {@code EntityScheduler} 跳回玩家线程发送。
 */
public final class ServuxBridgePlugin extends JavaPlugin implements PluginMessageListener, Listener {

    public static final String CHANNEL = "servux:entity_data";

    /**
     * 代理（Velocity 插件 YinwuClientVer）用来告知「玩家真实客户端 MC 版本」的频道。
     *
     * <p>必须要它：代理上的 ViaVersion 已把协议翻译成服务端版本，后端 ViaVersion 看到的永远是 26.3，
     * 只有代理知道原始版本（实测 26.2 客户端因此收到 servux-fabric-26.3 并被判 Mis-matched）。
     * 负载是版本串的 UTF-8 字节，如 {@code 26.2}。
     */
    public static final String CLIENTVER_CHANNEL = "yinwu:clientver";

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
    /** 记录「已提示过该玩家握手异常」，每位玩家每次进服只提示一条，避免刷屏 */
    private final Set<UUID> warnedIncompatible = ConcurrentHashMap.newKeySet();

    /** 代理中继过来的「玩家真实客户端 MC 版本」（如 26.2），跨版本握手的权威依据 */
    private final Map<UUID, String> clientVersions = new ConcurrentHashMap<>();

    /** ViaVersion 的 API 类名（5.x 与旧版包名不同，逐个尝试；全程反射，不产生硬依赖） */
    private static final String[] VIA_API_CLASSES = {
            "com.viaversion.viaversion.api.Via",
            "us.myles.ViaVersion.api.Via"
    };
    private static final String VIA_API_INTERFACE = "com.viaversion.viaversion.api.ViaAPI";
    private static final String[] VIA_PROTOCOL_CLASSES = {
            "com.viaversion.viaversion.api.protocol.version.ProtocolVersion",
            "us.myles.ViaVersion.api.protocol.ProtocolVersion"
    };
    private Class<?> viaApiClass;
    private Class<?> viaProtocolClass;

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
        getServer().getMessenger().registerIncomingPluginChannel(this, CLIENTVER_CHANNEL, this);
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
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CLIENTVER_CHANNEL, this);
        registered.clear();
        rateWindows.clear();
        warnedIncompatible.clear();
        clientVersions.clear();
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
        warnedIncompatible.remove(id);
        clientVersions.remove(id);
    }

    /**
     * 收到代理中继的客户端 MC 版本（频道 {@code yinwu:clientver}，负载是 UTF-8 的版本串）。
     *
     * <p>这是跨版本握手的权威依据 —— 后端 ViaVersion 因为代理已翻译而只会报服务端版本。
     * 若该玩家此前已经握手（可能已被回了错误的兜底版本串），这里补发一次正确的元数据。
     */
    private void handleClientVersion(Player player, byte[] message) {
        String version = new String(message, StandardCharsets.UTF_8).trim();
        if (!version.matches("\\d+(\\.\\d+)+")) {
            getLogger().warning("[servux] 代理中继的客户端版本串无法识别：\"" + version
                    + "\"（来自 " + player.getName() + "）");
            return;
        }
        UUID id = player.getUniqueId();
        String previous = clientVersions.put(id, version);
        boolean crossVersion = !version.equals(Bukkit.getMinecraftVersion());
        if (!version.equals(previous)) {
            getLogger().info("[servux] 代理告知 " + player.getName() + " 的客户端是 MC " + version
                    + (crossVersion ? "（服务端 " + Bukkit.getMinecraftVersion() + "，跨版本）"
                                    : "（与服务端一致）"));
        }
        // 已经握过手就按正确版本补发一次元数据（客户端若已注销则无害，若还在等则正好用上）
        if (registered.contains(id)) {
            sendMetadata(player, PROTOCOL_VERSION, "servux-fabric-" + version);
            if (debug) {
                getLogger().info("[servux] 已按代理告知的版本补发元数据给 " + player.getName());
            }
        }
    }

    // ------------------------------------------------------------------
    // 收包：本方法在玩家所属区域线程上执行（Folia/Canvas），只碰玩家自身状态
    // ------------------------------------------------------------------
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (CLIENTVER_CHANNEL.equals(channel)) {
            handleClientVersion(player, message);
            return;
        }
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

    /**
     * 类型 2：客户端握手 —— 必须回类型 1 元数据，否则 MiniHUD 会注销通道并关掉数据同步。
     *
     * <p>照上游 Servux 的做法：<b>不管客户端自报什么版本都回元数据、都供数</b>，由客户端自己校验。
     * 旧实现「版本低于 2 就不回包」会让客户端一直重试（实测每秒 11 次）并刷屏，
     * 还让本来能用的客户端（如 26.2 的 MiniHUD 0.40.x）完全拿不到数据。
     *
     * <p><b>多版本</b>：反编译核对后确认，1.21.11 / 26.2 / 26.3 的 MiniHUD 线上格式完全一致
     * （包类型 1–13 相同、握手都用原版 {@code writeNbt}、数据响应用 masa gzip），
     * 差异只在元数据里的 {@code servux} 版本串 —— 客户端会要求它以 {@code servux-fabric-<自己的MC版本>} 开头。
     * 所以这里把客户端自报的 {@code version}/{@code servux} <b>原样回给它</b>：不用知道对方是什么版本，
     * 每个版本的客户端都能通过自己的校验。（ViaVersion/ViaBackwards 不翻译自建通道的负载，只能由插件处理。）
     */
    private void handleMetadataRequest(Player player, FriendlyByteBuf in) {
        UUID id = player.getUniqueId();
        int clientVersion = -1;
        String clientServux = "";

        // 先取样原始字节：既能诊断，也能在原版读取失败时兜底
        byte[] raw = new byte[0];
        int readable = in.readableBytes();
        String hex;
        try {
            raw = new byte[Math.min(readable, 128)];
            in.getBytes(in.readerIndex(), raw);
            hex = toHex(raw, Math.min(raw.length, 24)) + (readable > 24 ? " … 共 " + readable + " 字节" : "");
        } catch (Throwable t) {
            hex = "(取样失败: " + t.getClass().getSimpleName() + ")";
        }

        try {
            CompoundTag request = in.readNbt();
            if (request != null) {
                if (request.contains("version")) {
                    clientVersion = request.getInt("version").orElse(-1);
                }
                if (request.contains("servux")) {
                    clientServux = request.getString("servux").orElse("");
                }
            }
        } catch (Throwable ignored) {
            // 读不出就走下面的字节兜底
        }
        if (clientVersion < 0) {
            clientVersion = scanInt(raw, "version");
        }
        if (clientServux.isEmpty()) {
            clientServux = scanString(raw, "servux");
        }

        // 版本串：客户端握手包里只有 version、没有 servux 字符串，所以必须自己查出它的 MC 版本
        String clientMc = detectClientMcVersion(player);
        String servuxString;
        if (clientMc != null) {
            servuxString = "servux-fabric-" + clientMc;
        } else if (!clientServux.isEmpty()) {
            servuxString = clientServux;            // 客户端将来若带上版本串，就原样回显
        } else {
            servuxString = servuxVersionString;     // 兜底：配置里的值（同版本客户端可用）
        }

        sendMetadata(player, clientVersion > 0 ? clientVersion : PROTOCOL_VERSION, servuxString);

        if (debug) {
            getLogger().info("[servux] 握手 " + player.getName() + "：客户端 version=" + clientVersion
                    + "，MC=" + (clientMc == null ? "未识别" : clientMc) + "，回 servux=\"" + servuxString
                    + "\"，原始: " + hex);
        } else if (clientMc == null && warnedIncompatible.add(id)) {
            getLogger().info("[servux] " + player.getName() + " 的客户端 MC 版本未能识别，已用兜底版本串 \""
                    + servuxString + "\" 回包（同版本客户端可用，跨版本客户端会拒绝）。原始前 24 字节: " + hex);
        } else if (clientMc != null && !clientMc.equals(Bukkit.getMinecraftVersion())
                && warnedIncompatible.add(id)) {
            getLogger().info("[servux] " + player.getName() + " 是跨版本客户端（MC " + clientMc
                    + "，服务端 " + Bukkit.getMinecraftVersion() + "），已按它的版本回 servux-fabric-"
                    + clientMc + " —— 这正是多版本支持的关键一步");
        }

        if (!hasPermission(player)) {
            if (debug) {
                getLogger().info("[servux] " + player.getName() + " 无权限，拒绝供数");
            }
            registered.remove(id);
            return;
        }
        registered.add(id);
    }

    /**
     * 查询客户端真实的 MC 版本（如 {@code "26.2"}）。
     *
     * <p>为什么必须查：MiniHUD 校验的是 {@code servux.startsWith("servux-fabric-" + MaLiLibReference.MC_VERSION)}，
     * 而客户端握手包里<b>只有 version、没有版本串</b>（实测负载就是 16 字节的 {@code {version:2}}），
     * 所以服务端回错版本串（例如对 26.2 客户端回 {@code servux-fabric-26.3}）会让客户端
     * 判为 {@code Mis-matched protocol version}、发 UnregisterReply、注销通道并关掉 ENTITY_DATA_SYNC。
     *
     * <p>版本信息来源：ViaVersion 在代理与后端两端都安装时，会把客户端的协议号转给后端，
     * 这里用反射读取（<b>不产生硬依赖</b>：没装 ViaVersion 或读取失败都只返回 null，退回配置兜底）。
     *
     * @return 形如 {@code "26.2"} / {@code "1.21.11"} 的版本名；无法确定时返回 null
     */
    private String detectClientMcVersion(Player player) {
        // 1) 代理中继（最可靠：只有代理知道原始版本）
        String relayed = clientVersions.get(player.getUniqueId());
        if (relayed != null) {
            return relayed;
        }
        // 2) 后端 ViaVersion（代理没装中继插件时才有意义 —— 但代理已翻译，通常只会得到服务端版本）
        try {
            if (viaApiClass == null) {
                for (String name : VIA_API_CLASSES) {
                    try {
                        viaApiClass = Class.forName(name);
                        break;
                    } catch (ClassNotFoundException ignored) {
                        // 试下一个包名
                    }
                }
            }
            if (viaApiClass == null) {
                return null;   // 没装 ViaVersion
            }
            Object api = viaApiClass.getMethod("getAPI").invoke(null);

            // 优先 ViaAPI.getPlayerProtocolVersion(UUID)（直接给 ProtocolVersion）
            Object protocolVersion = null;
            if (viaProtocolClass == null) {
                for (String name : VIA_PROTOCOL_CLASSES) {
                    try {
                        viaProtocolClass = Class.forName(name);
                        break;
                    } catch (ClassNotFoundException ignored) {
                        // 试下一个包名
                    }
                }
            }
            Class<?> apiInterface = Class.forName(VIA_API_INTERFACE);
            try {
                protocolVersion = apiInterface.getMethod("getPlayerProtocolVersion", UUID.class)
                        .invoke(api, player.getUniqueId());
            } catch (NoSuchMethodException e) {
                // 老版 ViaVersion 只有 int 版
                int protocol = (Integer) apiInterface.getMethod("getPlayerVersion", UUID.class)
                        .invoke(api, player.getUniqueId());
                if (protocol > 0 && viaProtocolClass != null) {
                    protocolVersion = viaProtocolClass.getMethod("getProtocol", int.class).invoke(null, protocol);
                }
            }
            if (protocolVersion == null || viaProtocolClass == null) {
                return null;
            }

            String name = String.valueOf(viaProtocolClass.getMethod("getName").invoke(protocolVersion));
            // 区间名（如 "26.1-26.1.2"、"1.21.9-1.21.10"）取前半段；"Unknown (778)" 之类直接判为未知
            int dash = name.indexOf('-');
            if (dash > 0) {
                name = name.substring(0, dash);
            }
            if (name.matches("\\d+(\\.\\d+)+")) {
                return name;
            }
            if (debug) {
                getLogger().info("[servux] " + player.getName() + " 的客户端版本名无法识别：\"" + name + "\"");
            }
        } catch (Throwable t) {
            if (debug) {
                getLogger().info("[servux] 读取 " + player.getName() + " 的客户端版本失败：" + t);
            }
        }
        return null;
    }

    /**
     * 回类型 1 元数据。
     *
     * @param version 回给客户端的协议版本（原样回它自报的值，各版本客户端各自校验自己的期望值）
     * @param servux  版本串，必须是 {@code servux-fabric-<客户端MC版本>}，否则客户端会注销通道
     */
    private void sendMetadata(Player player, int version, String servux) {
        CompoundTag metadata = new CompoundTag();
        metadata.putInt("version", version);
        metadata.putString("servux", servux);
        metadata.putString("provider", "YinwuServux (Canvas)");

        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        try {
            out.writeVarInt(S2C_METADATA);
            out.writeNbt(metadata);
            send(player, out);
        } finally {
            out.release();
        }
    }

    /**
     * 字节兜底：在原始负载里找 NBT 的 ASCII 键名，取紧随其后的 4 字节 int。
     * 万一某个版本的 NBT 编码细节不同、原版 {@code readNbt()} 解析失败时用。
     */
    private static int scanInt(byte[] data, String key) {
        byte[] k = key.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 2; i + k.length + 4 <= data.length; i++) {
            for (int j = 0; j < k.length; j++) {
                if (data[i + j] != k[j]) {
                    continue outer;
                }
            }
            // 键名前面应依次是两字节长度与标签类型，这里只校验长度
            if (data[i - 2] == 0 && data[i - 1] == (byte) k.length) {
                int at = i + k.length;
                return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                        | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            }
        }
        return -1;
    }

    /** 字节兜底：取 NBT 里某个字符串键的值（{@code [00 len][key][00 len][value]}）。 */
    private static String scanString(byte[] data, String key) {
        byte[] k = key.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 2; i + k.length + 2 <= data.length; i++) {
            for (int j = 0; j < k.length; j++) {
                if (data[i + j] != k[j]) {
                    continue outer;
                }
            }
            if (data[i - 2] == 0 && data[i - 1] == (byte) k.length) {
                int at = i + k.length;
                int len = ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
                if (len > 0 && at + 2 + len <= data.length) {
                    return new String(data, at + 2, len, StandardCharsets.US_ASCII);
                }
            }
        }
        return "";
    }

    private static String toHex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(Character.forDigit((data[i] >> 4) & 0xF, 16)).append(Character.forDigit(data[i] & 0xF, 16));
        }
        return sb.toString();
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
