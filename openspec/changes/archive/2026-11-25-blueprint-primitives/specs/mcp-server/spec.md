# Spec Delta

## ADDED Requirements

### Requirement: Blueprint session creation and listing tools
The MCP server SHALL register `blueprint_create` (required `blueprint_id` matching `^[a-z0-9_-]{1,48}$`, required 3-integer `origin`, optional cardinal `facing` defaulting to `NORTH`, optional boolean `overwrite` defaulting to `false`) and `blueprint_list` (empty arguments returning all active draft sessions in memory and whether each has an active placement).

#### Scenario: Creating and listing a blueprint session over MCP
- **WHEN** an MCP client calls `blueprint_create` with `{"blueprint_id": "oak_cottage", "origin": [124, 65, -52], "facing": "EAST"}` and then calls `blueprint_list`
- **THEN** `blueprint_create` returns `isError = false` with `op_count = 0` and `blueprint_list` includes `oak_cottage` with `has_active_placement = false`

### Requirement: Blueprint operation mutation tools
The MCP server SHALL register `blueprint_add_ops` (required `blueprint_id` and non-empty `ops` array), `blueprint_update_op` (required `blueprint_id`, `op_id`, `op`), `blueprint_remove_op` (required `blueprint_id`, `op_id`), and `blueprint_undo` (required `blueprint_id`, optional integer `count >= 1` defaulting to `1`). Each mutation tool SHALL return updated operation identifiers/counts, local bounding box, `total_blocks`, `materials`, and optional `survival_check`.

#### Scenario: Adding and updating primitives over MCP
- **WHEN** an MCP client calls `blueprint_add_ops` with a `fill_box` operation and then calls `blueprint_update_op` for `op_1`
- **THEN** `blueprint_add_ops` returns `added_op_ids: ["op_1"]` and `blueprint_update_op` returns the recalculated `bounding_box`, `total_blocks`, and `materials`

### Requirement: Blueprint inspection tool
The MCP server SHALL register `blueprint_inspect` (required `blueprint_id`, optional boolean `include_ops` defaulting to `true`) returning the session metadata, optional ordered `ops` list with `op_id`s, local and world bounding boxes, `total_blocks`, `materials`, and optional `survival_check` without writing any file to disk.

#### Scenario: Inspecting a blueprint with include_ops disabled
- **WHEN** an MCP client calls `blueprint_inspect` with `{"blueprint_id": "oak_cottage", "include_ops": false}`
- **THEN** the server returns `isError = false` with bounding box and material counts while omitting the detailed `ops` array

## MODIFIED Requirements

### Requirement: Unified structured error envelope
All MCP tool errors SHALL return `isError = true` with a single JSON text payload `{"error": {"code": "<CODE>", "message": "<MESSAGE>"}}`. Codes SHALL map to `NO_WORLD_LOADED`, `INVALID_RADIUS`, `INVALID_BOUNDS`, `VOLUME_TOO_LARGE`, `INVALID_ARGUMENTS`, `CLIENT_TIMEOUT`, `BLUEPRINT_EXISTS`, `BLUEPRINT_NOT_FOUND`, `OP_NOT_FOUND`, `INVALID_BLOCK_ID`, and `INTERNAL_ERROR`.

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
