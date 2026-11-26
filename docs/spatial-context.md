# Spatial Context Compression

> **Status:** Implemented v1 Specification — defines how in-game outdoor surroundings are extracted and compressed for LLM consumption.

Raw 3D Minecraft chunks are far too large for LLM context windows: a single `25 x 25 x 41` outdoor scan window contains over `25,000` blocks, most of which are `minecraft:air` or underground stone. Archica MCP prioritizes an **outdoor player-radius 2D terrain & surface summary** (`get_build_context`) that fits cleanly in ~700–1,100 tokens at default radius, paired with an on-demand 3D drill-down tool (`scan_volume`) when the agent needs vertical cross-sections or blockstate orientations of existing structures. *(Enclosed indoor spaces and underground caves are outside v1 scope.)*

---

## 1. Primary Context Scope: Outdoor Player Radius

By default, `get_build_context` scans a square region centered on the player's `(x, z)` block position:

- **Default radius**: `12` blocks (`25 x 25` XZ columns, configurable within `4..24` blocks).
- **Vertical surface search**: For each `(x, z)` column, the scanner searches downward across `[playerY - 16, playerY + 24]` (clamped to world height limits) and records the highest non-air, non-clutter block. Tree canopies (`*_leaves`), trunks (`*_log`), water, and roofs are captured directly as surface blocks, while ground clutter (grass blades, flowers, 1-layer snow, torches, carpets, signs) is skipped.
- **Suggested Anchor**: Projects ~`10` blocks ahead of the player's horizontal look direction (`yaw`) and snaps `suggested_origin` above ground (`ground_y + 1`). If that target lands on water, leaves, or an out-of-window column, the solver checks within Chebyshev radius `<= 3` for the nearest solid non-liquid, non-leaf ground block before falling back.

---

## 2. Concise Context Sections

`get_build_context` returns three core sections by default (and an optional fourth inventory section when enabled by the player):

### A. Player Pose & Anchor
- `pos`: `(x, y, z)` integer block coordinates of player's feet.
- `facing`: Cardinal direction (`NORTH`, `SOUTH`, `EAST`, `WEST`) + yaw/pitch degrees.
- `biome` and `dimension`: e.g., `minecraft:plains` in `minecraft:overworld`.
- `game_mode`: `survival`, `creative`, `adventure`, or `spectator`.
- `suggested_origin`: Recommended `(x, y, z)` build anchor in front of the player, paired with `ground_y` and `surface_block`.

### B. Material Availability (Opt-In Hotbar & Inventory)
- Disabled by default (`off` on every Minecraft client startup) so the AI designs freely without being biased by pocket items, and `[INVENTORY_MATERIALS]` is omitted from the payload.
- When the player explicitly runs `/archica inventory on` in-game, `get_build_context` aggregates block items currently carried in the player's hotbar and main inventory, sorted by count descending and tie-broken lexicographically (e.g., `oak_planks:128, cobblestone:32`, or `none` if empty).

### C. Aligned Non-RLE Heightmap + 1-to-1 Surface Legend
Each `(x, z)` column in the `(2 * radius + 1) x (2 * radius + 1)` grid is encoded as `<symbol><deltaY>` (or `?` if no surface exists within `[playerY - 16, playerY + 24]`):
- **`<symbol>`**: 1-character token mapped **strictly 1-to-1** in `[SURFACE_LEGEND]` to a single surface block ID.
  - High-frequency outdoor blocks use intuitive defaults (`.` = `minecraft:grass_block`, `~` = `minecraft:water`, `,` = `minecraft:dirt`, `:` = `minecraft:sand`, `#` = `minecraft:cobblestone`, `S` = `minecraft:stone`, `T` = `minecraft:oak_log`, `L` = `minecraft:oak_leaves`); additional blocks receive deterministic characters from `a..z`, `A..Z`, and safe punctuation (excluding digits, `+`, `-`, `?`, and whitespace).
- **`<deltaY>`**: Explicitly signed elevation offset relative to `player.y` (`0` = same Y as player feet, `+1` = 1 block higher, `-1` = 1 block lower).
- Cells are space-separated and column-padded **without RLE** so the LLM can read exact `(x, z)` coordinates directly without summing run lengths.

---

## 3. End-to-End Sample Payload (`get_build_context`)

Default output (inventory tracking `off`):

```text
[PLAYER]
pos=(124, 65, -40) facing=NORTH(yaw=-178, pitch=8) biome=minecraft:plains dim=minecraft:overworld mode=survival
suggested_origin=(124, 65, -50) ground_y=64 surface_block=minecraft:grass_block

[SURFACE_LEGEND]
.=minecraft:grass_block  ~=minecraft:water  #=minecraft:cobblestone  L=minecraft:oak_leaves

[TERRAIN_GRID origin=(112, -52) size=25x25 ref_y=65]
(Showing excerpt of rows; actual payload includes all 25 rows)
z-52: .0  .0  .0  ~-1 ~-1 .0  .0  .+1 .+1 ...
z-51: .0  .0  ~-1 ~-1 .0  .0  .0  .0  .+1 ...
z-50: .0  .0  .0  .0  .0  L+4 L+5 L+4 .0  ...
z-49: .0  .0  .0  .0  #+1 #+1 #+1 .0  .0  ...
```

When `/archica inventory on` is active, `[INVENTORY_MATERIALS]` is included between `[PLAYER]` and `[SURFACE_LEGEND]`:

```text
[INVENTORY_MATERIALS]
oak_planks:192, cobblestone:128, spruce_log:48, oak_stairs:32, glass_pane:24, oak_door:2
```

---

## 4. On-Demand 3D Drill-Down (`scan_volume`)

When the agent needs exact 3D geometry (for example, extending an existing house, matching roof stair orientation, or inspecting beneath a tree canopy), it calls `scan_volume(min, max, include_states)` on a bounded box (hard cap `13,824` blocks, e.g. `24 x 24 x 24`).

`scan_volume` compresses output by:
1. **Building a local numeric palette** (`0` is always `minecraft:air`).
2. **Optional compact blockstates (`include_states`, default `false`)**: When `false`, palette entries use bare block IDs; when `true`, distinct blockstates include sorted geometric/non-default properties (`minecraft:oak_stairs[facing=east,half=bottom]`) while stripping noisy defaults (`waterlogged=false`, `powered=false`, `distance`, `persistent`, `stage`, `age`).
3. **Emitting only non-empty Y-slices** (omitting layers that are 100% air).
4. **Run-length encoding (RLE)** each Z-row across ascending X using `count*paletteId` (e.g., `3*1, 2*0`).

### Example `scan_volume` Output (`include_states=false`)
```text
[VOLUME min=(116, 65, -54) max=(120, 67, -52) size=5x3x3]
[PALETTE]
0=minecraft:air  1=minecraft:cobblestone  2=minecraft:oak_planks

y=65:
  z-54: 5*0
  z-53: 3*1, 2*0
  z-52: 5*0

y=66:
  z-54: 5*0
  z-53: 1*1, 1*2, 1*1, 2*0
  z-52: 5*0
```

---

## 5. Related Documents

- [Architecture & Litematica Integration](architecture.md)
- [Stateful Blueprints & Building Primitives](blueprint-primitives.md)
- [MCP Tools Reference](mcp-tools.md)
