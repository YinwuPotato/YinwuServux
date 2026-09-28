# YinwuServux

**在 Canvas（Paper/Folia 系）服务端实现 masa 的 Servux 协议，让装了 MiniHUD 的客户端能用"容器预览"。**

看向箱子 / 木桶 / 潜影盒 / 熔炉等容器时，MiniHUD 会在 HUD 上直接画出里面的物品 —— 原版服务端不提供这个数据，
Fabric 端靠 Servux 这个 mod；Canvas 跑不了 Fabric mod，所以这里用插件把同一套协议在服务端实现。

---

## 1. 协议（从 jar 反编译核对，不是猜的）

对照版本：`servux-fabric-26.3-0.12.2` + `minihud-fabric-26.3-0.41.2`

通道：**`servux:entity_data`**（模组用的是 1.20.5+ 的 custom payload 机制，线格式与 Bukkit 插件消息一致，
所以服务端插件可以直接和 Fabric 客户端对话）

所有包以 **varint 类型号**开头（**注意：类型号不是枚举序号**）：

| 类型号 | 名称 | 负载 | 方向 |
|---|---|---|---|
| 1 | S2C_METADATA | 原版 NBT | 服务端 → 客户端 |
| 2 | C2S_METADATA_REQUEST | 原版 NBT（`{version:2}`） | 客户端 → 服务端 |
| 3 | C2S_BLOCK_ENTITY_REQUEST | BlockPos（打包 long） | 客户端 → 服务端 |
| 5 | S2C_BLOCK_NBT_RESPONSE_SIMPLE | BlockPos + int 长度 + gzip NBT | 服务端 → 客户端 |
| 7 | C2S_UNREGISTER_REPLY | 原版 NBT | 客户端 → 服务端 |
| 10/11 | S2C_NBT_RESPONSE_START / DATA | 分片（本插件暂未实现） | 服务端 → 客户端 |

其中 **gzip NBT 是 masa 自己的格式**（`DataByteBufUtils`）：

```
[int 长度][ gzip( [byte 类型=10][UTF 根名（空串）][NBT 负载] ) ]
```

等价于原版 `NbtIo.writeAnyTag(tag, output)`；本插件就是按这个字节序列手工拼的。

### 握手是硬性要求

客户端的校验很严格（`EntityDataManager.receiveServuxMetadata`）：

```java
int version = nbt.getIntOrDefault("version", -1);
String servux = nbt.getStringOrDefault("servux", "?");
if (version != 2 || !servux.startsWith("servux-fabric-" + MC_VERSION)) {
    // 不匹配 → 注销通道 + 把 MiniHUD 的 ENTITY_DATA_SYNC 关掉
}
```

所以服务端回的元数据**必须**含 `version = 2` 和以 `servux-fabric-26.3` 开头的 `servux` 字符串。
**在此之前发的方块实体响应会被客户端直接丢弃**。

---

## 2. 区域线程（Canvas / Folia）怎么处理

这是本插件最关键的设计点：

```
插件消息回调（在玩家所属区域线程上）
  ├─ 只读玩家自身状态：getWorld() / getEyeLocation()（做 8 格距离校验）
  └─ Bukkit.getRegionScheduler().execute(plugin, world, pos.x>>4, pos.z>>4, ...)
        └─ 在【方块所属区域线程】上：
             ServerLevel.getBlockEntity(pos)
             BlockEntity.saveWithFullMetadata(registryAccess())
             序列化成 masa 的 gzip NBT 格式（纯数据操作）
             └─ player.getScheduler().execute(...)  ← 回到【玩家所属区域线程】
                  player.sendPluginMessage(...)
```

即：**绝不在错误的线程上碰方块实体**，跨区域时不使用 `getBlockEntity`，全部通过 region scheduler 调度；
回包也必须回到玩家自己的调度器再发。`plugin.yml` 已声明 `folia-supported: true`。

---

## 3. 安装

1. 构建：双击 `build-javac.bat`（需要 JDK 25；脚本会自动找 canvas-api、服务端 jar 和 `Van\libraries\` 全部依赖）
2. 把 `yinwu-servux-1.0.0.jar` 放进 `<服务器>\Van\plugins\`
3. 重启 Van（首次会释放 `plugins/YinwuServux/config.yml`）
4. 启动日志应出现：
   `[servux] 已启用：通道=servux:entity_data，权限=…，元数据版本串=servux-fabric-26.3，最大距离=8.0 格，限速=10/秒`

## 4. 配置（`plugins/YinwuServux/config.yml`）

| 键 | 默认 | 说明 |
|---|---|---|
| `permission` | 空 | 留空 = 所有玩家可用（**等于允许翻别人箱子**）；建议填 `yinwu.servux.use` 并用 LuckPerms 授权 |
| `servux-version-string` | `servux-fabric-26.3` | 客户端校验的版本串；服务器 26.3 → 26.3 客户端 |
| `max-distance` | `8.0` | 允许请求的方块与玩家眼睛的最大距离；`0` = 不限。用于挡住改包客户端远程翻箱 |
| `max-requests-per-second` | `10` | 每玩家每秒请求上限，防刷 |
| `debug` | `false` | 打印每次请求/回包 |

命令：`/yinwuservux status`（看配置与已握手玩家数）、`/yinwuservux reload`（热重载配置）

## 5. 客户端需要什么
- **MiniHUD**（Fabric，26.3 对应 0.41.x）
- MiniHUD 的 **Entity Data Sync / Servux 开关**必须打开（配置里的 `ENTITY_DATA_SYNC`），否则客户端根本不会发握手
- MiniHUD 的"容器预览/物品栏预览"HUD 元素要启用
- **村民信息 / 交易显示**：打开 MiniHUD 的 **Villager Info** 覆盖层。它内置了图书管理员专用的交易搜索逻辑
  （`OverlayRendererVillagerInfo`）：扫描范围内所有图书管理员，筛出"绿宝石 → 附魔书"的交易并列出附魔与价格
  （找经验修补就靠这个）。数据来自实体 NBT 里的 `Offers`，走本插件的实体分支（类型 4/6）
- 客户端与服务端版本要对应（服务器 26.3 → 客户端 26.3）。客户端版本不同的话，元数据里的 `servux-fabric-<版本>` 对不上，
  客户端会主动关掉该功能（这是 masa 的设计，不是插件 bug）

## 6. 已知限制

- **实体分支已实现**（类型 4/6）：村民信息与交易显示、箱子船 / 驴 / 展示框等实体容器都能预览
- 请求其他玩家实体时，默认清空其 `Inventory` / `EnderItems`（`allow-other-player-inventory: false`，与 Servux 默认一致）
- **不支持分片**：单个容器 NBT 压缩后超过约 32 KB 时（例如塞满成书的箱子）不回包，服务端日志会给出提示。
  Servux 在这种情况用 10/11 分片，需要的话可以补
- 不做"服务端给玩家发物品栏"那类额外权限控制（对应 Servux 的 `nbtAllowPlayerInventory` 等设置）

## 7. 排查

| 现象 | 检查点 |
|---|---|
| 客户端完全没反应 | MiniHUD 的 Entity Data Sync 是否打开；`debug: true` 后看服务端有没有收到握手（类型 2） |
| 握手到了但预览不显示 | 元数据里的 `servux` 版本串是否与客户端 MC 版本一致；日志里有没有"协议版本不足"警告 |
| 只有远处的箱子能预览 | `max-distance` 调大 |
| 预览一段时间后失效 | 客户端可能因超限注销；看 `debug` 日志与 `max-requests-per-second` |

## 8. 为什么"村民交易牌"不会固定在讲台上（调查结论，2026-09-28）

**结论：这是 MiniHUD 的客户端行为，且"吸附到讲台"只在单机 / 局域网生效；任何多人服务器（Fabric 服、Canvas 服）都不会吸附，表现完全一致。服务端无法干预。**

代码依据（MiniHUD 0.41.2 / malilib 0.30.2，26.3）：

```java
// MiniHUD: OverlayRendererVillagerInfo.renderAtEntity(...)
// 只有【本地实体对象】的 Brain 里有 JOB_SITE、且村民离讲台 < 1.7 格时，才把 x/z 改成讲台坐标
if (living.getBrain().getMemoryInternal(MemoryModuleType.JOB_SITE) != null && …距离 < 1.7) { …讲台坐标… }
else { // 画在村民头顶：村民坐标 + 朝镜头 0.8 格，y + 1.5 }

// MiniHUD: util/EntityUtils.getEntitiesByClass(...) —— 实体是从这里取的
List<Integer> ids = mc.level.getEntitiesOfClass(...)…;          // 先在客户端世界找
Level world = WorldUtils.getBestWorld(mc);                       // ← 关键
return ids.stream().map(it -> (T) world.getEntity(it))…;

// malilib: WorldUtils.getBestWorld(mc)
if (mc.level != null && server != null) return server.getLevel(...);  // 单机/局域网 → 整合服务端世界（实体带真脑 ✓）
else                                    return mc.level;              // 多人 → 客户端世界（脑永远为空 ✗）
```

另外两条佐证：
- 原版**不同步 Brain 记忆**给客户端；`LivingEntity.tick()` 里 AI（含大脑传感器）被 `isClientSide` 挡住，只在服务端跑
- MiniHUD / malilib / Servux 三个仓库里**都没有**把服务端 NBT 写回客户端实体的代码（malilib 中 `readAdditionalSaveData` 仅两处，都是给 `FoodData` 用的）

所以：**村民站在讲台上时看起来"贴着讲台"，是"牌子在村民头顶"的视觉效果**；村民被困在讲台后方（本服的实际布局）时，牌子就停在讲台后方 —— 这是标准表现，不是本插件的问题。

> 若确实需要"多人下也钉在讲台"，只能改客户端（例如 mixin `OverlayRendererVillagerInfo.renderAtEntity`，改为读取 MiniHUD 已缓存的实体 NBT 里的 `Brain.memories."minecraft:job_site"` —— 而这份数据本插件已经在发，日志可见 `含Brain=true`）。
