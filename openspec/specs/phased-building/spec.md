# phased-building Specification

## Purpose

Defines the `phased-building` capability for organizing a blueprint into AI-authored, ordered semantic build phases, computing per-phase material and survival summaries, and letting players navigate phases and switch between isolated (`== k`), cumulative (`1..k`), and full (`ALL`) hologram display modes.

## Requirements

### Requirement: Ordered semantic build phase definitions
The system SHALL support ordered semantic build phases (`phase_id`, `order`, `title`, and optional `description`) within a blueprint session, authored by the AI via operation batches rather than fixed Y-slice heuristics. When operations omit phase metadata, the blueprint SHALL behave as an unphased single-region build without exposing phase navigation clutter.

#### Scenario: First-seen phase ordering creates deterministic build sequence
- **WHEN** an AI adds operations tagged with `phase: { "title": "Foundation & Floor", "description": "Compact stone base" }` followed by operations tagged with `phase: { "title": "Structural Frame" }` and `phase: { "title": "Gable Roof" }`
- **THEN** the session records three ordered phases (`1: Foundation & Floor`, `2: Structural Frame`, `3: Gable Roof`) with deterministic slug `phase_id`s derived when omitted

#### Scenario: Unphased blueprints remain single-phase without UI clutter
- **WHEN** a blueprint is created and compiled without any `phase` metadata on its operations
- **THEN** the blueprint has no user-facing build phases and compiles as a single-region schematic

### Requirement: Empty phase pruning after final-state carving
After final-state rasterization resolves all carving (`clear_box`) and overrides, any declared phase owning zero surviving non-air voxels SHALL be omitted from active build navigation and compiled schematic subregions while remaining preserved in draft operation history.

#### Scenario: Carved-out phase is skipped during player navigation
- **WHEN** phase `2` places temporary blocks that are later completely removed by `clear_box` in phase `3`
- **THEN** phase `2` owns `0` surviving voxels, is excluded from compiled schematic subregions and player phase steps, and navigation advances directly across phases with surviving blocks

### Requirement: Active phase cursor and view mode state (`ISOLATED`, `CUMULATIVE`, `ALL`)
Each phased blueprint SHALL maintain an active phase cursor (`active_phase_id` / 1-based index) and a display mode supporting toggling between `ISOLATED` (only the active phase `k`) and `CUMULATIVE` (phases `1..k`), plus `ALL` (all phases visible). Newly compiled phased blueprints SHALL default to phase `1` in `ISOLATED` mode.

#### Scenario: Toggling between Isolated and Cumulative modes on phase 2
- **WHEN** a 4-phase blueprint is currently at phase `2` in `ISOLATED` mode and the player triggers the view-mode toggle
- **THEN** the active view mode switches to `CUMULATIVE`, showing phases `1` and `2` together while phases `3` and `4` remain hidden; triggering the toggle again returns to `ISOLATED` showing only phase `2`

#### Scenario: Recompiling preserves active phase cursor when phase still exists
- **WHEN** a player is at phase `3` in `CUMULATIVE` mode and the AI edits an operation and recompiles the blueprint with phase `3` still non-empty
- **THEN** the active cursor stays on phase `3` in `CUMULATIVE` mode instead of resetting to phase `1`

#### Scenario: Recompiling clamps cursor safely if active phase was removed
- **WHEN** a player is at phase `4` and the AI removes phase `4` and recompiles a 3-phase blueprint
- **THEN** the active phase cursor clamps safely to the nearest valid phase (`3`) without errors

### Requirement: Per-phase material counts and survival readiness
For phased blueprints, inspecting or displaying a phase SHALL compute non-air material counts scoped to that phase's surviving voxels (counting two-half doors as `1` item), and when Survival inventory tracking is enabled, SHALL compute `missing_materials` specifically for that phase alongside overall blueprint totals.

#### Scenario: Survival player checks materials for phase 1 only
- **WHEN** a survival player with inventory tracking enabled carries `32` `minecraft:cobblestone` and inspects a 3-phase cottage whose phase `1` needs `24` `minecraft:cobblestone` and whose phase `3` needs `64` `minecraft:oak_stairs`
- **THEN** phase `1` reports `0` missing materials while the overall blueprint survival summary still reports `64` missing `minecraft:oak_stairs`
