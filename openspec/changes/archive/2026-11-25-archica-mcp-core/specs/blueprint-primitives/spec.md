# blueprint-primitives Delta Specification

## MODIFIED Requirements

### Requirement: Block identifier validation and auto-orientation
Every block reference SHALL accept `<namespace>:<id>` with optional `[key=value,...]` properties and be validated before mutating session state, failing with `INVALID_BLOCK_ID` on unknown blocks or properties. When explicit directional or connection states are omitted, the rasterizer SHALL auto-orient `stairs` (`facing`, `half`), `logs`/pillars (`axis`), `doors` (`half=lower` + `half=upper`), and `connectable` blocks (`north`, `south`, `east`, `west`).

#### Scenario: Invalid block ID or property rejected before mutating session
- **WHEN** `blueprint_add_ops` includes `minecraft:nonexistent_block` or `minecraft:oak_stairs[half=sideways]`
- **THEN** the call fails with `INVALID_BLOCK_ID` and no operations from the batch are committed to the session

#### Scenario: Door placement emits both lower and upper halves
- **WHEN** `set_block` places `minecraft:oak_door` at `[2, 1, 0]` without explicit `half`
- **THEN** the rasterized grid places `half=lower` at `[2, 1, 0]` and `half=upper` at `[2, 2, 0]` with matching `facing`, `hinge`, and `open` defaults

#### Scenario: Connectable panes and fences auto-connect horizontally
- **WHEN** a `fill_box` places `minecraft:glass_pane` adjacent to solid `minecraft:oak_planks` on local `+X` and another `minecraft:glass_pane` on local `-X` without explicit `north`/`south`/`east`/`west` properties
- **THEN** the rasterized `glass_pane` emits `east=true`, `west=true`, `north=false`, and `south=false` (and rotates those horizontal flags with `blueprint.facing` on export)

#### Scenario: Explicit connection properties are preserved
- **WHEN** `set_block` places `minecraft:oak_fence[north=true,east=false]` next to solid blocks on all sides
- **THEN** the explicit `north=true` and `east=false` properties are preserved while only omitted directions are defaulted
