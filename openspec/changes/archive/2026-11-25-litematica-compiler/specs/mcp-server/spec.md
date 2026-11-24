# Spec Delta

## ADDED Requirements

### Requirement: Blueprint compilation MCP tool
The MCP server SHALL register `blueprint_compile` accepting required `blueprint_id`, optional boolean `activate` (default `true`), and optional 3-integer `origin_override`, compiling the blueprint to `.litematic`, optionally activating/replacing its Litematica placement, and returning `blueprint_id`, `schematic_path`, `activated`, `replaced_existing_placement`, `world_origin`, `enclosing_size`, `total_blocks`, and optional `survival_check`.

#### Scenario: Compiling and activating a blueprint over MCP
- **WHEN** an MCP client calls `blueprint_compile` with `{"blueprint_id": "oak_cottage", "activate": true}` while a world is loaded
- **THEN** the server returns `isError = false` with `schematic_path`, `activated = true`, `world_origin`, `enclosing_size`, and `total_blocks`

### Requirement: Blueprint placement clearance MCP tool
The MCP server SHALL register `blueprint_clear_placement` accepting required `blueprint_id` and optional boolean `delete_file` (default `false`), removing any active Litematica placement for `blueprint_id`, optionally deleting its `.litematic` file, and returning `blueprint_id`, `cleared`, and `deleted_file`.

#### Scenario: Clearing an active placement and deleting its file over MCP
- **WHEN** an MCP client calls `blueprint_clear_placement` with `{"blueprint_id": "oak_cottage", "delete_file": true}` while a world is loaded
- **THEN** the server returns `isError = false` with `cleared = true` and `deleted_file = true`

## MODIFIED Requirements

### Requirement: Unified structured error envelope
All MCP tool errors SHALL return `isError = true` with a single JSON text payload `{"error": {"code": "<CODE>", "message": "<MESSAGE>"}}`. Codes SHALL map to `NO_WORLD_LOADED`, `INVALID_RADIUS`, `INVALID_BOUNDS`, `VOLUME_TOO_LARGE`, `INVALID_ARGUMENTS`, `CLIENT_TIMEOUT`, `BLUEPRINT_EXISTS`, `BLUEPRINT_NOT_FOUND`, `OP_NOT_FOUND`, `INVALID_BLOCK_ID`, `LITEMATICA_UNAVAILABLE`, and `INTERNAL_ERROR`.

#### Scenario: Missing required scan_volume argument
- **WHEN** an MCP client calls `scan_volume` without `max` or with `"min": [1, 2]`
- **THEN** the server returns `isError = true` whose text parses to JSON with `error.code = "INVALID_ARGUMENTS"` and a descriptive `error.message`

#### Scenario: World scanner guard maps to structured JSON error
- **WHEN** an MCP client calls `get_build_context` while the player is on the title screen
- **THEN** the server returns `isError = true` whose text parses to JSON with `error.code = "NO_WORLD_LOADED"` and a descriptive `error.message`

#### Scenario: Main-thread dispatch timeout maps to CLIENT_TIMEOUT
- **WHEN** executing `get_build_context` or `scan_volume` on the client main thread exceeds the configured dispatch timeout
- **THEN** the server returns `isError = true` whose text parses to JSON with `error.code = "CLIENT_TIMEOUT"` and a descriptive `error.message`

#### Scenario: Unknown blueprint or op maps to structured JSON error
- **WHEN** an MCP client calls `blueprint_update_op` with an unknown `blueprint_id` or missing `op_id`
- **THEN** the server returns `isError = true` whose text parses to JSON with `error.code = "BLUEPRINT_NOT_FOUND"` or `"OP_NOT_FOUND"` respectively

#### Scenario: Invalid block ID in primitive maps to INVALID_BLOCK_ID
- **WHEN** an MCP client calls `blueprint_add_ops` with an unknown block identifier or invalid blockstate property
- **THEN** the server returns `isError = true` whose text parses to JSON with `error.code = "INVALID_BLOCK_ID"` and a descriptive `error.message`

#### Scenario: Litematica placement adapter error maps to LITEMATICA_UNAVAILABLE
- **WHEN** `blueprint_compile` or `blueprint_clear_placement` fails inside the Litematica runtime adapter
- **THEN** the server returns `isError = true` whose text parses to JSON with `error.code = "LITEMATICA_UNAVAILABLE"` and a descriptive `error.message`
