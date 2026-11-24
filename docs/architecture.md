# Architecture & Litematica Integration

> **Status:** Planned v1 Architecture — describes the target runtime design for Minecraft `26.3` (Fabric + Fabric Language Kotlin).

Archica MCP runs entirely inside the Minecraft client JVM. It bridges an external local AI agent (via MCP over Streamable HTTP) with the Minecraft client world and Litematica's hologram placement engine without modifying world blocks directly.

---

## 1. High-Level Component Diagram

```text
+-------------------------------------------------------------------------+
|  Local AI Host (Antigravity / Claude / Cursor / VS Code)                |
+------------------------------------+------------------------------------+
                                     | Streamable HTTP (/mcp)
                                     | Loopback only (127.0.0.1:25580)
                                     v
+-------------------------------------------------------------------------+
|  Minecraft Client JVM                                                   |
|                                                                         |
|  +-------------------------------------------------------------------+  |
|  |  1. Embedded MCP Transport Layer (Ktor CIO + Kotlin MCP SDK)      |  |
|  |     * Runs on background coroutine dispatcher                     |  |
|  |     * Enforces DNS-rebinding protection (localhost/127.0.0.1)     |  |
|  |     * Routes MCP tool calls -> Application Services               |  |
|  +-----------------+-------------------------------+-----------------+  |
|                    |                               |                    |
|                    v                               v                    |
|  +---------------------------------+   +-----------------------------+  |
|  |  2. Blueprint Session Store     |   |  3. Client Thread Bridge    |  |
|  |     * Thread-safe in-memory map |   |     * Suspends Ktor request |  |
|  |     * Stores draft ops & origin |   |     * Marshals work onto    |  |
|  |     * Pure CPU rasterization    |   |       MinecraftClient       |  |
|  +-----------------+---------------+   |       render/main thread    |  |
|                    |                   +--------------+--------------+  |
|                    v                                  |                 |
|  +---------------------------------+                  |                 |
|  |  4. .litematic NBT Compiler     |                  |                 |
|  |     * Resolves blockstates      |                  |                 |
|  |     * Writes .minecraft/        |                  |                 |
|  |       schematics/archica/*.litematic               |                 |
|  +-----------------+---------------+                  |                 |
|                    |                                  v                 |
|                    |                   +-----------------------------+  |
|                    +-----------------> |  5. World & Litematica      |  |
|                                        |     Adapters (Main Thread)  |  |
|                                        |  * Spatial Context Scanner  |  |
|                                        |  * LitematicaBridge         |  |
|                                        +--------------+--------------+  |
+-------------------------------------------------------|-----------------+
                                                        v
                                         +-----------------------------+
                                         |  Litematica + MaLiLib       |
                                         |  * LitematicaSchematic      |
                                         |  * SchematicPlacementManager|
                                         +-----------------------------+
```

---

## 2. Embedded MCP Server & Security

### Transport
- **Library**: Official `io.modelcontextprotocol:kotlin-sdk` (`0.16.0`) using Ktor CIO (`io.ktor:ktor-server-cio-jvm` `3.5.2`).
- **Endpoint**: `http://127.0.0.1:<port>/mcp` (default `25580`) via `mcpStreamableHttp(path = "/mcp")`.
- **Lifecycle**: Starts asynchronously on `Dispatchers.IO` during `ClientModInitializer.onInitializeClient()` and shuts down gracefully on `ClientLifecycleEvents.CLIENT_STOPPING`. If binding fails (e.g., port already in use by another Minecraft instance), startup fails silently with a `WARN` log so the game continues running normally.
- **Port Configuration**: Default `25580`, overridable via `-Darchica.mcp.port=<port>` (`1..65535`; invalid values log a warning and fall back to `25580`). Planned `/archica mcp start|stop` client commands and MaLiLib GUI settings are deferred to a later slice.

### Loopback & DNS-Rebinding Protection
- The Ktor server binds exclusively to `127.0.0.1` (not `0.0.0.0`), preventing LAN or external network exposure.
- `enableDnsRebindingProtection = true` is kept enabled on the MCP Kotlin SDK Streamable HTTP endpoint so requests with unexpected `Host` / `Origin` headers outside `localhost`, `127.0.0.1`, or `[::1]` are rejected.

---

## 3. Threading & Client-Thread Marshalling

Ktor handles incoming HTTP/MCP requests on background IO/coroutine worker threads, whereas Minecraft's `ClientWorld`, `ClientPlayerEntity`, and Litematica's `DataManager` / `SchematicPlacementManager` are **not thread-safe** and must only be accessed on the Minecraft client thread.

```text
  MCP Tool Call (Ktor Coroutine)          Minecraft Client Main Thread
       |                                               |
       |-- validate arguments / session -------------->|
       |                                               |
       |-- CompletableDeferred + client.execute {      |
       |     if (client.world == null ||               |
       |         client.player == null) {              |
       |       completeExceptionally(NoWorldLoaded)    |
       |     } else {                                  |
       |       read world / mutate Litematica          |
       |     }                                         |
       |   } ----------------------------------------->|
       |                                               |-- runs on next tick/frame
       |<-- deferred.await() (with timeout) -----------|
       |                                               |
       |-- serialize MCP CallToolResult -------------->|
```

### Key Rules
- **Pure-CPU work stays off the main thread**: Managing draft blueprint operations (`blueprint_create`, `blueprint_add_ops`, `blueprint_update_op`, `blueprint_undo`) and rasterizing primitives into an intermediate voxel buffer execute on coroutine dispatchers without blocking frame rendering.
- **Bounded main-thread operations**: Only world reads (`get_build_context`, `scan_volume`), survival inventory checks, and Litematica placement registration are marshalled via `MinecraftClient.getInstance().execute { ... }` wrapped in a `CompletableDeferred` with a safety timeout (e.g., 5 seconds).
- **Missing-World Guard**: If the player is on the Title Screen, disconnecting, or loading into a server (`client.world == null || client.player == null`), main-thread tasks immediately fail cleanly and return a structured MCP tool error (`NO_WORLD_LOADED`) rather than throwing an NPE or hanging.

---

## 4. `.litematic` Compilation & Litematica Activation

Archica MCP uses a **file-backed compilation + automatic Litematica placement** strategy rather than volatile RAM-only schematics.

### Step 1: Compile Voxel Buffer to `.litematic` NBT
1. Flatten all active operations in the blueprint session into a bounded 3D voxel grid (see [Stateful Blueprints & Building Primitives](blueprint-primitives.md)).
2. Compute minimum bounding box `(sizeX, sizeY, sizeZ)` and collect the blockstate palette (`air` at index `0`).
3. Pack blockstate indices into Litematica's bit-packed `LongArray` (`BlockStates`) format along with `Metadata` (`Name`, `Author = "Archica MCP"`, `TimeCreated`, `TimeModified`, `TotalBlocks`, `TotalVolume`, `EnclosingSize`) and target `MinecraftDataVersion`.
4. Write atomically (temp file -> move) to `.minecraft/schematics/archica/<blueprint_id>.litematic`.

### Step 2: Activate & Replace Placement in Litematica
All Litematica interactions sit behind a dedicated `LitematicaBridge` interface so internal Litematica API changes across Minecraft 26.x patches remain isolated from the compiler and MCP layers.

```text
  blueprint_compile(id, activate = true)
       |
       +--> Write .minecraft/schematics/archica/<id>.litematic
       |
       +--> [Client Main Thread via LitematicaBridge]
              1. Look up existing placement tagged with "archica:<id>"
              2. If present: remove old placement via SchematicPlacementManager
                 (prevents overlapping duplicate holograms on refinement)
              3. Load schematic via LitematicaSchematic.createFromFile(...)
              4. Create SchematicPlacement at blueprint world origin (enabled=true, render=true)
              5. Register via DataManager.getSchematicPlacementManager().addSchematicPlacement(...)
```

### Why File-Backed + Auto-Placement?
- **Persistence**: Players retain every generated `.litematic` file in `.minecraft/schematics/archica/` for later survival builds, material lists, or server sharing.
- **Zero-Friction Iteration**: Players never have to open Litematica's file browser or manually align coordinates; edits made by the AI immediately replace the active hologram in place.
- **Clearance**: Calling `blueprint_clear_placement` removes the active `SchematicPlacement` from Litematica while optionally keeping the `.litematic` file on disk.

---

## 5. Related Documents

- [Spatial Context Compression](spatial-context.md)
- [Stateful Blueprints & Building Primitives](blueprint-primitives.md)
- [MCP Tools Reference](mcp-tools.md)
