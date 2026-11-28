# Tasks

## 1. Phase Model, Final-State Attribution & Persistence (`src/main`)

- [x] 1.1 Add `BlueprintPhase`, `PhaseViewMode` (`ISOLATED`, `CUMULATIVE`, `ALL`), `PhaseViewState`, and optional `phaseId` on `StoredBlueprintOp` in `BlueprintModels.kt` / `VoxelGrid.kt`, plus per-phase `materialCounts()` partitioning, and verify unit tests pass via `./gradlew test --tests "com.archica.mcp.blueprint.*"`.
- [x] 1.2 Implement final-state phase attribution in `BlueprintRasterizer.kt` (`phaseMap` alongside `VoxelGrid` across placements, `clear_box`, `replace_box`, `repeat_pattern`, paired two-half doors, `autoConnectConnectables`, and empty-phase pruning) and verify unit tests in `BlueprintRasterizerTest` via `./gradlew test --tests "com.archica.mcp.blueprint.BlueprintRasterizerTest"`.
- [x] 1.3 Extend `BlueprintSessionStore`, `BlueprintService`, and `BlueprintDraftPersistence.kt` to register/order semantic phases, compute per-phase materials & phase-scoped `survival_check`, manage active `(activePhaseId, viewMode)` cursor with recompile preservation/clamping, and persist backward-compatible phase fields in `.draft.json`, verified by `BlueprintSessionStoreTest` and `BlueprintDraftStoreTest`.

## 2. Multi-Region `.litematic` Compiler & Litematica Bridge

- [x] 2.1 Upgrade `LitematicSchematicCompiler.kt` to emit multi-region `.litematic` v7 NBT (`RegionCount = N`, deterministic phase region names, relative `Position` and `Size` per non-empty phase) when `nonEmptyPhases.size >= 2` while preserving single-region `RegionCount = 1` for unphased blueprints, verified by `LitematicSchematicCompilerTest`.
- [x] 2.2 Inspect Litematica `0.29.1` `SchematicPlacement` / `SubRegionPlacement` / `SchematicPlacementManager` APIs on the compile classpath and extend `LitematicaBridge` (`src/main`) + `ClientLitematicaBridge` (`src/client`) + `FakeLitematicaBridge` (`src/test`) with subregion visibility application on activation and in-place `setSubRegionVisibility` without rewriting `.litematic` on disk, verified by `BlueprintCompilerServiceTest` and `./gradlew compileClientKotlin`.

## 3. MCP Server Tools, Schemas & Agent Skill

- [x] 3.1 Extend `BlueprintToolCodec.kt` and `ArchicaMcpServer.kt` to accept optional `phase` on `blueprint_add_ops` and `blueprint_update_op`, expose per-phase breakdowns and active phase/mode state in `blueprint_inspect`, `blueprint_compile`, and `blueprint_list`, and update `SERVER_INSTRUCTIONS` + tool schemas, verified by `McpServerCoreTest` and `ArchicaMcpServerIntegrationTest`.
- [x] 3.2 Update `.agents/plugins/archica-mcp/skills/archica-mcp-workflow/SKILL.md` (and `docs/mcp-tools.md` / `docs/blueprint-primitives.md`) to document semantic `phase` grouping guidelines and player view-mode controls, and verify documentation matches implemented tool schemas.

## 4. Client UX: Commands, Keybinds, ActionBar & Phase UI (`src/client`)

- [x] 4.1 Implement `BlueprintService` phase-navigation methods (`setActivePhase`, `stepPhase`, `setPhaseViewMode`, `togglePhaseViewMode`) wired to `ClientLitematicaBridge.setSubRegionVisibility` and draft persistence, plus `/archica phase` subcommands (`list`, `next`, `prev`, `<index|phase_id>`, `mode <isolated|cumulative|all>`) in `ArchicaClientCommands.kt`, verified by `./gradlew test compileClientKotlin`.
- [x] 4.2 Register optional unbound keybinds (`Next Build Phase`, `Previous Build Phase`, `Toggle Phase View Mode`) and ActionBar status feedback in `ArchicaMcpClient.kt`, plus `ArchicaPhaseScreen` (accessible from `ArchicaConfigScreen.kt`) with blueprint selector, phase list, Prev/Next buttons, `Isolated / Cumulative / All` `CycleButton`, and per-phase material/survival breakdown, plus `en_us.json` entries, verified by `./gradlew compileClientKotlin`.

## 5. End-to-End Verification

- [x] 5.1 Run full `./gradlew test build` and `openspec validate phased-building`, confirming all unit/integration tests, Fabric client compilation/packaging, and OpenSpec validation checks pass cleanly.
