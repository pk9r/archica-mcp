# Spec Delta: world-context-compression

## MODIFIED Requirements

### Requirement: Outdoor player-radius surface scan
The system SHALL provide `get_build_context` scanning a `(2 * radius + 1)` square XZ grid centered on `(playerX, playerZ)` (`radius` default `12`, range `4..24`) top-down over `[playerY - 16, playerY + 24]` clamped to world bounds. It SHALL record the highest non-air, non-clutter surface block (including leaves, logs, liquids, and roofs), omit `[OBSTACLES]` sections, and omit `[INVENTORY_MATERIALS]` unless player inventory tracking is enabled.

#### Scenario: Default outdoor scan on open terrain
- **WHEN** `get_build_context` is invoked with no arguments while the player is in a loaded world and inventory tracking is disabled (the default)
- **THEN** the scanner uses `radius = 12` (a `25x25` XZ grid) over `[playerY - 16, playerY + 24]`, skips reading player inventory, and returns `[PLAYER]`, `[SURFACE_LEGEND]`, and `[TERRAIN_GRID]` without `[INVENTORY_MATERIALS]` or `[OBSTACLES]`

#### Scenario: Outdoor scan with inventory tracking enabled by player
- **WHEN** `get_build_context` is invoked while the player has enabled inventory tracking
- **THEN** the scanner returns `[PLAYER]`, `[INVENTORY_MATERIALS]`, `[SURFACE_LEGEND]`, and `[TERRAIN_GRID]` without `[OBSTACLES]`

#### Scenario: Canopy and pass-through clutter handling
- **WHEN** a column contains a flower on top of `minecraft:grass_block` and an adjacent column contains an oak tree canopy (`minecraft:oak_leaves`) above `minecraft:grass_block`
- **THEN** the flower column records `minecraft:grass_block` as its surface while the tree column records `minecraft:oak_leaves` at the canopy elevation

#### Scenario: Radius outside allowed bounds
- **WHEN** `get_build_context` is invoked with `radius` less than `4` or greater than `24`
- **THEN** the operation fails with a structured validation error and does not scan the world

### Requirement: Player inventory material summary
When player inventory tracking is enabled, the system SHALL aggregate placeable/block items in the player's hotbar and main inventory by block/item identifier, sort them by descending count (breaking ties lexicographically by identifier), and emit them in `[INVENTORY_MATERIALS]` (or `none` if no materials are carried). When inventory tracking is disabled (the default), the system SHALL NOT query player inventory and SHALL omit `[INVENTORY_MATERIALS]` from the serialized context payload.

#### Scenario: Inventory section omitted when tracking is disabled
- **WHEN** `get_build_context` runs with inventory tracking disabled even though the player carries `minecraft:oak_planks` in survival mode
- **THEN** `[INVENTORY_MATERIALS]` is omitted from the formatted payload and the inventory source is not queried

#### Scenario: Aggregating stacked materials across slots
- **WHEN** inventory tracking is enabled and the player carries two stacks of 64 `minecraft:oak_planks` and 32 `minecraft:cobblestone` across hotbar and main inventory
- **THEN** `[INVENTORY_MATERIALS]` lists `oak_planks:128` before `cobblestone:32`
