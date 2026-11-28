# Spec Delta

## MODIFIED Requirements

### Requirement: Blueprint operation mutation tools
The MCP server SHALL register `blueprint_add_ops` (with optional `phase` metadata), `blueprint_update_op` (with optional `phase` override), `blueprint_remove_op`, and `blueprint_undo`. Each mutation tool SHALL return updated operation identifiers/counts, local bounding box, `total_blocks`, `materials`, optional `survival_check`, and per-phase summaries when phases exist.

#### Scenario: Adding and updating primitives over MCP
- **WHEN** an MCP client calls `blueprint_add_ops` with a `fill_box` operation and then calls `blueprint_update_op` for `op_1`
- **THEN** `blueprint_add_ops` returns `added_op_ids: ["op_1"]` and `blueprint_update_op` returns the recalculated `bounding_box`, `total_blocks`, and `materials`

#### Scenario: Adding and updating primitives with phase tags over MCP
- **WHEN** an MCP client calls `blueprint_add_ops` with `phase: {"id": "frame", "title": "Structural Frame"}` and a `fill_box` operation, and then updates `op_1` via `blueprint_update_op`
- **THEN** `blueprint_add_ops` returns `added_op_ids: ["op_1"]` along with the `phases` summary, and `blueprint_update_op` preserves `op_1`'s `frame` phase and recalculates `bounding_box`, `total_blocks`, `materials`, and phase counts

### Requirement: Blueprint inspection tool
The MCP server SHALL register `blueprint_inspect` (`blueprint_id`, optional `include_ops` defaulting to `true`) returning session metadata, optional ordered `ops` (with `phase_id` when phased), local/world bounding boxes, `total_blocks`, `materials`, optional `survival_check`, and—for phased blueprints—ordered `phases` breakdowns plus `active_phase_id` and `view_mode` without writing to disk.

#### Scenario: Inspecting a blueprint with include_ops disabled
- **WHEN** an MCP client calls `blueprint_inspect` with `{"blueprint_id": "oak_cottage", "include_ops": false}`
- **THEN** the server returns `isError = false` with bounding box and material counts while omitting the detailed `ops` array

#### Scenario: Inspecting a phased blueprint returns per-phase breakdown
- **WHEN** an MCP client calls `blueprint_inspect` on a 2-phase blueprint with `include_ops = false`
- **THEN** the server returns `isError = false` with overall `total_blocks` and `materials`, omits the `ops` array, and includes the ordered `phases` array with each phase's title, block count, materials, and optional phase-scoped `survival_check`

### Requirement: Blueprint compilation MCP tool
The MCP server SHALL register `blueprint_compile` (`blueprint_id`, optional `activate` defaulting to `true`, optional `origin_override`), compiling to `.litematic` (multi-region when phased), optionally activating/replacing the Litematica placement, and returning `blueprint_id`, `schematic_path`, `activated`, `replaced_existing_placement`, `world_origin`, `enclosing_size`, `total_blocks`, optional `survival_check`, and active phase metadata when phased.

#### Scenario: Compiling and activating a blueprint over MCP
- **WHEN** an MCP client calls `blueprint_compile` with `{"blueprint_id": "oak_cottage", "activate": true}` while a world is loaded
- **THEN** the server returns `isError = false` with `schematic_path`, `activated = true`, `world_origin`, `enclosing_size`, and `total_blocks`

#### Scenario: Compiling a phased blueprint returns active phase metadata
- **WHEN** an MCP client calls `blueprint_compile` with `{"blueprint_id": "oak_cottage", "activate": true}` on a 3-phase blueprint
- **THEN** the server returns `isError = false` with `phase_count = 3`, `active_phase_id` pointing to phase `1`, and `view_mode = "ISOLATED"`

### Requirement: MCP initialization instructions and primitive schema guidance
The MCP server SHALL include `instructions` describing the inspect-draft-compile workflow, anchor-relative coordinates (`+Y` up, `+Z` forward, `+X` right), optional semantic `phase` grouping, and opt-in inventory reporting, and SHALL document all 11 primitive types plus optional `phase` fields in `blueprint_add_ops` and `blueprint_update_op` input schemas.

#### Scenario: Initialize response includes workflow instructions
- **WHEN** an MCP client completes the `initialize` handshake against `/mcp`
- **THEN** the server result includes non-empty `instructions` documenting `get_build_context`, stateful blueprint ops, local-to-world coordinate orientation, and that inventory materials are omitted unless enabled by the player

#### Scenario: Tool listing includes primitive parameter documentation
- **WHEN** an MCP client sends `tools/list`
- **THEN** the `blueprint_add_ops` and `blueprint_update_op` input schemas describe the parameter structure and required fields for all 11 supported primitive `type` variants

#### Scenario: Tool listing documents phase parameter on mutation tools
- **WHEN** an MCP client sends `tools/list`
- **THEN** the `blueprint_add_ops` and `blueprint_update_op` input schemas document the optional `phase` metadata field alongside the 11 primitive variants
