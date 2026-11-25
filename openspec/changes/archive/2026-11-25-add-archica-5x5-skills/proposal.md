# Proposal

## Why

Archica MCP exposes 11 spatial-context, stateful blueprint, and Litematica tools, plus 11 geometric primitives, yet AI agents without domain-specific architectural guidance frequently emit flat hollow boxes, misalign local forward-aligned coordinates, overlook `gable_roof` odd-span/open-gable behavior, or ignore terrain elevation and survival material constraints. Packaging a dedicated suite of agent skills centered on the **5x5 Shared-Pillar building technique** inside the `archica-mcp` Antigravity plugin gives agents repeatable, high-craft Minecraft building workflows out of the box.

## What Changes

- Package a complete workspace-scoped Antigravity plugin under `.agents/plugins/archica-mcp/` containing:
  - `plugin.json` (`"name": "archica-mcp"`, `"disabled": true` by default so mod development sessions do not attempt to connect while Minecraft is closed)
  - `mcp_config.json` (`http://127.0.0.1:25580/mcp` Streamable HTTP endpoint)
  - `skills/` (4 focused English `SKILL.md` skills with progressive-disclosure `references/`):
    1. `archica-mcp-workflow`: Core inspect-draft-compile-iterate loop across the 11 MCP tools, forward-aligned local coordinate rules (`+Y` up, `+Z` forward, `+X` right), `facing` rotation, targeted `blueprint_update_op`/`blueprint_remove_op`/`blueprint_undo` refinement, and error handling.
    2. `archica-5x5-architecture`: **Shared-Pillar 5x5 (4-block stride, `4N + 1` odd total span)** architectural system—3-layer depth rules (flush-grid + trim/pane depth for 1-bay `5x5` vs 1-block recessed walls behind `4i` timber frames for `>=2`-bay `9x9+`), `repeat_pattern` bay/floor recipes, `gable_roof` odd-span alignment and triangular gable-wall infill, plus `references/` for material palettes and reusable 5x5 bay modules (windows, doors, arches, chimneys).
    3. `archica-terrain-survival`: Interpreting `[TERRAIN_GRID]` (`<symbol><deltaY>`) and `suggested_origin` from `get_build_context`, projecting foundations/stilts below `y=0` on slopes or water, deciding between site grading (`clear_box`) and stepped foundations, and substituting palettes against `[INVENTORY_MATERIALS]` and `survival_check.missing_materials`.
    4. `archica-renovation-interior`: Using `scan_volume` (`include_states=true`) RLE slices to survey existing structures, snapping 5x5 Shared-Pillar extensions onto existing footprints, interior multi-story framing (`staircase` + `4`–`5`-block floor heights), and `replace_box` material upgrades/weathering.
- Update `docs/antigravity-setup.md` and `README.md` to document the bundled `.agents/plugins/archica-mcp/` plugin (including the 4 skills, default `"disabled": true` toggle behavior, and copying `skills/` for standalone or non-Antigravity MCP clients).

## Capabilities

### New Capabilities
<!-- None: workspace plugin configuration, agent skill prompts/references, and documentation only; no changes to mod runtime behavior or MCP contracts. -->

### Modified Capabilities
<!-- None: existing MCP server, blueprint primitives, spatial compression, compiler, and client UX specs remain unchanged. -->

## Impact

- **Plugin & Agent Skills (New)**: `.agents/plugins/archica-mcp/plugin.json`, `.agents/plugins/archica-mcp/mcp_config.json`, and `.agents/plugins/archica-mcp/skills/{archica-mcp-workflow,archica-5x5-architecture,archica-terrain-survival,archica-renovation-interior}/...`.
- **Documentation (Modified)**: `docs/antigravity-setup.md`, `README.md`.
- **Code / Runtime**: None — no changes to Kotlin mod source code, MCP tool schemas, rasterizer behavior, or Gradle tests.
- **OpenSpec Specs**: Skipped (`skip_specs: true` in `.openspec.yaml`) because no observable mod or MCP server behavior changes.
