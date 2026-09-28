# 上游 issue 草稿：MiniHUD 的村民信息牌在多人服下不会吸附到职业方块

提交位置（建议）：
- 主：https://github.com/sakura-ryoko/minihud/issues （26.3 的维护在他这边）
- 备：https://github.com/maruohon/minihud/issues （masa 上游）

以下英文正文可直接整段复制。

---

**Title:** Villager info text plate never attaches to the job site on multiplayer servers (JOB_SITE brain memory is server-side only)

**Body:**

```
Environment
- Client: MiniHUD 0.41.2 (26.3) + malilib 0.30.2, Fabric
- Server: Paper/Folia-based server (Minecraft 26.3)
  The server is Bukkit/Paper-based, so the Servux mod itself cannot be installed there.
  Instead a server-side plugin implements the same servux:entity_data protocol
  (metadata handshake + entity requests), byte-compatible with Servux.

What happens
- Singleplayer: the villager info text plate snaps to the villager's job site (lectern)
  when the villager is within 1.7 blocks — works as intended.
- Multiplayer: the plate always follows the villager and never attaches to the lectern,
  even when the villager stands right next to / on top of it.

Why (from the sources)
- OverlayRendererVillagerInfo.renderAtEntity() reads:
      living.getBrain().getMemoryInternal(MemoryModuleType.JOB_SITE)
- `living` comes from EntityUtils.getEntitiesByClass():
      ids are collected from mc.level, then resolved via WorldUtils.getBestWorld(mc)
- WorldUtils.getBestWorld() returns the integrated server's level when one exists,
  otherwise mc.level:
      singleplayer -> server-side entity  -> real brain  -> JOB_SITE present -> attaches
      multiplayer  -> client-side entity  -> vanilla never syncs brain memories
                                            -> JOB_SITE always empty -> branch is dead

Suggestion
- The entity data MiniHUD already requests from Servux (servux:entity_data, types 4/6)
  contains "Brain": the server side serialises entities with Entity#saveWithoutId(...),
  which writes TAG_BRAIN including the packed `minecraft:job_site` memory.
- So the plate position could be derived from the cached NBT
  (e.g. Brain.memories."minecraft:job_site".value) instead of the local entity's brain,
  which would make the feature work on multiplayer servers too.
- Alternatively, applying the received Brain tag to the client-side entity would make the
  existing check work as-is — note there is already an IMixinEntity exposing
  readAdditionalSaveData that currently has no callers.
```

---

## 备注（不用贴进 issue，自己看）

- 我实测过：**同一客户端**连 Fabric 多人服和 Paper/Folia 服，**行为完全一致**（都跟着村民）—— 这正好印证上面的代码分析
- 服务端插件是我们自己写的（`YinwuServux`），日志已确认发出去的实体 NBT **含 `Brain`**（`含Brain=true`），所以客户端数据是齐的，缺的只是"谁来用这份数据定位"
- 若维护者希望走第三条路（把 Brain 灌回客户端实体），MiniHUD 里那个 `IMixinEntity`（暴露 `readAdditionalSaveData`）现成可用，但需要 callers

---

# 附：Paper/Folia 服务端实现（作为 issue 的补充说明提交，或单独开 PR / Discussion）

以下英文块可直接复制，作为上面 issue 的**跟帖补充**，或贴到 **sakura-ryoko/servux** 的 PR / Discussion 里。

**Title（若单独开）：** Reference Paper/Folia server-side implementation of `servux:entity_data` (Bukkit plugin, region-thread aware)

**Body:**

```
Since Servux is a Fabric mod, servers running Paper/Folia cannot install it. We implemented the
servux:entity_data protocol as a standalone Bukkit/Paper/Folia plugin, verified byte-compatible
with Servux 0.12.2 (26.3), and it works with unmodified MiniHUD 0.41.2 on 26.3.

It covers both branches of the protocol, so Paper/Folia servers get the same two features:
- container preview for block containers (chests, barrels, shulkers, furnaces, hoppers, …)
- entity previews: villager info & librarian trade display, chest boats, donkeys, item frames

What it implements
- Channel: servux:entity_data, packets as varint type ids (1 metadata, 2 metadata request,
  3 block entity request, 4 entity request, 5/6 simple NBT responses, 7 unregister)
- Feature coverage: container preview via the block-entity branch (3 → 5), entity previews
  (villager info/trades, chest boats, donkeys, item frames) via the entity branch (4 → 6)
- Handshake: replies to the metadata request with {version: 2, servux: "servux-fabric-<mcver>"},
  which the client validates before it accepts any data
- Block entities: BlockEntity#saveWithFullMetadata(registryAccess)
- Entities: TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registryAccess) +
  Entity#saveWithoutId(...) + the "id" field, i.e. the same call chain Servux uses, so the
  payload contents are identical (including TAG_BRAIN with the packed memories)
- NBT wire format matches DataByteBufUtils: [int length][gzip([byte 10][UTF ""][nbt payload])]
  (equivalent to NbtIO.writeAnyTag; note FriendlyByteBuf#writeNbt is NOT compressed and will not parse)
- Player entities: Inventory / EnderItems stripped unless explicitly allowed (Servux default)

Folia / region-thread safety (the part that took the most care)
- The plugin message callback runs on the player's region thread: it only reads player state
- Block entity access is hopped onto the owning region via Bukkit#getRegionScheduler().execute(
  plugin, world, chunkX, chunkZ, ...) before touching ServerLevel#getBlockEntity
- Entity lookups run on the player's region (the client only ever asks for entities it is
  looking at, so they are within the view distance), with a configurable max distance
- The reply is sent back through the player's EntityScheduler, never from the reading thread
- plugin.yml declares folia-supported: true

Current limitations (happy to port more if useful)
- No support for the split packets (10/11) yet: a container whose gzipped NBT exceeds the plugin
  message limit is skipped with a log line
- Only the entity_data channel: hud_data / structure_bounding_boxes / litematic_data / tweaks_data
  are not implemented
- Permission node + per-player rate limit are configurable (the latter matters for MiniHUD's
  librarian trade scan, which issues one entity request per villager)

Availability
- Single self-contained Java source file against the Canvas/Paper API + NMS (Mojang mappings);
  no third-party dependencies. Builds with JDK 25, --release 21.
- Can be published as a separate repo/artifact under LGPLv3 (matching Servux) if you want to link
  it from the README as a third-party server-side implementation, or reworked into a submodule
  if you'd rather host it here.
```

