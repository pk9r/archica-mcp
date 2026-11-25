# Proposal

## Why

All four foundational v1 capabilities (`world-context-compression`, `mcp-server`, `blueprint-primitives`, and `litematica-compiler`) are implemented and unit-tested against fakes, yet `archica-mcp-core` still lacks in-game player controls, runtime thread-safety hardening in the Litematica bridge, LLM-ergonomic tool metadata, live Minecraft `26.3` + Litematica `0.29.1` verification, and an up-to-date `README.md`. Completing these finishing touches turns the implemented slices into a polished, player-ready v1 core release.

## What Changes

- **Add in-game `/archica` client UX (`client-ux`):**
  - Register client commands `/archica status` (server state, endpoint URL, draft count, active placements), `/archica mcp start [port]` / `/archica mcp stop` (runtime server control and port override), and `/archica clear <blueprint_id|all>` (quick in-game hologram clearance).
  - Emit concise client chat notifications when MCP calls activate, replace, or clear Litematica placements.
- **Harden Litematica bridge & connectable block rasterization:**
  - Marshal `ClientLitematicaBridge` active-placement queries onto the Minecraft client thread instead of reading `DataManager` from Ktor coroutine threads, and evict matching `SchematicHolder` entries when `blueprint_clear_placement` deletes a `.litematic` file.
  - Auto-orient horizontal `north`/`south`/`east`/`west` connection states for `CONNECTABLE` blocks (`*_pane`, `iron_bars`, fences) during rasterization when explicit properties are omitted, so Litematica hologram previews render connected rather than isolated pillars.
- **Improve MCP LLM ergonomics & runtime lifecycle:**
  - Provide concise server `instructions` in the MCP initialization handshake summarizing the 4-step workflow and anchor-relative coordinate convention (`+Y` up, `+Z` forward, `+X` right).
  - Enrich JSON input schemas for `blueprint_add_ops` and `blueprint_update_op` with explicit per-primitive parameter descriptions/examples.
  - Expose runtime `start`/`stop`/port status hooks from the embedded server lifecycle so `/archica mcp` commands operate safely without restarting Minecraft.
- **Live Minecraft verification & documentation sync:**
  - Verify the built mod end-to-end inside Minecraft `26.3` with Litematica `0.29.1` and MaLiLib `0.30.2` using a real Streamable HTTP MCP client, and update `README.md` to reflect the implemented v1 feature set and usage.

## Capabilities

### New Capabilities

- `client-ux`: In-game `/archica` client commands (`status`, `mcp start|stop`, `clear`) and player chat feedback for hologram placement lifecycle events.

### Modified Capabilities

- `blueprint-primitives`: Extend blockstate auto-orientation to compute horizontal neighbor connections (`north`, `south`, `east`, `west`) for `CONNECTABLE` blocks when explicit directional connection properties are omitted.
- `litematica-compiler`: Require client-thread-safe active placement inspection and `SchematicHolder` cache eviction when deleting compiled schematic files during placement clearance.
- `mcp-server`: Expose MCP server `instructions`, enriched primitive parameter schemas on `blueprint_add_ops`/`blueprint_update_op`, and runtime start/stop lifecycle controls.

## Impact

- **Pure Kotlin core (`src/main/kotlin/com/archica/mcp/`):**
  - `blueprint/BlueprintRasterizer.kt`, `blueprint/BlueprintTransform.kt`, `blueprint/BlueprintSessionStore.kt`: `CONNECTABLE` neighbor pass, `suspend` active-placement lookup in `BlueprintService.list()`/`LitematicaBridge`, and cache-eviction coordination on delete.
  - `litematica/LitematicaBridge.kt`: Suspend `hasActivePlacement` / `listActivePlacements` and optional schematic eviction hook.
  - `server/ArchicaMcpServer.kt`: Server `instructions`, enriched `blueprint_add_ops`/`blueprint_update_op` schemas, and runtime lifecycle/status controller.
- **Fabric client (`src/client/kotlin/com/archica/mcp/`):**
  - `ArchicaMcpClient.kt` + new `command/ArchicaClientCommands.kt`: `ClientCommandRegistrationCallback` (`/archica ...`), runtime MCP server controller, and client chat feedback hooks.
  - `litematica/ClientLitematicaBridge.kt`: Main-thread `ClientThreadDispatcher` marshalling for active placement checks and `SchematicHolder` eviction on file deletion.
- **Tests & Docs (`src/test/kotlin/`, `README.md`, `docs/`):**
  - Unit/integration tests for `CONNECTABLE` auto-connection, `SchematicHolder` eviction / suspend `hasActivePlacement`, MCP `instructions` & schema inspection, and server restart lifecycle.
  - Sync `README.md` (and `docs/architecture.md` / `docs/mcp-tools.md` where commands or instructions change) plus live `./gradlew runClient` smoke test checklist.
