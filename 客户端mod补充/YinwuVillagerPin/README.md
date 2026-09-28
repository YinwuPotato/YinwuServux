# YinwuVillagerPin

**客户端小 mod（仅客户端）：让 MiniHUD 的村民信息牌 —— 包括图书管理员的附魔书交易 —— 在多人服务器上也吸附到职业方块（讲台）上，效果和单人模式一致。**

不装它的玩家完全不受影响；服务端也不需要任何改动。

---

## 1. 为什么多人下不吸附（问题根源）

MiniHUD 判定牌子位置时读的是**客户端本地村民实体**的脑：

```java
// OverlayRendererVillagerInfo.renderAtEntity(...)
living.getBrain().getMemoryInternal(MemoryModuleType.JOB_SITE)
```

而**原版从不同步村民的脑给客户端**（AI/传感器只在服务端 tick），所以多人下这个记忆永远是空的，那段判定是死代码 —— 牌子只能跟着村民走。

单人模式之所以能吸附，是因为 MiniHUD 取实体时走的是：

```java
// MiniHUD: EntityUtils.getEntitiesByClass → WorldUtils.getBestWorld(mc)
if (mc.level != null && server != null) return server.getLevel(...);  // 单机：整合服务端世界 → 实体带真脑
else                                    return mc.level;              // 多人：客户端世界 → 空脑
```

## 2. 本 mod 怎么解决（只补数据，不改 MiniHUD 的判定）

MiniHUD 已经从 Servux（服务端插件，例如本仓库的 `YinwuServux`）拿到了**完整的实体 NBT**，其中就包含：

```
Brain.memories."minecraft:job_site".value = { dimension: "minecraft:overworld", pos: [I; x, y, z] }
```

本 mod 在 `renderAtEntity` 渲染前把这份记忆写进客户端村民的脑：

```java
CompoundData data = EntityDataManager.getInstance().getFromEntityCacheData(entityId); // MiniHUD 的缓存
... 读 Brain.memories."minecraft:job_site".value ...
brain.setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(dimension, pos));
```

之后 **MiniHUD 原本的判定自己就会通过** ✓ —— 所以行为和单人模式一样，而且不用改 MiniHUD 一行逻辑。

## 3. 前提条件

| 项 | 要求 |
|---|---|
| 服务端 | 已实现 Servux 的 `servux:entity_data`（本仓库的 `YinwuServux` 插件即可；Fabric 服务端装 Servux 也行） |
| 客户端 | MiniHUD（本版 26.3 对应 0.41.x）+ malilib，且 **Entity Data Sync 开关已打开** |
| 版本 | 客户端 MC 26.3（与 `fabric.mod.json` 的 `>=26.3 <26.4` 对应） |

## 4. 构建

需要 **JDK 21+**（本机 JDK 25 可用）与联网（首次会下载 Gradle 9.7.1、Minecraft、映射等）。

1. 把 MiniHUD / malilib 的 jar 放进 `libs/`，命名为：

```
libs/minihud.jar     ← [迷你HUD] minihud-fabric-26.3-0.41.2.jar
libs/malilib.jar     ← malilib-fabric-26.3-0.30.2.jar
```

2. 构建：

```bat
gradlew.bat build
```

产物在 `build/libs/YinwuVillagerPin-1.0.0.jar`（4.8 KB，**用不带 `-sources` 的那个**）。

> 文件名取自项目名。本项目**没有**声明 `mappings` —— 26.1+ 起 Minecraft 已不再混淆、官方也不再发布 mappings，因此可以直接用真实类名（`Brain`、`MemoryModuleType.JOB_SITE`…）编译，mixin 也**不需要 refmap**。

## 5. 安装

把 `YinwuVillagerPin-1.0.0.jar` 丢进客户端的 `mods\` 文件夹即可 —— **只有想用这个效果的玩家需要装**。
（仓库里已放好一份成品：`客户端mod补充\yinwu-villager-pin-1.0.0.jar`，与 `build/libs/` 下的完全一致。）

装好后：看向村民（图书管理员）时，牌子会像单人模式那样吸附到它绑定的讲台上。

### 怎么确认装上了

1. 启动游戏，看 `logs/latest.log` 里有没有这一行：

```
[YinwuVillagerPin] 已加载：MiniHUD 的村民信息牌将像单人模式一样吸附到讲台（job_site 数据来自服务端 Servux 实体 NBT）
```

2. 进服后看向图书管理员：信息牌应当贴在**讲台**上（而不是村民头顶）。若牌子仍跟着村民走，说明该村民的实体数据还没进缓存（走远再回来 / 等服务端发一次），或服务端没实现 Servux。
3. 想临时验证「不装也一样」：把 jar 移出 `mods\` 重启客户端，行为应恢复成跟着村民 —— 服务端无需任何改动。

## 6. 兼容性

- **不装**：一切照旧（牌子跟着村民），没有任何影响 ✓
- **装了，但服务端没实现 Servux**：缓存里没有实体数据 → mixin 直接跳过 → 行为不变 ✓（不会崩）
- **装了且服务端有数据**：吸附到讲台 ✓
- 只写 `JOB_SITE` 一条记忆，**不动** MiniHUD 的其它逻辑，也不改村民实体的其它状态 ✓

## 7. 已知边界

- 只为「村民信息牌的位置」服务；容器预览、交易内容等本来就由 MiniHUD + Servux 负责
- MiniHUD 大版本更新后，若 `renderAtEntity` 方法名/签名变化，本 mixin 需要同步跟进。已经做了两层保护：
  - `fabric.mod.json` 把 MiniHUD 限制在 `>=0.41.0 <0.42.0`、MC 限制在 `>=26.3 <26.4`，版本不对时 Fabric 直接拒绝加载（不会崩）
  - mixin 配置为 `required: false` + `defaultRequire: 0`：即使将来签名变了，也**只在日志里留一条 warning，客户端照常启动**，效果自动缺席
- 吸附的前提是「村民离讲台 1.7 格以内」（MiniHUD 的原有条件），村民走远时牌子仍会跟走 —— 这与单人模式的行为一致
