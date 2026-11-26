# Design

## Context

See `proposal.md` for motivation. Currently:
- `BuildContextScanner.scan(request)` unconditionally queries `source.getInventoryMaterials()` and `BuildContextResult.formatText()` always prints `[INVENTORY_MATERIALS]` (or `none`).
- `ArchicaMcpClient` wires `BlueprintService` with `SurvivalSnapshotProvider { dispatcher.readSurvivalSnapshotOrNull() }`, which dispatches to the Minecraft client main thread on every `blueprint_add_ops`, `blueprint_update_op`, `blueprint_remove_op`, `blueprint_undo`, `blueprint_inspect`, and `blueprint_compile` call solely to check `gameMode == "survival"` and compare against `player.inventory.nonEquipmentItems`.
- `ArchicaClientCommands` exposes `/archica status`, `/archica mcp start|stop`, and `/archica clear <target|all>` without any persistent config file on disk (runtime state resets on restart).

## Goals / Non-Goals

**Goals:**
- Keep player inventory reading and `survival_check` **off by default** on every Minecraft client launch (resetting to off in memory across restarts).
- Allow the player—and only the player—to actively opt in/out at runtime via `/archica inventory on|off`.
- Avoid touching the Minecraft client main thread for `survival_check` while inventory tracking is disabled.
- Omit `[INVENTORY_MATERIALS]` cleanly when disabled so the LLM does not misinterpret `none` as an empty survival inventory.

**Non-Goals:**
- Persisting the inventory toggle to a config file on disk (explicitly reset per game launch).
- Exposing an MCP tool parameter allowing the AI agent to override the player's inventory toggle.
- Adding a MaLiLib GUI screen (deferred beyond v1 alongside other GUI settings).

## Decisions

### 1. In-memory thread-safe toggle owned by the client layer
- **Decision**: Represent the runtime toggle as an `AtomicBoolean(false)` (or small wrapper class `InventoryTrackingState` backed by `AtomicBoolean(false)`) instantiated in `ArchicaMcpClient` and shared with `spatialService`, `blueprintService`'s `SurvivalSnapshotProvider`, and `ArchicaClientCommands`.
- **Rationale**: Matches the existing runtime-only pattern used for `/archica mcp start|stop`, guarantees thread-safe visibility between the client command thread and Ktor MCP coroutine threads, and automatically resets to `false` on every game launch.
- **Alternative considered**: Persisting to `.minecraft/config/archica-mcp.json` or adding `-Darchica.inventory.enabled`. Rejected because the user explicitly wants it reset to off every time the game starts, and no config file infrastructure exists yet in the mod.

### 2. Gate inventory in both `BuildContextRequest` and `SurvivalSnapshotProvider`
- **Decision**:
  - Add `val includeInventory: Boolean = false` to `BuildContextRequest` (and `val inventoryIncluded: Boolean = false` on `BuildContextResult`).
  - In `BuildContextScanner.scan(request)`: only call `source.getInventoryMaterials()` when `request.includeInventory` is `true`. In `BuildContextResult.formatText()`, emit `[INVENTORY_MATERIALS]` only when `inventoryIncluded` is `true`.
  - In `ArchicaMcpClient`: `spatialService.getBuildContext` constructs `request.copy(includeInventory = inventoryEnabled.get())`, and `SurvivalSnapshotProvider` returns `null` immediately without calling `dispatcher.readSurvivalSnapshotOrNull()` when `!inventoryEnabled.get()`.
  - Keep `SpatialToolArguments.parseGetBuildContext` ignoring/not advertising any MCP `include_inventory` parameter so the toggle stays player-controlled.
- **Rationale**:
  - Keeping `includeInventory = false` as the default on `BuildContextRequest` makes pure unit tests and `LocalSpatialContextService` safe by default too, while tests covering enabled inventory can explicitly pass `includeInventory = true`.
  - Returning `null` early in `SurvivalSnapshotProvider` avoids unnecessary client-thread marshalling during CPU-only blueprint drafting.
- **Alternative considered**: Emitting `[INVENTORY_MATERIALS]\nignored` when disabled. Rejected because omitting the section altogether saves tokens on every `get_build_context` call (matching how `[OBSTACLES]` is omitted) while updating `SERVER_INSTRUCTIONS` clarifies that inventory is only reported when the player enables it.

### 3. Client command surface under `/archica inventory`
- **Decision**: Add `/archica inventory` (prints current `on`/`off` state), `/archica inventory on`, and `/archica inventory off` to `ArchicaClientCommands`, and append `inventory: on|off` to `/archica status`.
- **Rationale**: Consistent with `/archica mcp start|stop` ergonomics, tab-completable in chat, and requires no server operator permissions.

## Risks / Trade-offs

- **Existing unit tests expecting `[INVENTORY_MATERIALS]` by default** -> Tests such as `BuildContextScannerTest`, `McpServerCoreTest`, and `ArchicaMcpServerIntegrationTest` that assert `[INVENTORY_MATERIALS]` on default `BuildContextRequest()` will need to be updated to verify both the new default-off omission and the opt-in `includeInventory = true` behavior.
- **AI expectations from older prompts/docs** -> Updating `SERVER_INSTRUCTIONS`, `get_build_context` tool description, and `docs/` (`spatial-context.md`, `mcp-tools.md`, `architecture.md`, `README.md`) ensures external MCP clients understand that inventory is omitted unless the player runs `/archica inventory on`.
