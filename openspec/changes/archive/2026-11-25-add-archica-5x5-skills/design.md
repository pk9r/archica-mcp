# Design

## Context

See [proposal.md](proposal.md) for motivation. Currently, `archica-mcp` documents how to configure a workspace-scoped Antigravity plugin (`.agents/plugins/archica-mcp/plugin.json` and `mcp_config.json`) in `docs/antigravity-setup.md`, and exposes concise server initialization `instructions` plus JSON schemas for its 11 MCP tools and 11 building primitives (`BlueprintRasterizer`, `BlueprintTransform`, `BuildContextScanner`). However, the repository does not yet ship an actual plugin bundle or agent skills teaching LLMs how to compose primitives into layered, terrain-aware Minecraft architecture.

## Goals / Non-Goals

**Goals:**
- Ship a ready-to-use, workspace-scoped Antigravity plugin under `.agents/plugins/archica-mcp/` bundling `plugin.json`, `mcp_config.json`, and 4 modular `skills/` (`archica-mcp-workflow`, `archica-5x5-architecture`, `archica-terrain-survival`, `archica-renovation-interior`) written in English.
- Encode the **Shared-Pillar 5x5** architectural system (`stride = 4`, `4N + 1` odd footprint spans) directly in terms of Archica MCP's 11 primitives (`line`, `hollow_box`, `fill_box`, `clear_box`, `replace_box`, `set_block`, `gable_roof`, `staircase`, `cylinder`, `dome`, `repeat_pattern`), forward-aligned local space (`+Y` up, `+Z` forward, `+X` right), and auto-orientation semantics.
- Keep `SKILL.md` files concise and scannable by offloading bulky material palettes, blockstate examples, and reusable 5x5 bay recipes into `references/` files under the relevant skills.
- Keep `.agents/plugins/archica-mcp/plugin.json` disabled by default (`"disabled": true`) so mod developers opening the repository without Minecraft running do not trigger unwanted MCP connection attempts.
- Update `docs/antigravity-setup.md` and `README.md` so users know the plugin and skills are bundled in the repo and can either toggle them on directly or copy the plugin / `skills/` folder into any build workspace.

**Non-Goals:**
- Modifying any Kotlin source code, MCP tool schemas, `BlueprintRasterizer` geometry, or `.litematic` compiler behavior.
- Registering global `~/.gemini/config/` plugins or `mcp_config.json` entries.
- Building non-overlapping `5x5x5` cube templates (stride `5`) as the primary system; the chosen standard is Shared-Pillar 5x5 (stride `4`).

## Decisions

1. **Package as a workspace plugin under `.agents/plugins/archica-mcp/` with `"disabled": true`**
   - *Rationale*: Antigravity discovers workspace plugins directly from `.agents/plugins/<name>/` and treats `plugin.json` + `mcp_config.json` + `skills/` as a single toggleable unit. Setting `"disabled": true` aligns with `docs/antigravity-setup.md` and avoids noisy MCP startup failures during Kotlin/Gradle development when Minecraft is closed.
   - *Portability*: Placing standard `SKILL.md` directories inside `.agents/plugins/archica-mcp/skills/` keeps them immediately reusable if a user copies `skills/` into `.agents/skills/`, `~/.gemini/config/skills/`, or other Agent-Skills-compatible clients.
   - *Alternative considered*: Putting loose skills in `.agents/skills/` separate from `mcp_config.json` — rejected because users would have to manage MCP connection and skills separately and would lose single-toggle plugin lifecycle.

2. **Split into 4 focused skills with distinct triggers (Progressive Disclosure)**
   - **`archica-mcp-workflow`**: Owns the end-to-end tool loop (`get_build_context` -> `blueprint_create` -> `blueprint_add_ops` -> `blueprint_inspect` -> `blueprint_compile` -> `blueprint_update_op`/`blueprint_remove_op`/`blueprint_undo`), forward-aligned coordinate convention (`+Z` forward, `+X` right), `facing` rotation, and structured error recovery (`NO_WORLD_LOADED`, `BLUEPRINT_EXISTS`, `VOLUME_TOO_LARGE`, `INVALID_BLOCK_ID`).
   - **`archica-5x5-architecture`**: Owns the Shared-Pillar 5x5 architectural grammar:
     - Grid pillars at `x = 4i`, `z = 4j` (`4N + 1` odd total span: `5, 9, 13, ...`) and `4`–`5`-block story heights.
     - Two depth modes so small and large builds both have 3D relief:
       1. *1-bay (`5x5`, `0..4`)*: flush perimeter walls with recessed `glass_pane` openings + outward trim (`-1` / `+5` stairs, trapdoors, fences, flower boxes).
       2. *Multi-bay (`>= 2` bays, `9x9+`)*: outer timber/stone pillar-and-beam frame on the `4i` grid with main walls recessed 1 block inward (`1..4N-1`, still odd width with usable interior), letting `gable_roof` on `0..4N` (`overhang=1`) shelter both the protruding frame and recessed facade.
     - `repeat_pattern` recipes for pillars, beams, window bays, and multi-story stacking.
     - Explicit `gable_roof` rules: odd span guarantees a single `ridge_block` peak, and agents MUST infill triangular gable end walls (`fill_box` / stepped `line`) plus optional inverted under-eave stairs because `gable_roof` emits only the sloped roof shell.
     - `references/` containing `5x5-bay-recipes.md` (door, window, arch, porch, dormer/chimney patterns) and `material-palettes.md` (medieval oak/spruce, stone/deepslate, desert sandstone, coastal/japanese palettes with block gradient rules).
   - **`archica-terrain-survival`**: Owns reading `[TERRAIN_GRID]` (`<symbol><deltaY>`), picking `origin` and `facing` relative to slopes/water, projecting stone foundations/stilts into negative local `Y` (`y = -1..-N`) so structures never float on slopes, choosing between grading (`clear_box`) vs stepped piers, and adapting block choices using `[INVENTORY_MATERIALS]` and `survival_check.missing_materials` (plus `references/survival-substitutions.md`).
   - **`archica-renovation-interior`**: Owns `scan_volume` (`include_states=true`) RLE slice decoding to inspect existing builds, snapping 5x5 Shared-Pillar extensions onto existing walls/roof ridges, interior floor division + `staircase` placement (`fill_under`, `width`), and `replace_box` weathering/palette upgrades.
   - *Alternative considered*: A single monolithic `archica-builder` skill — rejected because loading RLE scanning, terrain grading, and full 5x5 recipe catalogs into every prompt bloats context when the user only needs one capability.

3. **Author skills and references in English**
   - *Rationale*: Matches all repository documentation, MCP tool schemas, block IDs, and error codes, maximizing instruction-following precision regardless of the user's chat language.

4. **Update `docs/antigravity-setup.md` and `README.md`**
   - *Rationale*: Previously `docs/antigravity-setup.md` stated users create `.agents/plugins/archica-mcp/` manually in their build workspace. Now that this repo ships the complete plugin (disabled by default), docs must explain both toggling the bundled plugin here and copying/referencing it into external build workspaces.

## Risks / Trade-offs

- **Skill trigger overlap or under-triggering** → Write sharp, third-person `description` frontmatter for each of the 4 skills specifying exact triggers (workflow/MCP lifecycle vs 5x5 architectural design vs terrain/survival vs `scan_volume`/renovation/interiors) and instruct `archica-mcp-workflow` to cross-reference the specialized skills when designing or renovating.
- **Coordinate mistakes on `gable_roof` / rotated `facing`** → Include concrete worked coordinate examples (`local +Z forward`, `local +X right`, `overhang=1`, gable triangle infill math) and verify every primitive field name and enum value against `docs/blueprint-primitives.md` and `BlueprintRasterizer.kt`.
- **Mod developers accidentally triggering MCP connection warnings** → Keep `"disabled": true` in `.agents/plugins/archica-mcp/plugin.json` and document toggling it on only while Minecraft is running.
