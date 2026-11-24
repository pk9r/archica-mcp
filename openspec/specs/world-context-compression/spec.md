# world-context-compression Specification

## Purpose

Defines the `world-context-compression` capability for summarizing a Minecraft player's outdoor surroundings into a token-efficient 2D spatial/inventory payload (`get_build_context`) and inspecting bounded 3D regions via RLE voxel slices (`scan_volume`).

## Requirements

### Requirement: Outdoor player-radius surface scan
The system SHALL provide `get_build_context` scanning a `(2 * radius + 1)` square XZ grid centered on `(playerX, playerZ)` (`radius` default `12`, range `4..24`) top-down over `[playerY - 16, playerY + 24]` clamped to world bounds. It SHALL record the highest non-air, non-clutter surface block (including leaves, logs, liquids, and roofs) and omit `[OBSTACLES]` sections.

#### Scenario: Default outdoor scan on open terrain
- **WHEN** `get_build_context` is invoked with no arguments while the player is in a loaded world
- **THEN** the scanner uses `radius = 12` (a `25x25` XZ grid) over `[playerY - 16, playerY + 24]` and returns `[PLAYER]`, `[INVENTORY_MATERIALS]`, `[SURFACE_LEGEND]`, and `[TERRAIN_GRID]` without an `[OBSTACLES]` section

#### Scenario: Canopy and pass-through clutter handling
- **WHEN** a column contains a flower on top of `minecraft:grass_block` and an adjacent column contains an oak tree canopy (`minecraft:oak_leaves`) above `minecraft:grass_block`
- **THEN** the flower column records `minecraft:grass_block` as its surface while the tree column records `minecraft:oak_leaves` at the canopy elevation

#### Scenario: Radius outside allowed bounds
- **WHEN** `get_build_context` is invoked with `radius` less than `4` or greater than `24`
- **THEN** the operation fails with a structured validation error and does not scan the world

### Requirement: Aligned non-RLE terrain grid with 1-to-1 surface legend
`[TERRAIN_GRID]` SHALL emit an aligned, non-RLE matrix ordered by ascending `z` rows and `x` columns, formatting each cell as `<symbol><deltaY>` (`surfaceY - playerY` with explicit sign or `0`) or an out-of-window marker when no surface exists in `[playerY - 16, playerY + 24]`. `[SURFACE_LEGEND]` SHALL map each distinct surface block ID 1-to-1 to a unique single-character symbol.

#### Scenario: Aligned coordinate columns and signed elevation offsets
- **WHEN** the grid contains grass blocks at `playerY`, cobblestone one block above `playerY`, and water one block below `playerY`
- **THEN** `[TERRAIN_GRID]` emits space-separated aligned cells per `z` row using consistent signed offsets (such as `.0`, `#+1`, `~-1`) with no RLE multipliers

#### Scenario: Unique symbol assignment across multiple stone or wood blocks
- **WHEN** both `minecraft:stone` and `minecraft:cobblestone` appear as surface blocks in the scanned grid
- **THEN** `[SURFACE_LEGEND]` assigns two different single-character symbols so each block ID can be unambiguously identified from any grid cell

#### Scenario: Column with no surface inside vertical scan window
- **WHEN** a column within the radius contains only air and pass-through blocks across `[playerY - 16, playerY + 24]`
- **THEN** that cell renders the dedicated empty/out-of-window token without crashing or shifting column alignment

### Requirement: Outdoor suggested build origin
The system SHALL compute `suggested_origin` ~`10` blocks ahead along the player's horizontal `yaw` and report `suggested_origin`, `ground_y`, and `surface_block` in `[PLAYER]`. On solid non-liquid, non-leaf ground it SHALL snap to `(targetX, surfaceY + 1, targetZ)`; otherwise it SHALL search within Chebyshev radius `<= 3` for the nearest solid non-liquid, non-leaf column before falling back to the target column.

#### Scenario: Clear ground ahead of player
- **WHEN** the column 10 blocks ahead along the player's look vector has `minecraft:grass_block` at `y = 64`
- **THEN** `[PLAYER]` reports `suggested_origin=(x, 65, z)`, `ground_y=64`, and `surface_block=minecraft:grass_block`

#### Scenario: Look target lands on water or tree canopy
- **WHEN** the column 10 blocks ahead is `minecraft:water` or `minecraft:oak_leaves`, and a `minecraft:grass_block` column exists 2 blocks away within the `<= 3` fallback radius
- **THEN** `suggested_origin` snaps above that nearby solid `minecraft:grass_block` column instead of placing the build anchor on water or treetop leaves

### Requirement: Player inventory material summary
The system SHALL aggregate placeable/block items in the player's hotbar and main inventory by block/item identifier, sort them by descending count (breaking ties lexicographically by identifier), and emit them in `[INVENTORY_MATERIALS]` (or `none` if no materials are carried).

#### Scenario: Aggregating stacked materials across slots
- **WHEN** the player carries two stacks of 64 `minecraft:oak_planks` and 32 `minecraft:cobblestone` across hotbar and main inventory
- **THEN** `[INVENTORY_MATERIALS]` lists `oak_planks:128` before `cobblestone:32`

### Requirement: Bounded 3D RLE volume inspection
The system SHALL provide a `scan_volume(min, max, include_states)` operation that inspects an axis-aligned bounding box where `min[i] <= max[i]` for each axis and total volume `(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)` does not exceed `13,824` blocks. It SHALL emit `[VOLUME]` metadata, a numeric `[PALETTE]` with `0=minecraft:air`, omit Y slices consisting solely of `minecraft:air`, and RLE-encode each Z-row across ascending X using `count*paletteId`.

#### Scenario: Empty Y slices omitted and Z-rows RLE compressed
- **WHEN** `scan_volume` scans a `5x3x3` region whose `y=67` layer is entirely `minecraft:air` and whose `y=65` `z=-53` row has three `minecraft:cobblestone` followed by two `minecraft:air`
- **THEN** `y=67` is omitted from the output and `y=65` `z=-53` is encoded as `3*1, 2*0`

#### Scenario: Volume exceeds hard voxel cap
- **WHEN** `scan_volume` is called with a box whose total block count exceeds `13,824` (for example `25x24x24`)
- **THEN** the operation fails with structured error `VOLUME_TOO_LARGE` without scanning the world

#### Scenario: Inverted bounding coordinates
- **WHEN** `scan_volume` is called with `min[i] > max[i]` on any axis
- **THEN** the operation fails with a structured validation error before scanning

### Requirement: Optional compact blockstate serialization in scan_volume
`scan_volume` SHALL accept `include_states` (boolean, default `false`). When `false`, `[PALETTE]` entries SHALL use bare block IDs; when `true`, distinct blockstates SHALL use `<block_id>[prop=val,...]` keeping only normalized geometric/non-default properties and stripping defaults or noise such as `waterlogged=false`, `powered=false`, `distance`, and `persistent`.

#### Scenario: Default state-free palette
- **WHEN** `scan_volume` is called without `include_states` on a roof containing east-facing and west-facing `minecraft:oak_stairs`
- **THEN** both stairs share a single `minecraft:oak_stairs` palette entry

#### Scenario: State-aware compact palette
- **WHEN** `scan_volume` is called with `include_states=true` on east-facing bottom `minecraft:oak_stairs` (`waterlogged=false`) and west-facing bottom `minecraft:oak_stairs` (`waterlogged=false`)
- **THEN** the palette emits distinct entries such as `minecraft:oak_stairs[facing=east,half=bottom]` and `minecraft:oak_stairs[facing=west,half=bottom]` without `waterlogged=false`

### Requirement: Missing world safety guard
Both `get_build_context` and `scan_volume` SHALL verify that a client world and player entity are loaded before reading blocks or inventory, and SHALL return structured error `NO_WORLD_LOADED` if either is unavailable.

#### Scenario: Client is on title screen or disconnecting
- **WHEN** `get_build_context` or `scan_volume` is invoked while `client.world` or `client.player` is null
- **THEN** the operation fails immediately with structured error code `NO_WORLD_LOADED`
