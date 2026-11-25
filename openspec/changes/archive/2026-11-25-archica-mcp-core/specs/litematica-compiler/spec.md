# litematica-compiler Delta Specification

## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Client-thread-safe active placement inspection
Querying active `archica:` placements for `blueprint_list` or `/archica status` SHALL read Litematica placement manager state on the Minecraft client thread when a world is loaded, and SHALL safely return empty/inactive status without throwing or accessing client managers off-thread when no world is loaded.

#### Scenario: Listing blueprints inspects Litematica placements on client thread
- **WHEN** `blueprint_list` is invoked over MCP while a world is loaded and `archica:oak_cottage` is active
- **THEN** active placement status is queried via the client-thread dispatcher and `oak_cottage` reports `has_active_placement = true`

#### Scenario: Listing blueprints on title screen avoids main-thread world error
- **WHEN** `blueprint_list` is invoked while no world is loaded
- **THEN** active placement inspection safely reports `has_active_placement = false` for in-memory drafts instead of failing with `NO_WORLD_LOADED`
