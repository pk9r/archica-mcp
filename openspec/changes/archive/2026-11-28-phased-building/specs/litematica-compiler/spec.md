# Spec Delta

## MODIFIED Requirements

### Requirement: Standard GZIP-compressed .litematic v7 NBT encoding
The compiler SHALL write a GZIP-compressed `.litematic` NBT compound (`Version = 7`, `SubVersion = 1`, `MinecraftDataVersion`, `Metadata`, `Regions`) with `minecraft:air` at palette index `0` and `BlockStates` packed in `y * sizeX * sizeZ + z * sizeX + x` order at `max(2, ceil(log2(paletteSize)))` bits per entry. Unphased blueprints SHALL emit one subregion (`RegionCount = 1`) at `[0, 0, 0]`; phased blueprints SHALL emit one non-empty subregion per active phase (`RegionCount = N`) positioned relative to `worldBounds.min`.

#### Scenario: Bit-packed BlockStates and Metadata match Litematica v7 layout
- **WHEN** a blueprint with at least `5` distinct blockstates (requiring `3` bits per entry so indices straddle 64-bit `long` boundaries) is compiled
- **THEN** decoding the GZIP NBT file yields `Version = 7`, `SubVersion = 1`, `Metadata.TotalBlocks`, `Metadata.TotalVolume`, `Metadata.EnclosingSize`, `BlockStatePalette[0].Name = "minecraft:air"`, and every decoded `(x, y, z)` voxel matches the rotated world-oriented grid

#### Scenario: Multi-region .litematic compilation for phased blueprints
- **WHEN** a blueprint with 3 non-empty semantic phases (`foundation`, `walls`, `roof`) is compiled
- **THEN** the compiled `.litematic` reports `Metadata.RegionCount = 3`, `Regions` contains 3 disjoint non-empty subregions whose relative `Position` offsets and `Size` reconstruct the full rotated world grid, and `Metadata.TotalBlocks` equals the sum of non-air blocks across all 3 subregions

## ADDED Requirements

### Requirement: Subregion visibility control for phased view modes
When activating or updating a phased `archica:<blueprint_id>` Litematica placement, the bridge SHALL configure each subregion's enabled visibility state according to the active phase and view mode (`ISOLATED` enables only the active phase subregion `k`; `CUMULATIVE` enables subregions `1..k`; `ALL` enables all subregions) and trigger placement render refresh on the Minecraft client thread without rewriting the `.litematic` file on disk.

#### Scenario: Activating phased schematic defaults to phase 1 visibility
- **WHEN** a 3-phase schematic is compiled and activated for the first time
- **THEN** the `archica:<blueprint_id>` placement has subregion `1` enabled and subregions `2` and `3` disabled

#### Scenario: Switching phase and view mode updates subregion visibility in place
- **WHEN** a player switches an active 3-phase placement to phase `2` and toggles view mode from `ISOLATED` to `CUMULATIVE`
- **THEN** subregions `1` and `2` become enabled, subregion `3` stays disabled, Litematica refreshes rendered hologram chunks, and no `.litematic` disk rewrite occurs
