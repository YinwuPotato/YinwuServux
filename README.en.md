# YinwuServux

**A Paper/Folia (Bukkit) server-side implementation of [Servux](https://github.com/sakura-ryoko/servux)'s
`servux:entity_data` protocol, so unmodified [MiniHUD](https://github.com/sakura-ryoko/minihud) can show
container previews and villager info / librarian trades on non-Fabric servers.**

Servux is a Fabric mod, so Paper/Folia servers cannot install it. This plugin implements the same wire
protocol, verified byte-compatible with `servux-fabric-26.3-0.12.2` against `minihud-fabric-26.3-0.41.2`.

> Not affiliated with masa or Sakura-Ryoko. Independent implementation of the protocol; see
> [Attribution](#attribution).

## Features

| Feature | Where it comes from |
|---|---|
| Container preview (chest / barrel / shulker / furnace …) | block-entity branch (packet types 3 → 5) |
| Villager info & librarian trade display, chest boats, donkeys, item frames | entity branch (packet types 4 → 6) |

The client needs MiniHUD with **Entity Data Sync** enabled; vanilla servers give MiniHUD no data at all.

## Protocol

Channel: `servux:entity_data`. Every packet starts with a **varint type id** — note the ids are *not*
the enum ordinals:

| id | packet | payload | direction |
|---|---|---|---|
| 1 | `S2C_METADATA` | vanilla NBT | server → client |
| 2 | `C2S_METADATA_REQUEST` | vanilla NBT (`{version:2}`) | client → server |
| 3 | `C2S_BLOCK_ENTITY_REQUEST` | `BlockPos` (packed long) | client → server |
| 4 | `C2S_ENTITY_REQUEST` | `varint` entity id | client → server |
| 5 | `S2C_BLOCK_NBT_RESPONSE_SIMPLE` | `BlockPos` + int length + gzip NBT | server → client |
| 6 | `S2C_ENTITY_NBT_RESPONSE_SIMPLE` | `varint` entity id + int length + gzip NBT | server → client |
| 7 | `C2S_UNREGISTER_REPLY` | vanilla NBT | client → server |
| 10/11 | `S2C_NBT_RESPONSE_START` / `_DATA` | split payloads — **not implemented here** | server → client |

Two details that are easy to get wrong:

* **Handshake is mandatory.** The client validates the metadata it gets back:
  `version == 2` **and** the `servux` string must start with `servux-fabric-<client MC version>`
  (e.g. `servux-fabric-26.3`). Otherwise MiniHUD unregisters the channel and switches
  `ENTITY_DATA_SYNC` off. Packets sent before that are dropped by the client.
* **The NBT is gzip-compressed** using masa's `DataByteBufUtils` layout:
  `[int length][ gzip( [byte 10][UTF root name (empty)][NBT payload] ) ]` — equivalent to
  `NbtIo.writeAnyTag`. Vanilla `FriendlyByteBuf#writeNbt` is **not** compressed and will not parse.

What the server sends, mirroring Servux's own calls:

* block entities — `BlockEntity#saveWithFullMetadata(registryAccess)`
* entities — `TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registryAccess)` +
  `Entity#saveWithoutId(...)` + an `id` field (this is the same chain Servux uses, so the payload
  is identical, `TAG_BRAIN` included)
* other players' entities — `Inventory` / `EnderItems` are stripped unless
  `allow-other-player-inventory: true`

## Folia / Canvas region threading

This is the part that needs care on regionised servers:

```
plugin message callback            (runs on the player's region thread)
  ├─ reads only player state (world, eye position, distance check)
  └─ Bukkit.getRegionScheduler().execute(plugin, world, pos.x >> 4, pos.z >> 4, ...)
        └─ on the region owning the block entity:
             ServerLevel#getBlockEntity → saveWithFullMetadata → serialise (pure data)
             └─ player.getScheduler().execute(...)      ← back on the player's region thread
                  Player#sendPluginMessage(...)
```

Entity lookups run on the player's region (the client only asks for entities it is looking at) with a
configurable maximum distance. `plugin.yml` declares `folia-supported: true`.

## Build

Requires **JDK 25** (the Canvas 26.3 API jar contains class-file 69 classes, which older `javac`
cannot even read) and a checkout of the server's `libraries/` folder:

```bat
:: edit SERVER_ROOT in build-javac.bat first (points at your server root)
build-javac.bat
```

or manually:

```bat
javac -encoding UTF-8 --release 21 -proc:none -cp "<canvas-api.jar>;<canvas-server.jar>;<all jars under libraries/>" ^
      -d out src\main\java\io\yinwu\servux\ServuxBridgePlugin.java
jar --create --file yinwu-servux-1.0.0.jar -C out . -C src\main\resources .
```

`build-javac.bat` builds against the Canvas API/NMS (Mojang mappings). Since it only uses the Bukkit
API plus NMS serialisation helpers, a Paper 1.21+ build should work with the equivalent jars.

## Install

1. Drop `yinwu-servux-1.0.0.jar` into `plugins/` and restart the server.
2. `plugins/YinwuServux/config.yml` is created on first start:

| key | default | meaning |
|---|---|---|
| `permission` | `""` | empty = every player may read nearby containers/entities; set e.g. `yinwu.servux.use` to restrict |
| `servux-version-string` | `servux-fabric-26.3` | must match the client's MC version |
| `max-distance` | `8.0` | max distance between the player's eyes and the requested block/entity (`0` = unlimited) |
| `max-requests-per-second` | `40` | per-player rate limit; MiniHUD's librarian scan issues one request per villager |
| `allow-other-player-inventory` | `false` | include other players' `Inventory`/`EnderItems` |
| `debug` | `false` | log every request/response |

Commands: `/yinwuservux status`, `/yinwuservux reload`.

## Known limitations

* no split payloads (10/11): a container whose gzipped NBT exceeds the plugin-message limit is skipped
  with a log line
* only the `entity_data` channel; `hud_data`, `structure_bounding_boxes`, `litematic_data` and
  `tweaks_data` are not implemented
* the villager info **plate position** is a MiniHUD client decision — see
  `上游issue草稿-MiniHUD村民牌吸附.md` for the full analysis (the "attach to job site" branch reads the
  *client-side* entity's brain, which vanilla never syncs on multiplayer servers)

## Attribution

* Servux by masa (maruohon), maintained for recent versions by Sakura-Ryoko — LGPL-3.0
* MiniHUD / malilib by masa — LGPL-3.0

This project only re-implements the wire protocol of the above; no code was copied. Licensed under
LGPL-3.0 to stay compatible.
