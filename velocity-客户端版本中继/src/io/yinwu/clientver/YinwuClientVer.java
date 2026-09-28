package io.yinwu.clientver;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * YinwuClientVer —— 极小的 Velocity 插件：把玩家的<b>真实客户端 MC 版本</b>告诉后端服务器。
 *
 * <h2>为什么需要它</h2>
 * MiniHUD 校验服务端回的 servux 元数据时要满足
 * {@code servux.startsWith("servux-fabric-" + MaLiLibReference.MC_VERSION)}，
 * 而客户端的握手包里只有 {@code version}、<b>没有版本串</b>，所以后端必须知道客户端的 MC 版本。
 * 问题是：<b>代理上的 ViaVersion 已经把协议翻译成服务端版本</b>，后端 ViaVersion 看到的永远是 26.3
 * （实测：26.2 客户端仍然收到 servux-fabric-26.3 → 客户端判定 Mis-matched 并注销通道）。
 * 只有代理这一层知道原始版本，所以就由这里取出来、连到子服时用插件消息告知后端。
 *
 * <h2>取版本的两条路</h2>
 * <ol>
 *   <li>ViaVersion 的 API（反射调用，无硬依赖）：{@code ViaAPI#getPlayerProtocolVersion(UUID)} → {@code ProtocolVersion#getName()}</li>
 *   <li>退路：Velocity 自带的 {@code Player#getProtocolVersion()} → {@code getName()}</li>
 * </ol>
 *
 * <h2>消息格式</h2>
 * 频道 {@code yinwu:clientver}，负载就是版本串的 UTF-8 字节（例如 {@code 26.2}），
 * 由后端 YinwuServux 接收（它按发送者玩家归属该版本）。
 */
@Plugin(id = "yinwu-clientver", name = "YinwuClientVer", version = "1.0.0",
        description = "把玩家真实客户端 MC 版本告知后端，供 YinwuServux 的跨版本握手使用",
        authors = {"YinwuRealm"})
public final class YinwuClientVer {

    /** 与后端的约定频道。 */
    public static final String CHANNEL_NAME = "yinwu:clientver";

    private static final String[] VIA_API_CLASSES = {
            "com.viaversion.viaversion.api.Via",
            "us.myles.ViaVersion.api.Via"
    };
    private static final String VIA_API_INTERFACE = "com.viaversion.viaversion.api.ViaAPI";
    private static final String[] VIA_PROTOCOL_CLASSES = {
            "com.viaversion.viaversion.api.protocol.version.ProtocolVersion",
            "us.myles.ViaVersion.api.protocol.ProtocolVersion"
    };

    private final ProxyServer server;
    private final Logger logger;
    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(CHANNEL_NAME);

    private Class<?> viaApiClass;
    private Class<?> viaProtocolClass;
    private boolean viaMissing;

    @Inject
    public YinwuClientVer(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(channel);
        logger.info("[clientver] 已启用：会把玩家真实 MC 版本通过 {} 告知后端", CHANNEL_NAME);
    }

    /** 玩家连上子服之后再告诉它 —— 太早发会丢（那时后端还没这个玩家的连接）。 */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        String version = resolveVersion(player);
        if (version == null) {
            logger.info("[clientver] 无法确定 {} 的客户端版本，跳过", player.getUsername());
            return;
        }
        byte[] payload = version.getBytes(StandardCharsets.UTF_8);

        // 必须走玩家自己的 ServerConnection：RegisteredServer#sendPluginMessage 是“服务器级”发送，
        // 不带玩家上下文，后端会把版本归给该子服上任意一个在线玩家 —— 单人时看似正常，
        // 多人同服就会串号（26.2 的客户端收到 26.3 的串、26.3 的收到 26.1 的串）。
        player.getCurrentServer().ifPresentOrElse(
                connection -> {
                    connection.sendPluginMessage(channel, payload);
                    logger.info("[clientver] {} -> {}（客户端 MC {}）", player.getUsername(),
                            connection.getServerInfo().getName(), version);
                },
                () -> logger.info("[clientver] {} 当前不在任何子服上，跳过", player.getUsername()));
    }

    /**
     * 取客户端真实 MC 版本名（如 {@code "26.2"}）：优先 ViaVersion，退回 Velocity 自带的协议版本。
     *
     * @return 版本名；无法确定时返回 null
     */
    private String resolveVersion(Player player) {
        String via = viaVersion(player.getUniqueId());
        if (via != null) {
            return via;
        }
        try {
            Object protocolVersion = player.getProtocolVersion();
            String name = String.valueOf(protocolVersion.getClass().getMethod("getName").invoke(protocolVersion));
            return sanitize(name);
        } catch (Throwable t) {
            logger.warn("[clientver] 读取 {} 的协议版本失败：{}", player.getUsername(), t.toString());
            return null;
        }
    }

    /** ViaVersion 的版本名；ViaVersion 不在或调用失败时返回 null。 */
    private String viaVersion(UUID uuid) {
        if (viaMissing) {
            return null;
        }
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
                if (viaApiClass == null) {
                    viaMissing = true;
                    return null;
                }
            }
            Object api = viaApiClass.getMethod("getAPI").invoke(null);
            Class<?> apiInterface = Class.forName(VIA_API_INTERFACE);
            Object protocolVersion = apiInterface.getMethod("getPlayerProtocolVersion", UUID.class).invoke(api, uuid);
            if (protocolVersion == null) {
                return null;
            }
            String name = String.valueOf(protocolVersion.getClass().getMethod("getName").invoke(protocolVersion));
            return sanitize(name);
        } catch (Throwable t) {
            logger.info("[clientver] 通过 ViaVersion 取版本失败，改用 Velocity 自带的协议版本：{}", t.toString());
            return null;
        }
    }

    /**
     * 规范化版本名：ViaVersion 对同一协议号的多个版本会给区间名（如 {@code "26.1-26.1.2"}）→ 取前半段；
     * Velocity 给的是 {@code "26.2"} 这种；非「数字.数字」形式一律判为不可用。
     */
    private static String sanitize(String raw) {
        String name = raw == null ? "" : raw.trim();
        int dash = name.indexOf('-');
        if (dash > 0) {
            name = name.substring(0, dash);
        }
        return name.matches("\\d+(\\.\\d+)+") ? name : null;
    }
}
