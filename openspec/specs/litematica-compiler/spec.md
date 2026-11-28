# litematica-compiler Specification

## Purpose

Defines the `litematica-compiler` capability for transforming stateful blueprint draft sessions into standard GZIP-compressed `.litematic` NBT schematic files and managing live Litematica hologram placements inside the Minecraft client.

## Requirements

### Requirement: World-oriented normalization and empty-blueprint guard
Compiling a blueprint SHALL rasterize its active operations, rotate non-air voxels and directional blockstate properties (`facing`, `axis`) around `(0, 0, 0)` by `blueprint.facing`, and normalize coordinates into a non-negative region `[0..sizeX-1, 0..sizeY-1, 0..sizeZ-1]` anchored at `worldBounds.min` (adjusted by `origin_override` if provided). Empty blueprints (`total_blocks == 0`) SHALL fail with `INVALID_ARGUMENTS` without writing a file.

#### Scenario: Facing rotation and negative local overhang normalize cleanly
- **WHEN** a blueprint anchored at `[100, 64, 100]` with `facing = EAST` contains voxels from local `[-1, 0, 0]` to `[1, 2, 3]`
- **THEN** the compiled schematic region has positive dimensions `sizeX = 4, sizeY = 3, sizeZ = 3`, region `Position = [0, 0, 0]`, rotated blockstates (`facing`/`axis`), and a placement world origin at `worldBounds.min` (`[100, 64, 99]`)

#### Scenario: Empty blueprint rejected before disk write
- **WHEN** compilation is requested for a blueprint whose rasterized grid contains `0` non-air blocks (for example, all blocks carved out by `clear_box`)
- **THEN** compilation fails with `INVALID_ARGUMENTS` and no `.litematic` file is created or overwritten

### Requirement: Standard GZIP-compressed .litematic v7 NBT encoding
The compiler SHALL write a GZIP-compressed `.litematic` NBT compound (`Version = 7`, `SubVersion = 1`, `MinecraftDataVersion`, `Metadata`, `Regions`) with `minecraft:air` at palette index `0` and `BlockStates` packed in `y * sizeX * sizeZ + z * sizeX + x` order at `max(2, ceil(log2(paletteSize)))` bits per entry. Unphased blueprints SHALL emit one subregion (`RegionCount = 1`) at `[0, 0, 0]`; phased blueprints SHALL emit one non-empty subregion per active phase (`RegionCount = N`) positioned relative to `worldBounds.min`.

#### Scenario: Bit-packed BlockStates and Metadata match Litematica v7 layout
- **WHEN** a blueprint with at least `5` distinct blockstates (requiring `3` bits per entry so indices straddle 64-bit `long` boundaries) is compiled
- **THEN** decoding the GZIP NBT file yields `Version = 7`, `SubVersion = 1`, `Metadata.TotalBlocks`, `Metadata.TotalVolume`, `Metadata.EnclosingSize`, `BlockStatePalette[0].Name = "minecraft:air"`, and every decoded `(x, y, z)` voxel matches the rotated world-oriented grid

#### Scenario: Multi-region .litematic compilation for phased blueprints
- **WHEN** a blueprint with 3 non-empty semantic phases (`foundation`, `walls`, `roof`) is compiled
- **THEN** the compiled `.litematic` reports `Metadata.RegionCount = 3`, `Regions` contains 3 disjoint non-empty subregions whose relative `Position` offsets and `Size` reconstruct the full rotated world grid, and `Metadata.TotalBlocks` equals the sum of non-air blocks across all 3 subregions

### Requirement: Atomic file-backed output under Litematica schematics directory
Compiled schematics SHALL be written atomically (temporary file followed by atomic/replace move) to `<schematicsBaseDir>/archica/<blueprint_id>.litematic`, creating parent directories when needed and returning the relative `schematic_path` (`schematics/archica/<blueprint_id>.litematic` or configured base relative path). Disk compilation with `activate = false` SHALL succeed even when no Minecraft world is loaded.

#### Scenario: Compile to disk without activating while on title screen
- **WHEN** `blueprint_compile` is called with `activate = false` while no Minecraft world is loaded
- **THEN** the `.litematic` file is atomically written to `<schematicsBaseDir>/archica/<blueprint_id>.litematic` and the response succeeds with `activated = false`

### Requirement: Litematica placement activation, replacement, and clearance
When `activate = true`, the system SHALL verify a loaded world (`NO_WORLD_LOADED` otherwise), evict any stale cached schematic for that path, load the `.litematic` file, remove any existing placement tagged `archica:<blueprint_id>`, and register a new enabled, visible placement at `world_origin`. Clearing a placement SHALL remove the active `archica:<blueprint_id>` placement, evict matching cached schematics when `delete_file = true`, and optionally delete the `.litematic` file.

#### Scenario: Re-compiling replaces prior placement without duplicates
- **WHEN** `blueprint_compile` is called twice for `oak_cottage` with `activate = true` after modifying an operation
- **THEN** the previous `archica:oak_cottage` placement is removed before adding the new placement, `replaced_existing_placement` is `true`, and only one placement for `oak_cottage` remains active in Litematica

#### Scenario: Clearing placement removes hologram and preserves file by default
- **WHEN** `blueprint_clear_placement` is called for `oak_cottage` with `delete_file = false` (or omitted)
- **THEN** the active `archica:oak_cottage` placement is removed from Litematica while the `.litematic` file on disk remains intact

#### Scenario: Clearing placement with delete_file evicts cached schematic before deletion
- **WHEN** `blueprint_clear_placement` is called for `oak_cottage` with `delete_file = true`
- **THEN** the active `archica:oak_cottage` placement is removed, any cached `SchematicHolder` entry pointing at `oak_cottage.litematic` is evicted, and the file is deleted from disk

#### Scenario: Adapter failure reports LITEMATICA_UNAVAILABLE after disk write
- **WHEN** `blueprint_compile` writes the `.litematic` file to disk with `activate = true` and the Litematica runtime adapter throws an error while loading or placing the schematic
- **THEN** the operation fails with `LITEMATICA_UNAVAILABLE` and the error message includes the written `schematic_path`

### Requirement: Client-thread-safe active placement inspection
Querying active `archica:` placements for `blueprint_list` or `/archica status` SHALL read Litematica placement manager state on the Minecraft client thread when a world is loaded, and SHALL safely return empty/inactive status without throwing or accessing client managers off-thread when no world is loaded.

#### Scenario: Listing blueprints inspects Litematica placements on client thread
- **WHEN** `blueprint_list` is invoked over MCP while a world is loaded and `archica:oak_cottage` is active
- **THEN** active placement status is queried via the client-thread dispatcher and `oak_cottage` reports `has_active_placement = true`

#### Scenario: Listing blueprints on title screen avoids main-thread world error
- **WHEN** `blueprint_list` is invoked while no world is loaded
- **THEN** active placement inspection safely reports `has_active_placement = false` for in-memory drafts instead of failing with `NO_WORLD_LOADED`

### Requirement: Subregion visibility control for phased view modes
When activating or updating a phased `archica:<blueprint_id>` Litematica placement, the bridge SHALL configure each subregion's enabled visibility state according to the active phase and view mode (`ISOLATED` enables only the active phase subregion `k`; `CUMULATIVE` enables subregions `1..k`; `ALL` enables all subregions) and trigger placement render refresh on the Minecraft client thread without rewriting the `.litematic` file on disk.

#### Scenario: Activating phased schematic defaults to phase 1 visibility
- **WHEN** a 3-phase schematic is compiled and activated for the first time
- **THEN** the `archica:<blueprint_id>` placement has subregion `1` enabled and subregions `2` and `3` disabled

#### Scenario: Switching phase and view mode updates subregion visibility in place
- **WHEN** a player switches an active 3-phase placement to phase `2` and toggles view mode from `ISOLATED` to `CUMULATIVE`
- **THEN** subregions `1` and `2` become enabled, subregion `3` stays disabled, Litematica refreshes rendered hologram chunks, and no `.litematic` disk rewrite occurs
