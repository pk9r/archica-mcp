# Design

## Context

Currently, Archica MCP rasterizes all `PrimitiveOp`s in a `BlueprintSessionStore` session into a single `VoxelGrid` (`BlueprintRasterizer.kt`), writes a single-region `.litematic` (`RegionCount = 1`) via `LitematicSchematicCompiler.kt`, and activates one `SchematicPlacement` (`archica:<blueprint_id>`) via `ClientLitematicaBridge.kt`. See [proposal.md](proposal.md) for motivation.

Two in-flight changes establish important constraints we must preserve:
- `draft-persistence`: adds `.draft.json` sidecar files (`BlueprintDraftPersistence.kt`) alongside `.litematic` exports.
- `server-support`: broadcasts compiled `.litematic` bytes over Fabric Networking (`SyncSharedPlacement`) to connected modded clients under `archica:shared:<blueprint_id>`.

Using **multi-region `.litematic` v7 schematics** (`RegionCount = N`, one region per non-empty semantic phase) aligns directly with both: the single `.litematic` file remains self-contained and portable, and once loaded into Litematica, players can switch phases or toggle `ISOLATED` (`== k`) <-> `CUMULATIVE` (`1..k`) <-> `ALL` locally in memory without rewriting `.litematic` files or re-syncing over the network.

## Goals / Non-Goals

**Goals:**
- Let AI agents tag operation batches (or single ops) with ordered semantic build phases (`phase_id`, `title`, `description`) with zero extra MCP round-trips.
- Guarantee geometrically accurate phases via **final-state attribution** so `clear_box`, `replace_box`, `autoConnectConnectables`, and two-half doors never cause players to place blocks that are later carved away.
- Compile phased blueprints to standard multi-region `.litematic` v7 files (`RegionCount = N`) whose subregions reconstruct the exact full build at `world_origin`.
- Control Litematica `SubRegionPlacement` visibility directly on the client thread for instant switching between phases and toggling `ISOLATED`, `CUMULATIVE`, and `ALL` modes.
- Provide frictionless in-game player controls: `/archica phase`, optional keybinds with ActionBar notices, and UI controls with a dedicated mode-toggle button and per-phase material breakdown.

**Non-Goals:**
- Automatic heuristic phasing (Y-slicing or block-type guessing) when the AI omits phases; unphased blueprints stay `RegionCount = 1`.
- Automatic world-block progress polling / completion percentage tracking (deferred to Roadmap Phase 3).
- Modifying world blocks directly (read-only world invariant remains unchanged).

## Decisions

### 1. Phase Model & Backward-Compatible Persistence
Add `BlueprintPhase(phaseId: String, order: Int, title: String, description: String? = null)` to `BlueprintModels.kt` and optional `phaseId: String? = null` on `StoredBlueprintOp`.
- **Batch-first authoring**: `blueprint_add_ops` accepts an optional `phase` parameter (`string` title/id or `{ id?, title, description? }`) applied to all ops in that call (with optional per-op override). If `phase_id` is omitted, a deterministic slug (`^[a-z0-9_-]{1,32}$`) is derived from `title` (deduplicated with numeric suffix if needed). Phases are ordered by first registration in the session (`order = 1..N`).
- **Alternative considered**: Requiring a separate `blueprint_define_phases` MCP tool before adding ops. Rejected because it adds extra LLM round-trips and increases ordering mistakes.
- **Persistence**: Extend `PersistedBlueprintDraft` with nullable/defaulted fields (`phases: List<PersistedPhase> = emptyList()`, `phaseId: String? = null` on `PersistedBlueprintOp`, and `phaseView: PersistedPhaseViewState? = null` storing `activePhaseId` and `viewMode`). Legacy `.draft.json` files deserialize cleanly with defaults.

### 2. Final-State Phase Attribution in `BlueprintRasterizer`
Instead of rasterizing each phase in isolation (which breaks `clear_box` and cross-phase `replace_box` / `autoConnectConnectables`), rasterization evaluates all ops `op_1..op_N` sequentially once into a `VoxelGrid` while maintaining a parallel `phaseMap: LinkedHashMap<BlockPos3i, String>`:
- Any block placement primitive (`set_block`, `fill_box`, `hollow_box`, `line`, `cylinder`, `dome`, `gable_roof`, `staircase`, and `repeat_pattern` wrapping them) writes both `grid.set(pos, spec)` and—when `op.phaseId != null`—`phaseMap[pos] = op.phaseId` (if an unphased op overwrites a phased cell, `phaseMap` falls back to keeping or clearing consistently; mixed phased+unphased ops assign unphased ops to a `"default"` / `"general"` phase only if the blueprint already has phases).
- `clear_box` removes `pos` from both `grid` and `phaseMap`.
- `replace_box` updates `phaseMap[pos] = op.phaseId` when `op.phaseId != null` and a block was actually replaced; otherwise retains the prior `phaseMap[pos]`.
- **Two-half doors**: When `set_block` places `half=lower` at `pos` and `half=upper` at `pos + (0, 1, 0)`, both coordinates receive the same `phaseId`.
- **Empty phase pruning**: After `autoConnectConnectables` finishes, `VoxelGrid` entries are partitioned by `phaseMap`. Any phase with `0` surviving voxels is excluded from `nonEmptyPhases`, `LitematicSchematicCompiler`, and player step navigation (preventing empty dead-end steps).

### 3. Multi-Region `.litematic` v7 Encoding (`RegionCount = N`)
`LitematicSchematicCompiler.compileToFile` transforms all non-air voxels into world-oriented coordinates around `worldBounds.min` (`globalMin = worldBounds.min`), exactly as in `v0.1.0`.
- **When `nonEmptyPhases.size <= 1` (unphased)**: Emits the existing single-region `.litematic` (`RegionCount = 1`, region key `blueprintId`, `Position = [0, 0, 0]`) — zero change to existing output.
- **When `nonEmptyPhases.size >= 2` (phased)**:
  - For each phase `i` (`1..N`), collect its world-oriented voxels and compute that phase's minimal `phaseBounds`.
  - Region name in NBT `Regions` compound: deterministic `phaseRegionName(order, phaseId)` (e.g., `01_foundation`, `02_frame`), keeping unique keys sorted by phase order.
  - Subregion `Position` = `phaseBounds.min - globalMin` (`>= 0` on all axes) and `Size` = `phaseBounds.size`. Each subregion encodes its own compact `BlockStatePalette` (`minecraft:air` at `0`) and bit-packed `BlockStates`.
  - Root `Metadata.RegionCount = N`, `Metadata.TotalVolume = globalBounds.volumeLong()`, `Metadata.TotalBlocks = totalNonAirBlocks`, `Metadata.EnclosingSize = globalBounds.size`.
- **Alternative considered**: Re-compiling a filtered `RegionCount = 1` file every time the player clicks Next Phase or toggles `ISOLATED` <-> `CUMULATIVE`. Rejected per user preference for multi-region `.litematic` so switching is instant in RAM, portable in one `.litematic` file, and naturally compatible with `server-support` broadcast.

### 4. Subregion Visibility Control in `ClientLitematicaBridge`
Extend `LitematicaBridge` with:
- `activatePlacement(..., visibleRegions: Set<String>? = null)` (when non-null, applies subregion visibility immediately on creation).
- `setSubRegionVisibility(blueprintId: String, enabledRegions: Set<String>): Boolean` (updates subregion enabled flags on the active `archica:<blueprint_id>` placement on the client thread and triggers Litematica placement render refresh).
- **View mode resolution**: Given ordered `nonEmptyPhases` (`p_1..p_N`), active index `k`, and `PhaseViewMode`:
  - `ISOLATED`: `{ regionName(p_k) }`
  - `CUMULATIVE`: `{ regionName(p_1), ..., regionName(p_k) }`
  - `ALL`: `{ regionName(p_1), ..., regionName(p_N) }`
- **Default on first compile**: Phase `1` (`k = 1`), mode `ISOLATED`. On recompile, if `activePhaseId` still exists in `nonEmptyPhases`, preserve `(activePhaseId, viewMode)`; otherwise clamp safely to the nearest valid phase.
- **Implementation verification note**: Before coding `ClientLitematicaBridge.setSubRegionVisibility`, verify the exact Litematica `0.29.1` `SubRegionPlacement` toggle and `SchematicPlacementManager` chunk-rebuild / dirty-mark methods against the Loom compile classpath.

### 5. Player UX: Keybinds, `/archica phase`, ActionBar, and UI Screen
- **Quick in-world controls** (`ArchicaMcpClient.kt` + `ArchicaClientCommands.kt`):
  - Three unbound-by-default `KeyMapping`s: `Next Build Phase`, `Previous Build Phase`, `Toggle Phase View Mode` (flips `ISOLATED` <-> `CUMULATIVE`; if currently `ALL`, switches to `ISOLATED` at the current phase).
  - `/archica phase` (`list`, `next`, `prev`, `<index|phase_id>`, `mode <isolated|cumulative|all>`).
  - Switching updates `ClientLitematicaBridge.setSubRegionVisibility`, persists `(activePhaseId, viewMode)` to `.draft.json`, and displays a single-line ActionBar message (`player.displayClientMessage(..., true)`) with `[blueprint_id] Phase k/N: <title> | <Mode> (<phase_blocks> blocks)` so chat is not flooded.
- **UI Screen** (`ArchicaConfigScreen.kt` + dedicated `ArchicaPhaseScreen` linked by a button on `ArchicaConfigScreen` to keep the main settings panel compact):
  - Blueprint selector (when multiple phased drafts exist), ordered clickable phase list, `[Prev]` / `[Next]` buttons, and a `CycleButton` toggling **`Mode: Isolated` / `Mode: Cumulative` / `Mode: All`**.
  - Displays current phase `description` and per-phase material list (highlighting missing items in Survival when `/archica inventory on` is active).

## Risks / Trade-offs

- **[Litematica `0.29.1` subregion render refresh behavior]** -> Toggling `SubRegionPlacement` enabled state must invalidate Litematica's schematic world chunk render cache on the client thread. Mitigation: inspect `SchematicPlacement` / `SchematicPlacementManager` on the Loom classpath at the start of implementation; if a Litematica quirk prevents clean partial refresh, `LitematicaBridge` can fall back cleanly to updating placement flags and re-marking the placement dirty.
- **[Litematica Material List scope]** -> In Litematica's own `M+L` Material List, multi-region schematics may default to counting all subregions unless filtered per subregion. Mitigation: surface exact per-phase material counts and Survival missing counts directly in Archica's phase UI, `/archica phase`, and `blueprint_inspect`.
- **[Subregion wireframe clutter in Litematica]** -> Multiple regions whose voxels span across the structure produce overlapping bounding boxes when Litematica box rendering is on. Mitigation: keep Litematica's native behavior standard while letting players hide future phases cleanly via `ISOLATED` / `CUMULATIVE`.
- **[Multiplayer `server-support` sync]** -> When `server-support` broadcasts a multi-region `.litematic`, clients can toggle phase subregions locally even without server state, though sharing phase titles/descriptions in multiplayer will benefit from either reading region names (`01_foundation`) or syncing phase metadata in a follow-up `server-sync` extension.
