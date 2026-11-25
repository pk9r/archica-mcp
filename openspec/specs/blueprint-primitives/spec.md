# blueprint-primitives Specification

## Purpose

Defines the `blueprint-primitives` capability for managing stateful in-memory blueprint draft sessions, anchor-relative coordinate and facing transformations, blockstate validation and auto-orientation, sequential rasterization of the 11 v1 building primitives, and non-blocking survival material summaries.

## Requirements

### Requirement: Stateful blueprint draft session lifecycle
The system SHALL store draft blueprints in memory keyed by `blueprint_id` (`^[a-z0-9_-]{1,48}$`), holding `origin`, `facing` (`NORTH`, `SOUTH`, `EAST`, `WEST`, default `NORTH`), ordered operations, and a monotonic counter (`op_1`, `op_2`, ...). Duplicate creation with `overwrite=false` SHALL fail with `BLUEPRINT_EXISTS`, while `overwrite=true` resets the session.

#### Scenario: Creating and mutating a draft session with monotonic op IDs
- **WHEN** a session `oak_cottage` is created, two operations are added (`op_1`, `op_2`), `op_1` is removed, and a third operation is added
- **THEN** the newly added operation receives `op_3` rather than reusing `op_1`, and `op_2` remains ahead of `op_3` in evaluation order

#### Scenario: Duplicate blueprint creation rejected when overwrite is false
- **WHEN** `blueprint_create` is invoked for an already active `blueprint_id` with `overwrite=false` (or omitted)
- **THEN** the operation fails with `BLUEPRINT_EXISTS` and leaves the existing draft session untouched

### Requirement: In-place update, removal, and undo of operations
The system SHALL support replacing an operation in place by `op_id` while preserving its exact position in the evaluation pipeline, removing a single operation by `op_id`, and undoing the last `count` operations (default `1`). Targeting an unknown `blueprint_id` SHALL fail with `BLUEPRINT_NOT_FOUND`, and targeting a missing `op_id` SHALL fail with `OP_NOT_FOUND`.

#### Scenario: In-place operation update preserves evaluation order
- **WHEN** a blueprint has `[op_1 (fill_box), op_2 (clear_box), op_3 (set_block)]` and `op_2` is updated in place
- **THEN** the replacement keeps identifier `op_2` and still evaluates after `op_1` and before `op_3`

#### Scenario: Undo clamps safely or fails on missing session
- **WHEN** undo is requested with `count = 5` on a session containing `2` operations
- **THEN** all `2` remaining operations are removed, leaving `0` operations without crashing

### Requirement: Anchor-relative local coordinates and forward-aligned facing transform
Primitive coordinates SHALL use local space anchored at `(0, 0, 0)` (`+Y` up, `+Z` forward, `+X` right), with inclusive `[min, max]` box bounds. Mapping to world orientation SHALL rotate coordinates and directional blockstate properties (`facing`, `axis`) around `(0, 0, 0)` according to `blueprint.facing` (`NORTH`=`-Z`, `SOUTH`=`+Z`, `EAST`=`+X`, `WEST`=`-X` forward).

#### Scenario: Reversed box endpoints normalize inclusively
- **WHEN** a `fill_box` specifies `from: [4, 2, 4]` and `to: [0, 0, 0]`
- **THEN** the rasterized cuboid covers every integer coordinate in `0..4 x 0..2 x 0..4` (`75` blocks)

#### Scenario: Facing transform rotates both coordinates and directional blockstates
- **WHEN** a blueprint anchored at `[100, 64, 100]` with `facing = EAST` places a local block at `[0, 0, 2]` with local `facing=north` (forward)
- **THEN** its world-transformed offset corresponds to `+2` along world `+X` (`[102, 64, 100]`) and its rotated `facing` property becomes `east`

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

### Requirement: Sequential rasterization of v1 building primitives
The system SHALL rasterize operations sequentially (`op_1` to `op_N`) across `set_block`, `fill_box`, `hollow_box`, `line`, `cylinder`, `dome`, `gable_roof`, `staircase`, `replace_box`, `clear_box`, and `repeat_pattern` (`depth <= 4`, `count in 1..256`). Rasterization SHALL enforce a `131,072`-block enclosing volume cap, failing with `VOLUME_TOO_LARGE` when exceeded.

#### Scenario: Later clear_box carves an opening into an earlier hollow_box
- **WHEN** `op_1` creates a `hollow_box` wall from `[0, 0, 0]` to `[6, 4, 6]` and `op_2` runs `clear_box` from `[3, 1, 0]` to `[3, 2, 0]`
- **THEN** voxels `[3, 1, 0]` and `[3, 2, 0]` become `minecraft:air` while all remaining wall voxels stay intact

#### Scenario: Gable roof generates auto-oriented slopes and ridge cap
- **WHEN** `gable_roof` spans `[0, 4, 0]` to `[6, 4, 8]` with `ridge_axis: "z"`, `overhang: 1`, `block: "minecraft:oak_stairs"`, and `ridge_block: "minecraft:oak_slab"`
- **THEN** the roof spans `-1..7` in X and `-1..9` in Z, places ascending `oak_stairs` on both left and right slopes facing inward toward the peak, and caps the center peak ridge with `oak_slab`

#### Scenario: Replace box matches bare ID or explicit properties
- **WHEN** `replace_box` targets `minecraft:oak_planks` inside a region containing `oak_planks` and `cobblestone`
- **THEN** only `minecraft:oak_planks` voxels inside the box are replaced while `cobblestone` and voxels outside the box remain unchanged

#### Scenario: Oversized primitive exceeds rasterization safety cap
- **WHEN** an operation specifies bounds whose enclosing rasterized volume exceeds `131,072` blocks
- **THEN** rasterization/validation fails with `VOLUME_TOO_LARGE` without allocating an oversized grid

### Requirement: Bounding box, material aggregation, and non-blocking survival check
Inspecting or mutating a blueprint session SHALL compute the minimal enclosing local bounding box of non-air voxels, `total_blocks` (excluding air), per-block material counts (ignoring air), and—when a world is loaded in `survival` mode—`survival_check.missing_materials` comparing required counts against the player's current inventory without blocking draft creation or edits.

#### Scenario: Survival material check warns on missing blocks without failing
- **WHEN** a survival-mode player carrying `32` `minecraft:oak_stairs` inspects or adds ops to a blueprint requiring `98` `minecraft:oak_stairs`
- **THEN** the response succeeds and reports `survival_check.missing_materials["minecraft:oak_stairs"] = 66`

#### Scenario: Drafting remains functional when no world is loaded
- **WHEN** blueprint draft operations run while no Minecraft world is loaded
- **THEN** rasterization, bounding box, and material counts still succeed and `survival_check` is omitted (`null`)
