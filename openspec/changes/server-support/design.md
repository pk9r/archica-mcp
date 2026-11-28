# Design

## Context

Archica MCP is currently client-only (`fabric.mod.json` has `"environment": "client"`), with pure-Kotlin blueprint, compiler, and MCP server code in `src/main` and Minecraft/Litematica adapters in `src/client`. `draft-persistence` adds portable `.draft.json` persistence in `src/main`. See [proposal.md](proposal.md) for motivation.

In the target multiplayer deployment (for example, a Docker Fabric server accessed over Tailscale), the server should expose only the standard Minecraft port (`25565`) while AI agents continue connecting locally to each player's `http://127.0.0.1:25580/mcp` client endpoint. When the server also runs `archica-mcp`, the client acts as a transparent gateway over Fabric Networking so blueprints are persisted on the server and Litematica holograms automatically synchronize across all connected modded players—without ever editing `ServerLevel` world blocks.

## Goals / Non-Goals

**Goals:**
- Package a single universal Fabric jar (`"environment": "*"`) supporting both dedicated servers and clients.
- Automatically detect server `archica-mcp` capability on join and route `blueprint_*` operations over Fabric Networking C2S/S2C payloads while keeping solo/unmodded-server operation 100% local.
- Distribute compiled `.litematic` schematics from the server to all connected modded clients for immediate `archica:shared:<blueprint_id>` Litematica activation, re-sync on join, and clean unload on disconnect.
- Support read-only `ServerLevel` `scan_volume` fallback when target chunks are outside client render distance.

**Non-Goals:**
- Direct block placement, destruction, or world modification on `ServerLevel`.
- Running an external/public HTTP MCP server on the dedicated server.
- Synchronizing player inventory across clients (per-player privacy opt-in remains local to the player's client unless explicitly enabled on their own client).

## Decisions

### 1. Universal packaging and entrypoint split
Update `src/main/resources/fabric.mod.json` to `"environment": "*"` with three entrypoints:
- `"main"` (`ArchicaMcpCommon : ModInitializer`): registers shared Fabric `PayloadTypeRegistry` C2S/S2C packet codecs once for both logical sides.
- `"client"` (`ArchicaMcpClient : ClientModInitializer`): keeps the local HTTP MCP server on `127.0.0.1:25580`, local persistence/Litematica bridge, client commands/UI, and client-side networking handlers.
- `"server"` (`ArchicaMcpServerMod : DedicatedServerModInitializer`): runs only on dedicated servers (never inside a client's singleplayer integrated server), hosting the server `BlueprintService` + `BlueprintDraftStore` in `<serverRoot>/schematics/archica/`, `ServerWorldContextSource` + `ServerThreadDispatcher`, server `/archica` commands, and join/broadcast sync.
Move `ClientBlockCatalog` to `src/main` as `MinecraftBlockCatalog` since `BuiltInRegistries.BLOCK` and blockstate classes are common Minecraft APIs.

### 2. Transparent Client-Gateway blueprint routing
Introduce a `BlueprintGateway` (or `ArchicaBlueprintPort` implemented by local `BlueprintService` vs remote `ClientGatewayBlueprintPort`) behind `ArchicaMcpServerFactory`:
- When `Minecraft.getInstance().connection != null` and `ClientPlayNetworking.canSend(ARCHICA_RPC_REQUEST_ID)` is true, `blueprint_create`, `blueprint_add_ops`, `blueprint_update_op`, `blueprint_remove_op`, `blueprint_undo`, `blueprint_inspect`, `blueprint_list`, `blueprint_compile`, and `blueprint_clear_placement` serialize into C2S RPC payloads keyed by a monotonic `requestId` (`CompletableDeferred<RpcResponse>` with bounded timeout).
- When `blueprint_*` mutates or inspects on the server, survival inventory checks still optionally incorporate the requesting player's local `SurvivalInventorySnapshot` (if the player enabled `/archica inventory on` on their client) by either sending the optional snapshot with the request or computing `survivalCheck` on the client side from returned `materials`. Computing `survivalCheck` client-side from the returned `materials` avoids sending inventory data over the network!
- On `ClientPlayConnectionEvents.DISCONNECT`, all pending `CompletableDeferred` RPC calls fail cleanly with an explicit disconnect/error envelope so Ktor threads never hang.

### 3. Chunked `.litematic` S2C broadcast and namespace isolation
Compiled `.litematic` files for `<= 131,072` blocks are GZIP-compressed and typically range from a few KB to ~100 KB, but dense/high-entropy schematics could approach Fabric/Minecraft's ~1 MB `CustomPayload` ceiling.
- Split schematic byte transfers above a safe threshold (for example, `256 KiB` chunks with `transferId`, `chunkIndex`, `totalChunks`, and `sha256`/length check) when sending S2C `SyncSharedPlacement` payloads.
- Server tracks active shared placements (`Map<String, ActiveSharedPlacement>` restored from `.draft.json` metadata + verified `.litematic` existence on disk at startup).
- On `ServerPlayConnectionEvents.JOIN`, send active shared placements to the joining player if `ServerPlayNetworking.canSend(...)` is true.
- On the client, `ClientLitematicaBridge` stores received bytes in `<schematicsBaseDir>/archica/shared/<blueprint_id>.litematic` and registers placements under `"archica:shared:<blueprint_id>"`. On disconnect, the client removes all `"archica:shared:*"` placements from Litematica's `SchematicPlacementManager`.

### 4. Optional Litematica guard on client
Wrap Litematica class access in `ClientLitematicaBridge` behind `FabricLoader.getInstance().isModLoaded("litematica")` via a lazy provider (`ClientLitematicaAdapter` vs `NoOpLitematicaBridge`) so `ClassNotFoundException` / `NoClassDefFoundError` never fires when Litematica is absent on a client.

### 5. Read-only `ServerLevel` `scan_volume` fallback
On the client, before running `VolumeScanner`, verify whether all chunk `(cx, cz)` pairs intersecting `[min, max]` are loaded in `ClientLevel` (`level.chunkSource.hasChunk(cx, cz)`). If any chunk is unloaded and the server supports `archica-mcp`, forward `ScanVolumeRequest` (scoped to the player's current dimension) via C2S RPC to `ServerThreadDispatcher`, which executes `VolumeScanner(ServerWorldContextSource(serverLevel))` on the server tick thread without modifying blocks.

## Risks / Trade-offs

- **Concurrent edits by multiple players on the same `blueprint_id`** -> Server `BlueprintSessionStore` already synchronizes mutations per session; sequential execution on the server keeps AST and `.draft.json` consistent, and every `blueprint_compile(activate = true)` broadcasts the latest snapshot to all modded clients.
- **Large schematic S2C transfer lag on join** -> Chunked payload assembly offloads disk writes to `Dispatchers.IO` and only activates Litematica on the client main thread once all chunks arrive.
- **Dedicated server permission boundary** -> `/archica status` and `/archica clear` on dedicated server use OP level `2` (`source.hasPermission(2)`), while client-initiated MCP blueprint calls remain available to all connected players in the trusted server group.
