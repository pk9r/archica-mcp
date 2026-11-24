# Design

## Context

Archica MCP is a client-only Fabric + Kotlin mod for Minecraft `26.3` using Fabric Loom's `splitEnvironmentSourceSets()` layout (`src/main/kotlin` for pure/common Kotlin, `src/client/kotlin` for `MinecraftClient` / `ClientWorld` integration, and `src/test/kotlin` for JUnit 5 unit tests). Currently only the mod entrypoint scaffold exists; world reading, spatial compression, and the embedded MCP server have not yet been implemented.

See [proposal.md](proposal.md) for motivation and scope, and [specs/world-context-compression/spec.md](specs/world-context-compression/spec.md) for the exact externally observable contracts.

## Goals / Non-Goals

**Goals:**
- Keep all spatial compression, legend allocation, coordinate snapping, inventory sorting, RLE encoding, and blockstate filtering pure Kotlin in `src/main/kotlin/com/archica/mcp/context/` so behavior is deterministically unit-testable via `./gradlew test` without launching Minecraft.
- Isolate Minecraft `ClientWorld` / `ClientPlayerEntity` reads behind a thin snapshot/reader boundary in `src/client/kotlin/com/archica/mcp/context/` that executes safely on the client main thread.
- Optimize `get_build_context` for outdoor spatial accuracy and compact token count (~625 cells at default `radius = 12`).

**Non-Goals:**
- Indoor ceiling/multi-floor disambiguation, cave/underground scanning, or `[OBSTACLES]` connected-component clustering (explicitly deferred beyond v1).
- Wiring the Ktor CIO MCP server transport or tool registration itself (covered by core MCP transport/server wiring; this change provides the pure services and client-world adapters those tools invoke).

## Decisions

### 1. Split Pure Compression Core from ClientWorld Adapter
- **Decision:** Define lightweight immutable/read-only abstractions in `src/main/kotlin/com/archica/mcp/context/` (`WorldContextSource` / `BlockSample` / `PlayerSnapshot` / `InventoryEntry`) consumed by `BuildContextScanner` and `VolumeScanner`. Implement the Minecraft-backed `WorldContextSource` in `src/client/kotlin/com/archica/mcp/context/` using `MinecraftClient`, `ClientWorld`, and `ClientPlayerEntity`.
- **Rationale:** Loom's `splitEnvironmentSourceSets()` keeps `src/test` isolated from client-only dependencies (`MinecraftClient`, Litematica) by default, and testing terrain edge cases against an in-memory fake grid is orders of magnitude faster and more deterministic than booting a headless Minecraft client.
- **Alternative considered:** Reading `ClientWorld` directly inside formatting functions. Rejected because it couples string formatting to Minecraft registries and prevents pure JVM unit testing.

### 2. Aligned Non-RLE `[TERRAIN_GRID]` with `radius = 12` Default (Max `24`)
- **Decision:** Use a space-separated, column-aligned `<symbol><deltaY>` matrix without RLE in `get_build_context`, reducing default `radius` from `16` (`1,089` cells) to `12` (`625` cells) and capping `radius` at `24` (`2,401` cells).
- **Rationale:** Row-level RLE on 2D terrain grids forces LLMs to mentally sum run lengths to recover `(x, z)` offsets, causing off-by-one placement errors when choosing build anchors. Lowering the default grid to `25x25` keeps token usage predictable while preserving direct column-by-column readability.
- **Alternative considered:** Row-level RLE (`4*.0`) at `radius = 16..32`. Rejected because coordinate accuracy for outdoor placement outweighs RLE savings on mixed outdoor terrain.

### 3. Direct Canopy Surface + Deterministic 1-to-1 `[SURFACE_LEGEND]`
- **Decision:** Scan columns strictly top-down across `[max(bottomY, playerY - 16), min(topY, playerY + 24)]`, skipping only a curated pass-through set/predicate (air, short/tall grass, ferns, flowers, saplings, thin snow layers `layers=1`, torches, signs, carpets, vines). Leaves, logs, water, and structures remain the reported surface block, removing heuristic `[OBSTACLES]` clustering in v1.
- **Decision on Legend Mapping:** Reserve readable defaults for high-frequency outdoor blocks (`minecraft:grass_block` -> `.`, `minecraft:water` -> `~`, `minecraft:dirt` -> `,`, `minecraft:sand` -> `:`, `minecraft:cobblestone` -> `#`, `minecraft:stone` -> `S`, `minecraft:oak_log` -> `T`, `minecraft:oak_leaves` -> `L`), and allocate unused characters from a deterministic pool (`a..z`, `A..Z`, and safe punctuation) strictly 1-to-1 per distinct block ID encountered in the grid. Digits `0..9`, signs (`+`, `-`), `?`, and whitespace are excluded from the symbol pool so `<symbol><deltaY>` tokens remain unambiguous.
- **Rationale:** Eliminates ambiguous shared symbols (e.g., `dirt` and `sand` sharing `,`) and removes fragile 3D flood-fill/classification heuristics while preserving `scan_volume` for inspecting underneath canopies when needed.
- **Alternative considered:** Grouping leaves/logs into `[OBSTACLES]` and projecting terrain underneath trees. Rejected for v1 due to classification fragility (e.g., player-built log/leaf hedges vs. natural trees).

### 4. Outdoor `suggested_origin` Fallback Search
- **Decision:** Project ~`10` blocks along the player's horizontal yaw vector `( -sin(yaw), cos(yaw) )` rounded to `(targetX, targetZ)`. If that column's surface is valid solid ground (not air/out-of-window, not liquid/water/lava, not leaves), place `suggested_origin = (targetX, surfaceY + 1, targetZ)`. Otherwise, scan concentric square rings `r = 1..3` around `(targetX, targetZ)` (ordered by Euclidean distance squared, then deterministic tie-break) for the nearest solid non-liquid, non-leaf column; if none exists, fall back to the original target column.
- **Rationale:** Prevents suggesting a build anchor floating on a treetop or submerged in a pond when the player happens to glance across one, while keeping the search strictly bounded (`<= 49` columns already scanned in the grid).

### 5. `scan_volume` Optional Normalized Blockstates (`include_states = false` Default)
- **Decision:** Keep `scan_volume` centered on numeric `[PALETTE]` + non-empty Y-slices + X-axis RLE (`count*paletteId`). When `include_states = true`, serialize blockstate properties in sorted key order `[k=v,...]`, excluding properties whose value matches benign/non-geometric defaults or noise (`waterlogged=false`, `powered=false`, `lit=false`, `distance`, `persistent`, `stage`, `age`, `snowy=false`, `in_wall=false`, `attached=false`). If all properties of a blockstate are stripped, omit `[]` entirely.
- **Rationale:** Keeping `include_states = false` by default minimizes tokens when checking rough clearances or simple walls, while enabling `include_states = true` exposes stair/door/slab/log orientation without exploding the palette on leaf `distance` or `waterlogged=false` noise.

### 6. Documentation Synchronization
- **Decision:** Update [docs/spatial-context.md](../../docs/spatial-context.md) and [docs/mcp-tools.md](../../docs/mcp-tools.md) alongside implementation so repository docs match the implemented v1 contract (`radius=12/24`, non-RLE aligned grid, no `[OBSTACLES]`, `scan_volume.include_states`).

## Risks / Trade-offs

- **[Risk] Large `radius = 24` calls (`2,401` cells) consume ~3,000+ LLM tokens** -> **Mitigation:** Default `radius = 12` (`625` cells) keeps routine calls compact (~700–1,100 tokens), reserving `radius = 24` for explicit wide-area surveys.
- **[Risk] Dense forests or >65 distinct block IDs in one grid exhaust single-character ASCII symbols** -> **Mitigation:** Use a deterministic pool of ~68 unambiguous printable ASCII characters (letters `a..z`, `A..Z`, plus safe punctuation `~`, `.`, `,`, `:`, `;`, `#`, `@`, `%`, `&`, `*`, `!`, `=`, `/`, `^`, `_`, `|`, explicitly excluding digits `0..9`, `+`, `-`, `?`, and whitespace). If an extreme modded scene ever exceeds the pool, fall back to a dedicated overflow marker documented in `[SURFACE_LEGEND]`.
- **[Risk] Vertical scan window `[playerY - 16, playerY + 24]` misses tall cliffs or deep ravines** -> **Mitigation:** Columns without a surface in the window emit a deterministic `?` marker rather than failing or clamping inaccurately; `scan_volume` covers arbitrary vertical slices when needed.
- **[Risk] Pass-through block list maintenance across Minecraft `26.3` blocks** -> **Mitigation:** In the client adapter, classify pass-through ground clutter via non-colliding/replaceable checks while explicitly preserving liquids (`water`, `lava`) and foliage/structural surfaces (`leaves`, logs, roofs). Defining the predicate cleanly makes it resilient across `26.x` block additions.
