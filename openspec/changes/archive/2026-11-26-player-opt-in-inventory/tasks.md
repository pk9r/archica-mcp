# Tasks

## 1. Core Spatial Context Gating

- [x] 1.1 Add `includeInventory: Boolean = false` to `BuildContextRequest` and `inventoryIncluded: Boolean = false` to `BuildContextResult` in `BuildContextScanner.kt`, skipping `source.getInventoryMaterials()` and omitting the `[INVENTORY_MATERIALS]` section from `formatText()` when `includeInventory` is `false` (verify via `BuildContextScannerTest` covering default-off omission, non-invocation of inventory source, and `includeInventory = true` aggregation).
- [x] 1.2 Update `docs/spatial-context.md` to document that `[INVENTORY_MATERIALS]` is omitted by default and only included when the player enables inventory tracking.

## 2. Client Toggle, Survival Check Gating & Commands

- [x] 2.1 Add an in-memory `AtomicBoolean(false)` inventory-tracking state in `ArchicaMcpClient.kt` (resetting to `false` on every client launch) and wire it into `spatialService.getBuildContext` (`includeInventory = inventoryEnabled.get()`) and `SurvivalSnapshotProvider` (returning `null` immediately without client-thread dispatch when `!inventoryEnabled.get()`); verify with `BlueprintSessionStoreTest` / `BlueprintCompilerServiceTest` that `survival_check` is omitted when the provider returns `null` and computed when enabled.
- [x] 2.2 Add `/archica inventory`, `/archica inventory on`, and `/archica inventory off` client commands plus `inventory: on|off` status reporting in `/archica status` inside `ArchicaClientCommands.kt` (verify via `./gradlew build` and client command registration inspection).
- [x] 2.3 Update `docs/architecture.md` to reflect in-memory default-off player opt-in inventory gating and `/archica inventory` commands.

## 3. MCP Server Metadata & Integration Verification

- [x] 3.1 Update `ArchicaMcpServerFactory.SERVER_INSTRUCTIONS` and `get_build_context` tool description in `ArchicaMcpServer.kt` to accurately state that player inventory materials and `survival_check` are omitted by default unless enabled by the player; update and pass `McpServerCoreTest` and `ArchicaMcpServerIntegrationTest` for both default-off and enabled-inventory paths.
- [x] 3.2 Update `docs/mcp-tools.md` and `README.md` to document default-off inventory behavior and `/archica inventory on|off`, then run `./gradlew test` and verify all tests pass.
