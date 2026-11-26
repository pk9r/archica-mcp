# Spec Delta: blueprint-primitives

## MODIFIED Requirements

### Requirement: Bounding box, material aggregation, and non-blocking survival check
Inspecting or mutating a blueprint session SHALL compute the minimal enclosing local bounding box of non-air voxels, `total_blocks` (excluding air), and per-block material counts (ignoring air). When player inventory tracking is enabled and a world is loaded in `survival` mode, it SHALL also include `survival_check.missing_materials` without blocking edits; otherwise `survival_check` SHALL be omitted (`null`).

#### Scenario: Survival material check skipped when inventory tracking is disabled
- **WHEN** a survival-mode player carrying `32` `minecraft:oak_stairs` inspects, mutates, or compiles a blueprint requiring `98` `minecraft:oak_stairs` while inventory tracking is disabled (the default)
- **THEN** the response succeeds, returns `total_blocks` and `materials`, and omits `survival_check` (`null`) without querying player inventory

#### Scenario: Survival material check warns on missing blocks without failing
- **WHEN** a survival-mode player who has enabled inventory tracking and carries `32` `minecraft:oak_stairs` inspects or adds ops to a blueprint requiring `98` `minecraft:oak_stairs`
- **THEN** the response succeeds and reports `survival_check.missing_materials["minecraft:oak_stairs"] = 66`

#### Scenario: Drafting remains functional when no world is loaded
- **WHEN** blueprint draft operations run while no Minecraft world is loaded
- **THEN** rasterization, bounding box, and material counts still succeed and `survival_check` is omitted (`null`)
