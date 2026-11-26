# Proposal

## Why

`README.md` currently ends its `Status & Roadmap` section with the five completed `v0.1.0` milestones and offers no forward-looking roadmap for post-v1 upgrades. Capturing the multi-phase upgrade plan in repository documentation preserves explored architectural priorities and technical tradeoffs—without prematurely committing to code changes—so future OpenSpec changes can be scoped cleanly when ready.

## What Changes

- Create `docs/roadmap.md` (in English, matching existing `docs/*.md` files) documenting the post-`v0.1.0` upgrade roadmap across four dependency-ordered phases:
  - **Phase 1 (`v0.2.0`) — Draft Persistence, `.litematic` Import & World Capture**: Lossless draft session persistence (sidecar JSON for `BlueprintSessionStore` to stay synchronized with Litematica placements across restarts), world-region capture into a draft session, and `.litematic` import (building on existing `NbtBinaryCodec.readGzipCompound` and `LitematicaBitArray.getAt`) using a compact base-snapshot representation.
  - **Phase 2 (`v0.3.0`) — Architectural Primitives, Blockstate Coverage & Preview**: Higher-level primitives (`auto_foundation`, `copy_box`/`mirror`/`rotate_region`, `hip_roof`/`cone`, `spiral_staircase`, `palette_swap`), expanded blockstate validation/auto-orientation (`slab`, `trapdoor`, `bed`, `chest`), and optional ASCII slice preview in `blueprint_inspect`.
  - **Phase 3 (`v0.4.0`) — Live Build Progress & Deeper Litematica Bridge**: Real-time comparison between active `archica:<id>` placements and world blocks (completion %, missing blocks, misplaced/misoriented blocks), Y-layer render control via MCP/`/archica`, and multi-phase/subregion support for large builds.
  - **Phase 4 (`v0.5.0`) — Smart Spatial Context & Universal MCP Client UX**: Indoor/cave horizontal scanning around `playerY`, nearby chest/container material scanning with survival substitution hints, exposing 5x5 architectural guidelines/palettes as standard MCP Prompts/Resources, and persistent client config / `/archica list`.
- Update `README.md` under `Documentation` and `Status & Roadmap` to link `docs/roadmap.md` and concisely list the planned post-`v0.1.0` phases alongside the completed v1 baseline.

## Capabilities

### New Capabilities
<!-- None: pure documentation change (`skip_specs: true` in `.openspec.yaml`) with no runtime behavior changes. -->

### Modified Capabilities
<!-- None: existing specs remain unchanged until individual phases are proposed and implemented. -->

## Impact

- **Documentation**: `docs/roadmap.md` (new), `README.md` (modified).
- **Code / Runtime**: None — no changes to Kotlin source files, MCP tool schemas, `.litematic` compiler, or tests.
- **OpenSpec Specs**: Skipped (`skip_specs: true` in `.openspec.yaml`) because no observable system behavior changes.
