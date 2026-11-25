# Tasks

## 1. Plugin Manifest & MCP Configuration

- [x] 1.1 Create `.agents/plugins/archica-mcp/plugin.json` (`{"name": "archica-mcp", "disabled": true}`) and `.agents/plugins/archica-mcp/mcp_config.json` (`http://127.0.0.1:25580/mcp`) matching `docs/antigravity-setup.md`, and verify both files parse as valid JSON.

## 2. Core Workflow Skill (`archica-mcp-workflow`)

- [x] 2.1 Create `.agents/plugins/archica-mcp/skills/archica-mcp-workflow/SKILL.md` with valid YAML frontmatter (`name`, third-person `description`) covering the 11-tool lifecycle (`get_build_context` -> `blueprint_create` -> `blueprint_add_ops` -> `blueprint_inspect` -> `blueprint_compile` -> `blueprint_update_op`/`blueprint_remove_op`/`blueprint_undo`), forward-aligned local coordinates (`+Y` up, `+Z` forward, `+X` right), `facing` rotation, auto-orientation rules, and structured error recovery (`NO_WORLD_LOADED`, `BLUEPRINT_EXISTS`, `VOLUME_TOO_LARGE`, `INVALID_BLOCK_ID`, `LITEMATICA_UNAVAILABLE`); verify every tool name, primitive field, and error code matches `docs/mcp-tools.md` and `docs/blueprint-primitives.md`.

## 3. 5x5 Shared-Pillar Architecture Skill (`archica-5x5-architecture`)

- [x] 3.1 Create `.agents/plugins/archica-mcp/skills/archica-5x5-architecture/SKILL.md` defining the Shared-Pillar 5x5 grid (`stride = 4`, `4N + 1` odd footprint spans, `4`–`5`-block story heights), the 1-bay (`0..4` flush wall + pane/exterior trim) vs `>=2`-bay (`4i` protruding frame + `1..4N-1` recessed wall) depth modes, `repeat_pattern` recipes, and `gable_roof` odd-span + triangular gable-wall infill rules; verify coordinate examples against `BlueprintRasterizer.kt` and `BlueprintTransform.kt`.
- [x] 3.2 Create `.agents/plugins/archica-mcp/skills/archica-5x5-architecture/references/5x5-bay-recipes.md` and `references/material-palettes.md` with concrete JSON `ops` snippets (window bays, arched/framed entrances, multi-story bays, chimneys, and themed palettes/weathering gradients) linked via relative markdown links from `SKILL.md`; verify all referenced file paths exist and JSON snippets use valid v1 primitive fields.

## 4. Terrain & Survival Skill (`archica-terrain-survival`)

- [x] 4.1 Create `.agents/plugins/archica-mcp/skills/archica-terrain-survival/SKILL.md` and `references/survival-substitutions.md` covering `[TERRAIN_GRID]` (`<symbol><deltaY>`) parsing, choosing `origin` and `facing` against terrain slopes/water, projecting stone foundations/stilts into negative local `Y` (`y < 0`), grading vs stepped piers, and substituting block palettes from `[INVENTORY_MATERIALS]` and `survival_check.missing_materials`; verify sections, symbols, and error/material rules match `docs/spatial-context.md` and `docs/mcp-tools.md`.

## 5. Renovation & Interior Skill (`archica-renovation-interior`)

- [x] 5.1 Create `.agents/plugins/archica-mcp/skills/archica-renovation-interior/SKILL.md` covering `scan_volume` (`include_states=true`) RLE decoding, snapping Shared-Pillar 5x5 extensions onto existing structures, multi-story interior floor framing (`staircase` with `width` and `fill_under`), and `replace_box` weathering/palette upgrades; verify `scan_volume` RLE format and `staircase`/`replace_box` parameters match `docs/spatial-context.md` and `docs/blueprint-primitives.md`.

## 6. Documentation & Verification

- [x] 6.1 Update `docs/antigravity-setup.md` and `README.md` to document the bundled `.agents/plugins/archica-mcp/` plugin (4 skills included, `"disabled": true` default, toggling in Customizations UI, and copying `skills/` into other workspaces or MCP clients); verify all relative markdown links resolve to existing files.
- [x] 6.2 Validate plugin discovery layout, JSON syntax of `plugin.json` and `mcp_config.json`, YAML frontmatter and relative `references/` links across all 4 `SKILL.md` files, and run `openspec validate "add-archica-5x5-skills"` to confirm change artifacts pass validation.
