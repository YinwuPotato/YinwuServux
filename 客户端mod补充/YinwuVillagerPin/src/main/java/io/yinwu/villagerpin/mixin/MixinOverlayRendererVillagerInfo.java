package io.yinwu.villagerpin.mixin;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.position.Vec3d;
import fi.dy.masa.minihud.data.EntityDataManager;
import fi.dy.masa.minihud.renderer.OverlayRendererVillagerInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 让 MiniHUD 的村民信息牌（含图书管理员附魔书交易）在多人服务器上也能吸附到职业方块 —— 和单人模式一致。
 *
 * <p>为什么多人下不吸附：MiniHUD 的判定读的是「客户端本地村民实体」的脑：
 * <pre>
 *   living.getBrain().getMemoryInternal(MemoryModuleType.JOB_SITE)
 * </pre>
 * 而原版从不同步村民的脑到客户端（AI 只在服务端 tick），所以多人下这个记忆永远是空的；
 * 单人下之所以能吸附，是因为 MiniHUD 的 {@code WorldUtils.getBestWorld()} 那时返回的是
 * <b>整合服务端</b>的世界，取到的实体的脑里有真数据。
 *
 * <p>本 mixin 补上这份数据：MiniHUD 已经从 Servux（服务端插件，例如 YinwuServux）拿到了完整实体 NBT，
 * 里面有 {@code Brain.memories."minecraft:job_site".value = {dimension, pos}}；
 * 我们在渲染前把它写进客户端村民的脑，MiniHUD 原版逻辑随后自然生效。
 * <b>不改 MiniHUD 的任何判定</b>，只补数据。
 */
@Mixin(OverlayRendererVillagerInfo.class)
public class MixinOverlayRendererVillagerInfo {

    private static final String JOB_SITE_KEY = "minecraft:job_site";

    @Inject(method = "renderAtEntity", at = @At("HEAD"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void yinwu$pinJobSiteFromServuxData(List<String> texts, Entity targetEntity, Vec3d cameraPos,
                                                Minecraft mc, CallbackInfo ci) {
        try {
            if (!(targetEntity instanceof LivingEntity living)) {
                return;
            }
            Brain<?> brain = living.getBrain();
            if (brain == null) {
                return;
            }
            // 脑里已经有 job_site 就别动（单人模式，或已经补过了）
            if (brain.getMemoryInternal(MemoryModuleType.JOB_SITE).isPresent()) {
                return;
            }

            // MiniHUD 缓存的实体 NBT（来自 Servux 的 servux:entity_data 响应）
            CompoundData data = EntityDataManager.getInstance().getFromEntityCacheData(targetEntity.getId());
            if (data == null || !data.containsLenient("Brain")) {
                return;
            }
            CompoundData brainTag = data.getCompound("Brain");
            if (brainTag == null) {
                return;
            }
            CompoundData memories = brainTag.getCompound("memories");
            if (memories == null) {
                return;
            }
            CompoundData entry = memories.getCompound(JOB_SITE_KEY);
            if (entry == null) {
                return;
            }
            CompoundData value = entry.getCompound("value");
            if (value == null) {
                value = entry; // 结构兜底：万一不是 {value:...} 包一层
            }

            String dimension = value.getString("dimension");
            int[] pos = value.getIntArray("pos");
            if (dimension == null || pos == null || pos.length < 3) {
                return;
            }

            ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
            BlockPos blockPos = new BlockPos(pos[0], pos[1], pos[2]);
            ((Brain) brain).setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(dimensionKey, blockPos));
        } catch (Throwable ignored) {
            // 任何异常都不该影响渲染：静默跳过，下一帧再试
        }
    }
}
