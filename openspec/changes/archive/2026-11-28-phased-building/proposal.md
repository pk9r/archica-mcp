# Proposal

## Why

Currently, Archica MCP compiles an entire blueprint into a single `.litematic` region (`RegionCount = 1`) and displays every block simultaneously as one Litematica hologram. For multi-part or Survival builds, showing foundations, framing, walls, interior details, and roofs all at once creates heavy visual clutter and hides build order—players cannot easily see which structural part should be built first without manually slicing layers in Litematica.

## What Changes

- Allow AI agents to explicitly organize blueprint operations into ordered, named **semantic build phases** (e.g., Foundation -> Structural Frame -> Walls & Openings -> Interior -> Roof) via `blueprint_add_ops` / `blueprint_update_op`, while remaining 100% backward-compatible with unphased blueprints.
- Implement **final-state phase attribution** during rasterization so later operations (`clear_box`, `replace_box`, `autoConnectConnectables`, two-half doors) carve and finalize geometry accurately and each surviving voxel belongs cleanly to the phase that produced its final state.
- Upgrade `LitematicSchematicCompiler` to compile phased blueprints into standard **multi-region `.litematic` v7 schematics** (`RegionCount = N`, one named subregion per non-empty phase) instead of a single monolithic region.
- Extend `LitematicaBridge` and `ClientLitematicaBridge` to control per-subregion visibility on the active `archica:<blueprint_id>` placement, enabling instant switching between phases and a two-mode toggle between **Isolated** (only current phase `k`) and **Cumulative** (phases `1..k`), plus an option to view **All** phases.
- Add in-game player controls: `/archica phase` commands, optional keybinds (`Next Phase`, `Previous Phase`, `Toggle Phase Mode`), ActionBar status feedback, and UI controls in the Archica client screen (including the mode toggle button and per-phase material breakdown).
- Persist phase metadata and the player's current phase cursor / view mode in `.draft.json` across restarts.

## Capabilities

### New Capabilities

- `phased-building`: Semantic phase definitions (`phase_id`, `title`, `description`, `order`), phase-scoped material and survival summaries, active phase cursor and view mode state (`ISOLATED`, `CUMULATIVE`, `ALL`), and phase navigation lifecycle.

### Modified Capabilities

- `blueprint-primitives`: Optional phase assignment on `blueprint_add_ops` / `blueprint_update_op`, final-state voxel-to-phase attribution across `clear_box`/`replace_box`/paired doors, and persistence of phase definitions and cursor state in `.draft.json`.
- `litematica-compiler`: Multi-region `.litematic` v7 compilation (`RegionCount = N`, per-phase subregions with accurate relative `Position` and `Size`) for phased blueprints, plus `LitematicaBridge` subregion visibility toggling for `ISOLATED` / `CUMULATIVE` / `ALL` modes.
- `mcp-server`: Extend `blueprint_add_ops`, `blueprint_update_op`, `blueprint_inspect`, `blueprint_compile`, `blueprint_list`, and `SERVER_INSTRUCTIONS` to accept and report semantic build phases and active phase state.
- `client-ux`: Add `/archica phase` commands, optional keybinds (`Next Phase`, `Prev Phase`, `Toggle Phase Mode`), ActionBar phase notices, and in-screen phase selector + `Isolated / Cumulative` toggle button.

## Impact

- **Blueprint Domain & Persistence (`src/main`)**: `BlueprintModels.kt`, `VoxelGrid.kt`, `BlueprintRasterizer.kt`, `BlueprintSessionStore.kt`, `BlueprintDraftPersistence.kt`.
- **Compiler & Litematica Bridge (`src/main`, `src/client`)**: `LitematicSchematicCompiler.kt`, `LitematicaBridge.kt`, `ClientLitematicaBridge.kt` (subregion visibility and render refresh against Litematica `0.29.1`).
- **MCP Protocol (`src/main`)**: `ArchicaMcpServer.kt`, `BlueprintToolCodec.kt`.
- **Client UX (`src/client`)**: `ArchicaMcpClient.kt`, `ArchicaClientCommands.kt`, `ArchicaConfigScreen.kt` (and/or a dedicated phase screen), `en_us.json` localization strings.
- **Agent Skills (`.agents/plugins/archica-mcp/`)**: Update `archica-mcp-workflow` to guide AI agents on organizing builds into 3–6 semantic phases.
