# Spec Delta: mcp-server

## MODIFIED Requirements

### Requirement: Spatial context tool exposure for get_build_context
The MCP server SHALL register `get_build_context` accepting an optional integer `radius` (default `12`) and returning the compact `[PLAYER]`, optional `[INVENTORY_MATERIALS]` (included only when player inventory tracking is enabled), `[SURFACE_LEGEND]`, and `[TERRAIN_GRID]` text payload for the loaded player and world.

#### Scenario: Calling get_build_context with custom radius
- **WHEN** a connected MCP client calls `tools/call` for `get_build_context` with `{"radius": 8}` while a world is loaded and inventory tracking is disabled (the default)
- **THEN** the server returns `isError = false` with the compact `17x17` text payload containing `[PLAYER]`, `[SURFACE_LEGEND]`, and `[TERRAIN_GRID]` without `[INVENTORY_MATERIALS]`

#### Scenario: Calling get_build_context when player has enabled inventory tracking
- **WHEN** a connected MCP client calls `tools/call` for `get_build_context` while a world is loaded and the player has enabled inventory tracking
- **THEN** the server returns `isError = false` including `[INVENTORY_MATERIALS]` alongside `[PLAYER]`, `[SURFACE_LEGEND]`, and `[TERRAIN_GRID]`

### Requirement: MCP initialization instructions and primitive schema guidance
The MCP server SHALL include concise `instructions` in its initialization metadata describing the recommended inspect-draft-compile workflow, anchor-relative coordinate convention (`+Y` up, `+Z` forward, `+X` right), and player-controlled opt-in inventory reporting (omitted by default), and SHALL advertise detailed parameter guidance for all 11 primitive types in `blueprint_add_ops` and `blueprint_update_op` schemas.

#### Scenario: Initialize response includes workflow instructions
- **WHEN** an MCP client completes the `initialize` handshake against `/mcp`
- **THEN** the server result includes non-empty `instructions` documenting `get_build_context`, stateful blueprint ops, local-to-world coordinate orientation, and that inventory materials are omitted unless enabled by the player

#### Scenario: Tool listing includes primitive parameter documentation
- **WHEN** an MCP client sends `tools/list`
- **THEN** the `blueprint_add_ops` and `blueprint_update_op` input schemas describe the parameter structure and required fields for all 11 supported primitive `type` variants
