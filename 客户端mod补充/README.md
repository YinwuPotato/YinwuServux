# 客户端 mod 补充（可选）

这里的**不是服务端插件**，而是给**玩家自己**装的小 mod。服务端（本仓库的 `YinwuServux`）保持原样，什么都不用改。

## 这个文件夹里有什么

| 路径 | 说明 |
|---|---|
| `yinwu-villager-pin-1.0.0.jar` | **成品，直接丢进客户端 `mods\`**（4.8 KB） |
| `YinwuVillagerPin\` | 源码工程（Gradle，Loom 1.18，MC 26.3），`gradlew.bat build` 可重新构建 |
| `YinwuVillagerPin\README.md` | 原理、构建、安装、兼容性、已知边界（详细版） |
| `..\客户端mod\` | MiniHUD / malilib / fabric-api 上游 jar，仅供本地构建参考（已 git 忽略，不随仓库分发） |

## 它解决什么

MiniHUD 的村民信息牌（含**图书管理员附魔书交易**）在**单人模式**下会吸附到职业方块（讲台）上；在**任何多人服务器**（Fabric 服、Paper、Canvas 都一样）都不会 —— 因为原版从不同步村民的 `Brain` 记忆给客户端，MiniHUD 读到的 `JOB_SITE` 永远是空的。

本 mod 从 MiniHUD 已经缓存好的实体 NBT（**数据正是 `YinwuServux` 插件在发的**，日志可见 `含Brain=true`）里取出 `job_site`，在渲染前写进客户端村民的脑。MiniHUD 原有的吸附判定随即自行通过 —— **不改 MiniHUD 一行逻辑**。

## 装 / 卸

- **装**：把 `yinwu-villager-pin-1.0.0.jar` 放进客户端 `mods\`，重启客户端
- **卸**：把 jar 移出 `mods\`，重启客户端 —— 行为立刻恢复原样
- 没装的玩家：完全不受影响（这个 mod 只在装了 MiniHUD + malilib 的客户端里才会加载）

前提：客户端装了 **MiniHUD 0.41.x（26.3）** + **malilib 0.30.x**，且 MiniHUD 的 **Entity Data Sync 已开启**；服务端有 Servux 数据（本仓库插件或 Fabric 端 Servux）。

## 怎么确认生效

1. `logs/latest.log` 出现：
   `[YinwuVillagerPin] 已加载：MiniHUD 的村民信息牌将像单人模式一样吸附到讲台（job_site 数据来自服务端 Servux 实体 NBT）`
2. 看向图书管理员：信息牌贴在**讲台**上（不再是村民头顶/身后）
3. 若未生效：该村民数据还没进缓存（走远再回来 / 等服务端发一次），或服务端没有 Servux

## 零安装的替代做法（不改客户端也能"看起来钉住"）

MiniHUD 的判定是「村民离讲台 ≤ 1.7 格」才吸附。所以：

- 从**正面跨过讲台**看村民（相机相对村民有 0.8 格偏移）时，牌子视觉上就落在讲台位置
- 或让村民**站在讲台/台阶上**，牌子本来就在讲台上方

本服现在的布局是「村民困在讲台**后面**、活板门压在讲台上」，所以牌子停在讲台后方 —— 这是 MiniHUD 的标准表现，不是插件的问题。

---

上游相关：`..\上游issue草稿-MiniHUD村民牌吸附.md`（可作为给 MiniHUD 的 issue 草稿）。
