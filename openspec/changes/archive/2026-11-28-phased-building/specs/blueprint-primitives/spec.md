# Spec Delta

## ADDED Requirements

### Requirement: Optional semantic phase assignment on primitive operations
Each stored blueprint operation SHALL optionally store a `phase_id` linking it to an ordered phase entry (`phase_id`, `title`, optional `description`) registered on the session. Updating an operation in place via `blueprint_update_op` SHALL preserve its existing `phase_id` unless a new `phase` is explicitly provided.

#### Scenario: Batch operations inherit batch-level phase metadata
- **WHEN** `blueprint_add_ops` is called with batch-level `phase` metadata and three primitive operations
- **THEN** all three operations are assigned to that phase in evaluation order unless an individual operation overrides it

#### Scenario: Updating an operation preserves its phase assignment by default
- **WHEN** `op_2` belongs to phase `walls` and `blueprint_update_op` replaces `op_2` without specifying `phase`
- **THEN** `op_2` remains assigned to phase `walls` in its original evaluation position

### Requirement: Final-state phase attribution across carving, replacement, and paired doors
During sequential rasterization (`op_1` to `op_N`), the system SHALL compute the final `VoxelGrid` across all operations while tracking the owning `phase_id` for each surviving voxel. `clear_box` SHALL remove both the voxel and its phase attribution, `replace_box` SHALL attribute modified voxels to the replacement operation's phase when phased (or retain the prior phase when unphased), and auto-generated two-half doors (`lower` and `upper`) SHALL share the same owning `phase_id`.

#### Scenario: Later clear_box carves doorway out of an earlier phase
- **WHEN** `op_1` in phase `walls` fills a wall and `op_2` in phase `openings` runs `clear_box` for a doorway
- **THEN** doorway voxels become air and are absent from both `walls` and `openings`, so the player is never asked to place blocks in the doorway during `walls`

#### Scenario: Auto-oriented two-half door belongs to one phase
- **WHEN** `set_block` places `minecraft:oak_door` in phase `trim`
- **THEN** both `half=lower` and `half=upper` voxels receive phase `trim`

#### Scenario: Connectable blocks preserve final auto-connections per phase voxel
- **WHEN** a `minecraft:glass_pane` placed in phase `windows` borders a solid wall placed in phase `walls`
- **THEN** the pane voxel stays attributed to phase `windows` with its final horizontal connection blockstate pointing toward the adjacent wall

### Requirement: Phase metadata and active cursor persistence in draft sidecars
Persisted `<blueprint_id>.draft.json` sidecar files SHALL store ordered phase definitions, per-operation `phase_id`s, and the player's current phase view cursor (`active_phase_id`, `view_mode`) using backward-compatible defaults so pre-existing v1 draft files without phase fields reload cleanly.

#### Scenario: Persisted phased draft restores phases and active cursor on startup
- **WHEN** a phased blueprint at phase `2` in `CUMULATIVE` mode restarts and reloads from `.draft.json`
- **THEN** all phase titles, operation assignments, and the phase `2` / `CUMULATIVE` view state are restored

#### Scenario: Legacy draft without phase fields loads without error
- **WHEN** an existing `.draft.json` created before phased-building contains no phase fields
- **THEN** startup reload restores the draft cleanly as an unphased blueprint
