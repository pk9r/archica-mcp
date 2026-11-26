# Archica MCP Upgrade Roadmap

> **Status:** Planning & Exploration — `v0.1.0` is implemented; Phases 1–4 (`v0.2.0`–`v0.5.0`) below are **Planned / Exploring** directions rather than finalized schemas or active contracts. Each phase will be scoped through its own OpenSpec proposal, specs, and design before implementation.

This document captures the multi-phase upgrade roadmap for **Archica MCP** beyond the `v0.1.0` core release.

---

## 1. `v0.1.0` Implemented Baseline

`v0.1.0` establishes the core end-to-end AI-to-Litematica drafting loop inside the Minecraft `26.3` Fabric client:

- **Embedded Loopback MCP Server (`ArchicaMcpServer`)**: Streamable HTTP endpoint at `http://127.0.0.1:25580/mcp` with DNS-rebinding protection and 11 registered tools (`get_build_context`, `scan_volume`, `blueprint_create`, `blueprint_list`, `blueprint_add_ops`, `blueprint_update_op`, `blueprint_remove_op`, `blueprint_undo`, `blueprint_inspect`, `blueprint_compile`, `blueprint_clear_placement`). See [MCP Tools Reference](mcp-tools.md).
- **Spatial Context Compression (`BuildContextScanner`, `VolumeScanner`)**: Outdoor top-down 2D terrain grid (`radius` `4..24`), suggested build origin, opt-in inventory summary (`/archica inventory [on|off]`, defaulting to `off`), and RLE-encoded 3D `scan_volume` slices (`<= 13,824` blocks). See [Spatial Context Compression](spatial-context.md).
- **Stateful Blueprint Primitives (`BlueprintSessionStore`, `VoxelGrid`)**: In-memory draft sessions supporting 11 anchor-relative primitives (`set_block`, `fill_box`, `hollow_box`, `line`, `cylinder`, `dome`, `gable_roof`, `staircase`, `replace_box`, `clear_box`, `repeat_pattern`), auto-orientation (`stairs`, `logs`, `doors`, horizontal `connectable` blocks), and a `131,072`-block enclosing volume cap. See [Stateful Blueprints & Building Primitives](blueprint-primitives.md).
- **`.litematic` Compiler & Litematica Bridge (`LitematicSchematicCompiler`, `ClientLitematicaBridge`)**: Atomic v7 single-region `.litematic` export under `.minecraft/schematics/archica/` and automatic placement activation/replacement/clearance (`archica:<blueprint_id>`). See [Architecture & Litematica Integration](architecture.md).
- **Client UX & Antigravity Packaging (`ArchicaClientCommands`, `ArchicaClientConfig`, `ArchicaConfigScreen`)**: In-game `/archica` config screen (plus optional Mod Menu integration) backed by `.minecraft/config/archica-mcp.json`, `/archica status`, `/archica inventory [on|off]`, `/archica mcp start [port]`, `/archica mcp stop`, and `/archica clear <blueprint_id|all>` commands, chat placement notices, and workspace-scoped Antigravity plugin skills under `.agents/plugins/archica-mcp/`. See [Antigravity Setup Guide](antigravity-setup.md).

---

## 2. Post-`v0.1.0` Phase Dependency Flow

Phases are ordered by architectural dependency: persist and import drafts first, expand modeling power next, close the in-world construction feedback loop, and then broaden spatial context and cross-client support.

```text
+-----------------------------------------------------------------------------+
|                        ARCHICA MCP UPGRADE ROADMAP                          |
+-----------------------------------------------------------------------------+
|                                                                             |
|  [v0.1.0 Baseline]  11 tools | 11 primitives | In-memory | .litematic v7    |
|         |                                                                   |
|         v                                                                   |
|  [Phase 1: v0.2.0]  Draft Persistence, .litematic Import & World Capture    |
|   - Lossless draft session persistence (sidecar JSON synced on restart)     |
|   - Capture existing world regions into a compact blueprint base layer      |
|   - Import .litematic files (reusing NbtBinaryCodec + LitematicaBitArray)   |
|         |                                                                   |
|         v                                                                   |
|  [Phase 2: v0.3.0]  Architectural Primitives, Blockstate Coverage & Preview |
|   - New primitives: auto_foundation, copy/mirror/rotate, hip_roof/cone,     |
|     spiral_staircase, palette_swap                                          |
|   - Expanded blockstates (slab, trapdoor, bed, chest) + ASCII slice preview |
|         |                                                                   |
|         v                                                                   |
|  [Phase 3: v0.4.0]  Live Build Progress & Deeper Litematica Bridge          |
|   - Compare active archica:<id> placement vs world (% done, missing, wrong) |
|   - Y-layer render control via MCP / commands & multi-phase large builds    |
|         |                                                                   |
|         v                                                                   |
|  [Phase 4: v0.5.0]  Smart Spatial Context & Universal MCP Client UX         |
|   - Indoor/cave horizontal scanning + nearby container material checks      |
|   - Expose 5x5 architectural guides & palettes via MCP Prompts/Resources    |
|   - Extended config thresholds & /archica list                              |
|                                                                             |
+-----------------------------------------------------------------------------+
```

---

## 3. Phase Breakdown

### Phase 1 (`v0.2.0`) — Draft Persistence, `.litematic` Import & World Capture
**Status:** `Planned`

- **Current Gaps in `v0.1.0`**:
  - `BlueprintSessionStore` keeps draft sessions (`origin`, `facing`, `ops`, `nextOpNumber`) exclusively in an in-memory `ConcurrentHashMap`. Restarting Minecraft drops all draft history even though `.litematic` files and active `archica:<id>` Litematica placements persist, leaving Litematica holograms out of sync with `blueprint_list`.
  - Read-side helpers already exist in [`NbtBinaryCodec.readGzipCompound`](../src/main/kotlin/com/archica/mcp/litematica/NbtCodec.kt) and [`LitematicaBitArray.getAt`](../src/main/kotlin/com/archica/mcp/litematica/LitematicaBitArray.kt), but no schematic import workflow uses them yet.
  - `LitematicSchematicCompiler` currently serializes `BlockSpec` (block ID + blockstate properties) without TileEntity or Entity NBT.
- **Planned Scope**:
  - **Lossless Draft Persistence**: Persist `BlueprintSessionStore` AST state (`origin`, `facing`, `ops`, `nextOpNumber`) to disk so drafts reload cleanly across game restarts and stay aligned with active `archica:*` placements.
  - **`.litematic` Import**: Decode existing `.litematic` schematics into draft sessions so players and agents can remix, resize, or palette-swap existing builds.
  - **World Region Capture**: Capture a bounded region from the loaded world directly into a draft session as a compact base snapshot layer so renovation edits (`clear_box`, `replace_box`, `gable_roof`) apply cleanly on top of existing structures.
- **Value Delivered**: Transforms Archica MCP from a single-session sketching aid into a durable multi-day project workflow and unlocks true renovation/remixing of existing builds.
- **Open Design Questions**:
  1. Should draft sidecars live alongside `.litematic` files (`schematics/archica/<id>.draft.json`) for portability, or in a world/mod-state directory?
  2. How should imported voxel snapshots be represented inside `ops` (dedicated compact `voxel_stamp` / `import_snapshot` base op vs. greedy-box merge into `fill_box` ops) to keep `blueprint_inspect` token-efficient?
  3. Should TileEntity/Entity NBT from external `.litematic` files be stripped with an explicit warning in v0.2.0 or partially preserved?

---

### Phase 2 (`v0.3.0`) — Architectural Primitives, Blockstate Coverage & Preview
**Status:** `Planned`

- **Current Gaps in `v0.1.0`**:
  - Builds on sloped terrain require manual pillar/fill ops to prevent floating bases.
  - No built-in symmetry/reuse primitives (`mirror`, `rotate_region`, `copy_box`), hip/conical roofs, spiral stairs, or whole-blueprint material swaps.
  - Auto-orientation covers `stairs`, `logs`, `doors`, and horizontal `connectable` blocks, while `slabs`, `trapdoors`, `beds`, `chests`, and wall-mounted blocks require manual property tuning; agents also lack an in-response visual check prior to compiling.
- **Planned Scope**:
  - **Higher-Level Primitives**: Terrain-adapting `auto_foundation`, region transforms (`copy_box`, `mirror`, `rotate_region`), roof/stair additions (`hip_roof`, `cone`, `spiral_staircase`), and scoped/global `palette_swap`.
  - **Expanded Blockstate Rules**: Better defaults and validation for `slab` (`top`/`bottom`/`double`), `trapdoor`, `bed`, `chest` (single/double facing), and wall attachments.
  - **ASCII Slice / Footprint Preview**: Optional compact ASCII cross-section or layer view in `blueprint_inspect` so agents can catch misaligned openings, roof overlaps, or staircase clearances before writing `.litematic`.
- **Value Delivered**: Reduces `op` count and token overhead for complex structures while catching geometric mistakes earlier in the drafting loop.
- **Open Design Questions**:
  1. Should `auto_foundation` read world terrain dynamically at rasterization/compile time or bake terrain heights at op creation time?
  2. Which ASCII preview projections (top-down per-floor footprint vs. X/Z cross-section) give the best defect-detection-to-token ratio?

---

### Phase 3 (`v0.4.0`) — Live Build Progress & Deeper Litematica Bridge
**Status:** `Planned`

- **Current Gaps in `v0.1.0`**:
  - Feedback between the player building in-world and the AI assistant is purely manual (the player inspects the hologram and types corrections in chat).
  - Litematica layer rendering and multi-region/phased schematics cannot yet be coordinated through MCP tools.
  - `VoxelGrid` enforces a hard `131,072`-block enclosing cap (`VOLUME_TOO_LARGE`).
- **Planned Scope**:
  - **Live Build Progress Inspection**: Compare an active `archica:<id>` placement against placed world blocks to report completion percentage, remaining blocks at the site, and misplaced or misoriented blocks.
  - **Layer & Phased Build Controls**: Expose Litematica render-layer navigation (single-layer / slice-by-Y workflows) via MCP and `/archica` commands, and explore multi-region or multi-phase compilation for large builds.
- **Value Delivered**: Closes the loop during actual construction so the agent can guide survival building step by step, spot placement errors, and manage larger builds cleanly.
- **Open Design Questions**:
  1. What maximum comparison volume and sampling cadence keeps main-thread client ticks smooth during progress checks?
  2. Should multi-phase large builds be modeled as linked sub-blueprints in `BlueprintSessionStore` or multi-region `.litematic` exports?

---

### Phase 4 (`v0.5.0`) — Smart Spatial Context & Universal MCP Client UX
**Status:** `Planned`

- **Current Gaps in `v0.1.0`**:
  - `get_build_context` scans top-down (`[playerY - 16, playerY + 24]`), so roofs, ceilings, and cave overhangs mask interior floors and underground spaces.
  - Survival material checks inspect only the player's own inventory (and only when `/archica inventory on` is toggled each session), ignoring nearby storage containers and offering no material-substitution suggestions.
  - The 5x5 architectural skills live in the Antigravity workspace plugin (`.agents/plugins/archica-mcp/`), so other MCP hosts (Claude Desktop, Cursor, VS Code) do not automatically receive them.
  - `.minecraft/config/archica-mcp.json` and `ArchicaConfigScreen` currently cover port, auto-start, and chat feedback, leaving scanner/guard thresholds and in-chat draft listing (`/archica list`) unexposed.
- **Planned Scope**:
  - **Indoor & Cave Context Mode**: Horizontal/slice-aware context scanning around `playerY` for interiors, basements, and caves without roof occlusion.
  - **Container Material Awareness & Substitutions**: Optional scanning of nearby chests/barrels/shulkers and survival-friendly block substitution suggestions.
  - **MCP Prompts & Resources**: Serve core 5x5 building guidelines and curated material palettes directly from `ArchicaMcpServer` via standard MCP Prompts and Resources.
  - **Extended In-Game UX & Config Options**: Add `/archica list` for quick draft/placement overview in chat and expose additional scanner/guard settings in `ArchicaClientConfig` / `ArchicaConfigScreen`.
- **Value Delivered**: Makes Archica MCP equally capable underground/indoors and provides a consistent architectural experience across all MCP-compatible AI clients.
- **Open Design Questions**:
  1. Should nearby container scanning require explicit player opt-in (similar to `/archica inventory on`) to avoid unexpected world/container reads on multiplayer servers?
  2. Should MCP Prompts/Resources mirror `.agents/plugins/archica-mcp/skills/` from a single shared resource bundle at build time to prevent drift?

---

## 4. Related Documentation

- [Architecture & Litematica Integration](architecture.md)
- [Spatial Context Compression](spatial-context.md)
- [Stateful Blueprints & Building Primitives](blueprint-primitives.md)
- [MCP Tools Reference](mcp-tools.md)
- [Antigravity Setup Guide](antigravity-setup.md)
