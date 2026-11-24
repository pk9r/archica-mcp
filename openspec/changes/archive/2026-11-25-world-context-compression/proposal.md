# Proposal

## Why

Raw 3D Minecraft chunks around a player contain tens of thousands of blocks—mostly air or underground stone—making direct world dumps unusable in LLM context windows. Now that the Fabric + Kotlin mod project is scaffolded, Archica MCP needs its spatial world-context compression layer implemented and locked down around an accurate, token-bounded outdoor workflow before blueprint and MCP transport integration build on top of it.

## What Changes

- Introduce the `world-context-compression` spatial scanning and encoding capability across a pure-Kotlin core module and a Minecraft client-world adapter:
  - **`get_build_context` (outdoor-first 2D surface summary):** Scans a square `(2 * radius + 1)` XZ grid centered on the player (`default radius = 12`, `min = 4`, `max = 24`) from top to bottom within `[playerY - 16, playerY + 24]`. Treats canopies, trunks, and roofs directly as surface blocks while skipping thin/non-structural pass-through clutter (grass, flowers, thin snow, torches). Encodes `[TERRAIN_GRID]` as an aligned, non-RLE `<symbol><deltaY>` matrix with a deterministic 1-to-1 `[SURFACE_LEGEND]`, includes `[PLAYER]` pose + outdoor `suggested_origin` (snapping to nearby solid non-water/non-leaf terrain within 3 blocks of the ~10-block look target), and aggregates `[INVENTORY_MATERIALS]`.
  - **Scope refinement from initial draft docs:** Removes heuristic `[OBSTACLES]` clustering and `include_obstacles` from v1 in favor of direct surface representation on the aligned terrain grid plus on-demand 3D drill-down. Enclosed/underground multi-layer scanning is explicitly out of scope for v1.
  - **`scan_volume` (on-demand 3D RLE inspection):** Scans a bounded 3D box (hard cap `13,824` blocks / `24x24x24`), emits a numeric `[PALETTE]` (`0=minecraft:air`), skips all-air Y layers, and compresses Z-rows with X-axis RLE (`count*paletteId`). Adds an optional `include_states` boolean parameter (default `false`) that, when enabled, appends normalized non-default/geometric blockstate properties (e.g., `facing`, `half`, `axis`, `shape`, `open`) while stripping noisy properties.

## Capabilities

### New Capabilities

- `world-context-compression`: Outdoor player-radius 2D terrain/inventory context extraction (`get_build_context`) and bounded 3D RLE voxel slice inspection with optional compact blockstate serialization (`scan_volume`).

### Modified Capabilities

<!-- None: no durable OpenSpec capabilities exist yet in openspec/specs/. -->

## Impact

- **Pure Kotlin core (`src/main/kotlin/com/archica/mcp/context/`):** World snapshot abstractions, pass-through block classification, 1-to-1 symbol legend assignment, aligned `<symbol><deltaY>` grid formatter, `suggested_origin` solver, inventory aggregator, RLE 3D slice encoder, and blockstate property normalizer.
- **Fabric client adapter (`src/client/kotlin/com/archica/mcp/context/`):** Main-thread `ClientWorld` / `ClientPlayerEntity` reader implementing the snapshot abstraction and missing-world guard (`NO_WORLD_LOADED`).
- **Unit tests (`src/test/kotlin/com/archica/mcp/context/`):** Deterministic tests covering outdoor terrain encoding, pass-through handling, `suggested_origin` fallback near water/trees, RLE 3D compression, blockstate filtering, and volume-cap validation.
- **Documentation alignment (`docs/spatial-context.md`, `docs/mcp-tools.md`):** Update v1 contract docs to reflect `radius` default `12` / max `24`, aligned non-RLE terrain grid, removal of v1 `[OBSTACLES]`, and `scan_volume.include_states`.
