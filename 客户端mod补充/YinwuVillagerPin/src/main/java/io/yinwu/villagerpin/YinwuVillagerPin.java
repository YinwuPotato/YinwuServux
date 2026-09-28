package io.yinwu.villagerpin;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YinwuVillagerPin —— 客户端小 mod（仅客户端）。
 *
 * <p>作用：让 MiniHUD 的村民信息牌（含图书管理员附魔书交易）在多人服务器上也能吸附到职业方块，
 * 效果与单人模式一致。原理见 {@code mixin/MixinOverlayRendererVillagerInfo}。
 *
 * <p>不装这个 mod 的玩家完全不受影响；服务端也无需任何改动。
 */
public class YinwuVillagerPin implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("YinwuVillagerPin");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[YinwuVillagerPin] 已加载：MiniHUD 的村民信息牌将像单人模式一样吸附到讲台"
                + "（job_site 数据来自服务端 Servux 实体 NBT）");
    }
}
