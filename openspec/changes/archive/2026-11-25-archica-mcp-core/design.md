# Design

## Context

See [proposal.md](proposal.md) for motivation and scope, and the delta/new specs under [specs/](specs/) for the updated behavioral contracts.

Current implementation state verified in the repository:
- [ArchicaMcpClient.kt](../../src/client/kotlin/com/archica/mcp/ArchicaMcpClient.kt) starts [ArchicaEmbeddedMcpServer](../../src/main/kotlin/com/archica/mcp/server/ArchicaMcpServer.kt) once on `onInitializeClient()` and stops it on `CLIENT_STOPPING`, without any runtime restart controller or `/archica` client commands.
- [ClientLitematicaBridge.kt](../../src/client/kotlin/com/archica/mcp/litematica/ClientLitematicaBridge.kt) currently reads `DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()` directly inside synchronous `hasActivePlacement(blueprintId)` on Ktor coroutine threads, and `clearPlacement(deleteFile = true)` does not evict `SchematicHolder` entries before deleting the `.litematic` file.
- [ClientBlockCatalog.kt](../../src/client/kotlin/com/archica/mcp/blueprint/ClientBlockCatalog.kt) already classifies `CrossCollisionBlock` as `BlockKind.CONNECTABLE`, and [BlueprintTransform.kt](../../src/main/kotlin/com/archica/mcp/blueprint/BlueprintTransform.kt) already rotates `facing` and `axis`, but neither computes horizontal connection flags (`north`, `south`, `east`, `west`) nor rotates those 4 cardinal properties on export.
- Fabric API `0.167.0+26.3` (`fabric-command-api-v2` `3.1.3`) exposes `ClientCommandRegistrationCallback`, `ClientCommands`, and `FabricClientCommandSource`.
- MCP Kotlin SDK `0.16.0` `Server` constructor accepts `instructions: String` alongside `Implementation` and `ServerOptions`.

## Goals / Non-Goals

**Goals:**
- Keep all pure-Kotlin logic (`CONNECTABLE` auto-orientation, horizontal property rotation, MCP `instructions`/schemas, suspend `BlueprintService` orchestration) testable via `./gradlew test` without booting Minecraft.
- Eliminate off-thread `DataManager` access and stale `SchematicHolder` entries in `ClientLitematicaBridge`.
- Provide a clean, lightweight `/archica` client command surface and chat feedback without introducing heavy GUI dependencies.
- Validate the real mod jar/client end-to-end against Minecraft `26.3` + Litematica `0.29.1` + MaLiLib `0.30.2`.

**Non-Goals:**
- Adding MaLiLib GUI configuration screens or keybind overlays.
- Persisting blueprint sessions to disk across Minecraft restarts.
- Supporting vertical connections (`up`, `down`) on walls/fences beyond the 4 horizontal directions (`north`, `south`, `east`, `west`) or full Minecraft block-connection overrides (e.g., leaves/glass non-solid exceptions).

## Decisions

### 1. Client-Thread-Safe `LitematicaBridge` Inspection & Eviction
- **Decision:** Change `LitematicaBridge.hasActivePlacement` (or replace/augment with `suspend fun listActivePlacements(): Set<String>`) to be `suspend`, and make `BlueprintService.list()` `suspend`. In `ClientLitematicaBridge`, if `client.level == null || client.player == null`, immediately return `emptySet()` (no `NO_WORLD_LOADED` error for `blueprint_list` or `/archica status` on the title screen); otherwise marshal via `ClientThreadDispatcher.runOnClientThread` to read `DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()` matching prefix `"archica:"`. Also add `evictCachedSchematic(schematicFile: Path)` inside `clearPlacement` when `deleteFile = true`.
- **Rationale:** Reading `DataManager` on the client thread eliminates race conditions with Litematica's render/tick threads, while returning `emptySet()` when no world is loaded preserves `blueprint_list`'s offline behavior. Querying the full active set once per `list()` call also avoids O(N) main-thread dispatches.
- **Alternative considered:** Relying solely on the `activePlacementIds` `ConcurrentHashMap` mirror. Rejected because players can remove or toggle placements manually in Litematica's GUI, making the mirror stale.

### 2. `CONNECTABLE` Auto-Orientation & Facing Rotation
- **Decision:** Add a post-rasterization pass in `BlueprintRasterizer.rasterize` (before returning the `VoxelGrid`) that inspects every voxel whose resolved `BlockKind == BlockKind.CONNECTABLE`. For each of the 4 local horizontal neighbors (`+Z` local north, `+X` local east, `-Z` local south, `-X` local west), set `"north"`, `"east"`, `"south"`, `"west"` to `"true"` if the neighbor is non-air in the grid (and `"false"` otherwise), using `BlockSpec.withDefaultProperties` so any explicit property passed by the caller wins. Extend `BlueprintTransform.transformBlockSpec` to cyclically permute `"north"`, `"east"`, `"south"`, `"west"` keys by `clockwiseTurns(blueprintFacing)` whenever `turns != 0`.
- **Rationale:** Computing connections after all `ops` (including `clear_box` and `replace_box`) finish ensures carved doorways/windows disconnect cleanly from air, while rotating the 4 directional property keys in `BlueprintTransform` guarantees `EAST`/`SOUTH`/`WEST` blueprints keep accurate world connections in `.litematic` files.
- **Alternative considered:** Evaluating neighbor connections during each individual op. Rejected because later `clear_box` or adjacent ops would leave stale connection states.

### 3. Runtime MCP Server Controller & `/archica` Client Commands
- **Decision:** Extract a thread-safe `McpServerController` in `ArchicaMcpClient.kt` (backed by `lifecycleLock`, managing the active `ArchicaEmbeddedMcpServer` and `CoroutineScope`) with `status()`, `start(port: Int?)`, and `stop()`. Register `/archica` in a dedicated `src/client/kotlin/com/archica/mcp/command/ArchicaClientCommands.kt` using Fabric `26.3`'s `ClientCommandRegistrationCallback` and `ClientCommands`:
  - `/archica status`
  - `/archica mcp start` and `/archica mcp start <port>` (`IntegerArgumentType.integer(1, 65535)`)
  - `/archica mcp stop`
  - `/archica clear <blueprint_id>` and `/archica clear all` (marshalled on client thread / coroutine scope cleanly, sending feedback via `source.sendFeedback` / `sendError` or `Minecraft.execute { player?.displayClientMessage(..., false) }`).
- **Rationale:** `ClientCommands` in `fabric-command-api-v2` runs client-side even on vanilla multiplayer servers without needing server-side mod installation or OP permissions.
- **Alternative considered:** Adding a MaLiLib config GUI screen. Deferred as out of scope for v1 core polish.

### 4. MCP Server `instructions` & Primitive Schema Guidance
- **Decision:** Pass a concise `instructions` string to `io.modelcontextprotocol.kotlin.sdk.server.Server` inside `ArchicaMcpServerFactory.create`, summarizing:
  1) Recommended sequence (`get_build_context` -> `blueprint_create` -> `blueprint_add_ops` -> `blueprint_compile`),
  2) Local coordinate convention (`+Y` up, `+Z` forward, `+X` right) and inclusive box bounds,
  3) Availability of `scan_volume` for 3D terrain/structure inspection.
  Enrich `blueprint_add_ops` and `blueprint_update_op` schema `description` fields to list the exact parameters of all 11 primitive `type` variants cleanly without breaking `additionalProperties: true` parsing.
- **Rationale:** Keeps schema validation backward-compatible while significantly reducing LLM trial-and-error on first tool calls.

### 5. Live Runtime Verification Strategy
- **Decision:** After automated `./gradlew test build` produces `build/libs/archica-mcp-0.1.0.jar`, deploy the built mod jar into the user's external Fabric `26.3` launcher instance (alongside Fabric API `0.167.0+26.3`, Fabric Language Kotlin `1.14.2+kotlin.2.4.21`, MaLiLib `0.30.2`, and Litematica `0.29.1`). The user verifies `/archica status` and `/archica mcp` commands in-game while we send real MCP `initialize`, `tools/list`, `get_build_context`, `blueprint_create`, `blueprint_add_ops`, `blueprint_compile`, and `blueprint_clear_placement` requests over Streamable HTTP to `http://127.0.0.1:25580/mcp`, confirming the hologram appears, updates in place, notifies chat, and clears cleanly.

## Risks / Trade-offs

- **[Risk] `blueprint_list` becoming `suspend` affects existing unit tests calling `store.list()` or `service.list()`** -> **Mitigation:** Keep `BlueprintSessionStore.list(activePlacementIds: Set<String>)` synchronous for pure store tests, and make `BlueprintService.list()` `suspend` so only service/MCP integration callers await `litematicaBridge.listActivePlacements()`.
- **[Risk] `CONNECTABLE` auto-connect connecting to non-full blocks like adjacent stairs/doors inside a wall** -> **Mitigation:** Connecting to any non-air neighbor is a clean 90% heuristic for hologram preview, and Minecraft recalculates actual block connections if placed/pasted; callers can always override explicitly via `[north=false]`.
- **[Risk] External launcher environment mismatch (missing Fabric Language Kotlin, MaLiLib, or Litematica versions)** -> **Mitigation:** Document exact required mod versions in `README.md` and run a cooperative live checklist with the user on `http://127.0.0.1:25580/mcp` before archiving.
