# mcp-server Specification

## Purpose

Defines the `mcp-server` capability for hosting an embedded, loopback-only Streamable HTTP MCP server inside the Minecraft client and exposing the v1 spatial context tools (`get_build_context`, `scan_volume`) with unified structured error payloads.

## Requirements

### Requirement: Loopback-only Streamable HTTP MCP endpoint
The system SHALL expose an embedded MCP server speaking Streamable HTTP at `http://127.0.0.1:<port>/mcp` (default port `25580`). It SHALL bind exclusively to the IPv4 loopback interface `127.0.0.1` and SHALL NOT listen on wildcard or LAN interfaces (`0.0.0.0`).

#### Scenario: Local MCP client connects and lists tools
- **WHEN** a local MCP client initializes a Streamable HTTP session against `http://127.0.0.1:25580/mcp` and sends `tools/list`
- **THEN** the server completes the MCP handshake and advertises `get_build_context` and `scan_volume` with their JSON input schemas

### Requirement: DNS-rebinding and origin protection
The MCP endpoint SHALL enforce DNS-rebinding protection by validating `Host` and `Origin` headers on incoming requests and rejecting requests from non-loopback hosts or origins outside `127.0.0.1`, `localhost`, and `[::1]`.

#### Scenario: Request with external Origin header is rejected
- **WHEN** an HTTP client sends a request to `/mcp` with `Origin: https://evil.example`
- **THEN** the server rejects the request before invoking any MCP tool

### Requirement: Port configuration via JVM system property
The system SHALL resolve the listening port from the `archica.mcp.port` JVM system property (`-Darchica.mcp.port=<port>`), defaulting to `25580` when unset. If the property is non-numeric or outside `1..65535`, the system SHALL log a warning and fall back to default port `25580`.

#### Scenario: Valid custom port override
- **WHEN** the client starts with `-Darchica.mcp.port=25581`
- **THEN** the MCP server binds to `127.0.0.1:25581` and serves `/mcp` on that port

#### Scenario: Invalid custom port falls back to default
- **WHEN** the client starts with `-Darchica.mcp.port=not_a_number` or `-Darchica.mcp.port=70000`
- **THEN** the system logs a warning and falls back to binding on port `25580`

### Requirement: Fail-silent client lifecycle and graceful shutdown
The embedded MCP server SHALL start asynchronously during client initialization and shut down gracefully when the client stops. If binding the port or starting the server fails (for example, because another process already occupies the port), the system SHALL log a warning and allow the Minecraft client to continue running normally without crashing.

#### Scenario: Port conflict on startup fails silently
- **WHEN** another process is already listening on `127.0.0.1:25580` during client initialization
- **THEN** the system catches the startup failure, logs a warning including the endpoint/port, and leaves the Minecraft client running normally

#### Scenario: Client shutdown stops the embedded server
- **WHEN** the Minecraft client triggers its stopping lifecycle event while the MCP server is running
- **THEN** the embedded MCP server stops gracefully and releases its bound socket

### Requirement: Spatial context tool exposure for get_build_context
The MCP server SHALL register `get_build_context` accepting an optional integer `radius` (default `12`) and returning the compact `[PLAYER]`, `[INVENTORY_MATERIALS]`, `[SURFACE_LEGEND]`, and `[TERRAIN_GRID]` text payload for the loaded player and world.

#### Scenario: Calling get_build_context with custom radius
- **WHEN** a connected MCP client calls `tools/call` for `get_build_context` with `{"radius": 8}` while a world is loaded
- **THEN** the server returns `isError = false` with the compact `17x17` text payload generated for the loaded player and world

### Requirement: Spatial context tool exposure for scan_volume
The MCP server SHALL register `scan_volume` accepting required 3-integer arrays `min` and `max` plus optional boolean `include_states` (default `false`), and returning the compact `[VOLUME]`, `[PALETTE]`, and RLE Y-slice text payload.

#### Scenario: Calling scan_volume with defaults
- **WHEN** a connected MCP client calls `tools/call` for `scan_volume` with `{"min": [0, 64, 0], "max": [4, 66, 2]}` while a world is loaded
- **THEN** the server uses `include_states = false` and returns `isError = false` with the RLE-encoded `[VOLUME]` text payload

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
