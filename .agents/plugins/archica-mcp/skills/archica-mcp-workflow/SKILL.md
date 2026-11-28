---
name: archica-mcp-workflow
description: >-
  Guides the end-to-end Minecraft building workflow using Archica MCP's 11 tools,
  stateful blueprint sessions, forward-aligned local coordinates, auto-orientation
  rules, Litematica hologram compilation, and error recovery. Use whenever the user
  asks to design, draft, preview, or iterate on a Minecraft build through Archica MCP.
---

# Archica MCP Workflow

Archica MCP drafts client-side **Litematica holograms** in memory and compiles them to `.litematic` files without mutating world blocks directly. Always work through stateful blueprint sessions and surgical updates rather than regenerating entire builds.

## Companion Skills

Activate the matching companion skill alongside this workflow when applicable:
- **New architectural structures, facades, roofs, or 5x5 grids**: Load `archica-5x5-architecture`.
- **Sloped terrain, water, foundation planning, or Survival inventory limits**: Load `archica-terrain-survival`.
- **Inspecting, extending, furnishing, or upgrading existing structures**: Load `archica-renovation-interior`.

---

## 1. Standard 4-Stage Build Loop

1. **Inspect context first**:
   - Call `get_build_context` (optional `radius` `4..24`, default `12`) to get `[PLAYER]` (`pos`, `facing`, `game_mode`, `suggested_origin`), `[INVENTORY_MATERIALS]`, `[SURFACE_LEGEND]`, and `[TERRAIN_GRID]`.
   - Call `scan_volume(min, max, include_states=true)` (hard cap `13,824` blocks) when tying into or modifying existing world structures.
2. **Create or reset a blueprint session**:
   - Call `blueprint_create(blueprint_id, origin, facing, overwrite?)`.
   - `blueprint_id` must match `^[a-z0-9_-]{1,48}$` (becomes `<blueprint_id>.litematic`).
   - Use `blueprint_list` to inspect active in-memory sessions and Litematica placements; pass `overwrite=true` to intentionally reset an existing session.
3. **Draft in layered primitive batches (with semantic build phases)**:
   - Call `blueprint_add_ops(blueprint_id, ops, phase?)` with ordered primitives.
   - Operations rasterize sequentially (`op_1` -> `op_N`): build **foundation -> structural frame -> walls -> openings (`clear_box`) -> interior -> roof -> trim/details**, so later ops cleanly carve or override earlier ones.
   - For multi-part builds, tag batches with `phase` (e.g. `phase: {"id": "foundation", "title": "Foundation & Floor", "description": "Compact stone base"}` or a string title) grouped into **3–6 logical construction phases** so Survival players see what to build first and can toggle between `ISOLATED` (current phase) and `CUMULATIVE` (`1..current`) modes. Final-state phase attribution automatically excludes blocks removed by later `clear_box` ops from earlier phases.
   - Use `blueprint_inspect(blueprint_id, include_ops=true)` to check `op_id`s, per-phase `phases` breakdowns, local/world `bounding_box`, `total_blocks`, `materials`, and `survival_check` before compiling.
4. **Compile, preview, and iterate surgically**:
   - Call `blueprint_compile(blueprint_id, activate=true, origin_override?)` to write `.minecraft/schematics/archica/<blueprint_id>.litematic` (multi-region when phased, starting at Phase 1 in `ISOLATED` mode) and automatically replace any prior `archica:<blueprint_id>` Litematica hologram.
   - On player feedback, modify only the affected operations via `blueprint_update_op(blueprint_id, op_id, op, phase?)`, `blueprint_remove_op(blueprint_id, op_id)`, or `blueprint_undo(blueprint_id, count)`, then re-run `blueprint_compile`.
   - Use `blueprint_clear_placement(blueprint_id, delete_file?)` to remove an unwanted hologram placement.

---

## 2. Coordinate Space & Facing Rotation

All primitive coordinates operate in **anchor-relative local space**:
- `(0, 0, 0)` maps to `origin` in world coordinates.
- **`+Y`**: Upward (elevation). Negative `y` (`-1`, `-2`, ...) extends below `origin` (ideal for foundations on slopes).
- **`+Z`**: Local forward (`north` in local blockstates).
- **`+X`**: Local right (`east` in local blockstates).
- **Inclusive bounds**: `from` and `to` are inclusive on both endpoints regardless of min/max ordering.

When `blueprint.facing` (`NORTH`, `SOUTH`, `EAST`, `WEST`, default `NORTH`) is set:
- Local `+Z` maps to the chosen world cardinal direction (`NORTH`=`-Z`, `EAST`=`+X`, `SOUTH`=`+Z`, `WEST`=`-X`), and local `+X` rotates 90 degrees clockwise to the right.
- Directional blockstate properties (`facing`, `axis`, and horizontal connections `north`/`east`/`south`/`west`) rotate automatically on export.
- Tip: `suggested_origin` sits ~10 blocks ahead of the player's look direction. Setting `blueprint.facing` equal to the player's `facing` puts `z=0` closest to the player and `+Z` extending away from them.

---

## 3. Block Identifiers & Auto-Orientation

Every block parameter accepts `namespace:id` or `namespace:id[k=v,...]`:
- **Stairs**: `gable_roof` and `staircase` auto-compute `facing` and `half=bottom`. For inverted eave trims, window sills, or arches in `set_block`/`fill_box`/`repeat_pattern`, specify explicit local properties like `minecraft:oak_stairs[facing=north,half=top]`.
- **Logs / Pillars**: `line` and `fill_box` automatically infer `axis` (`x`, `y`, or `z`) from the longest delta axis.
- **Doors**: `set_block` on a door ID (without `half=`) automatically places both `lower` and `upper` halves (`pos` and `pos + [0,1,0]`) and infers `facing` toward adjacent air.
- **Connectables (`glass_pane`, `iron_bars`, `fence`)**: Horizontal `north`/`east`/`south`/`west` connections are computed automatically after all ops finish rasterizing (and rotated with `blueprint.facing`).

---

## 4. V1 Building Primitives Reference

| `type` | Required Fields | Optional Fields | Notes |
|---|---|---|---|
| `set_block` | `pos:[x,y,z]`, `block` | — | Places 1 block (or 2-tall door). |
| `fill_box` | `from:[x,y,z]`, `to:[x,y,z]`, `block` | — | Solid cuboid; auto-orients logs. |
| `hollow_box` | `from:[x,y,z]`, `to:[x,y,z]`, `block` | `walls_only:bool=false` | 1-block shell; `walls_only=true` skips floor/ceiling. |
| `line` | `from:[x,y,z]`, `to:[x,y,z]`, `block` | — | 3D Bresenham line; auto-orients logs. |
| `cylinder` | `center:[x,y,z]`, `radius:int`, `height:int`, `block` | `hollow:bool=false` | Extrudes upward along `+Y`. |
| `dome` | `center:[x,y,z]`, `radius:int`, `block` | `hollow:bool=true` | Upper hemisphere (`y >= center.y`). |
| `gable_roof` | `from:[x,y,z]`, `to:[x,y,z]`, `block`, `ridge_axis:"x"\|"z"` | `ridge_block`, `overhang:int=1` | Sloped stairs + peak when odd span; gable ends stay open. |
| `staircase` | `from:[x,y,z]`, `to:[x,y,z]`, `block` | `width:int=1`, `fill_under:bool=false` | Ascending stair run; auto-orients climb `facing`. |
| `replace_box` | `from:[x,y,z]`, `to:[x,y,z]`, `target`, `replacement` | — | Bare `target` ID matches any blockstate properties. |
| `clear_box` | `from:[x,y,z]`, `to:[x,y,z]` | — | Removes voxels (`minecraft:air`) inside box. |
| `repeat_pattern` | `op:PrimitiveOp`, `count:int`, `step:[dx,dy,dz]` | — | `count` `1..256`, max nesting depth `4`. |

---

## 5. Error Handling & Recovery

| Error Code | Meaning & Action |
|---|---|
| `NO_WORLD_LOADED` | Ask the player to join a world before calling world/placement tools (`blueprint_compile(activate=false)` still works offline). |
| `BLUEPRINT_EXISTS` | Call `blueprint_list` / `blueprint_inspect`, or retry `blueprint_create` with `overwrite=true` if replacing. |
| `BLUEPRINT_NOT_FOUND` / `OP_NOT_FOUND` | Check `blueprint_list` or `blueprint_inspect(include_ops=true)` for exact IDs. |
| `INVALID_BLOCK_ID` | Fix typo or unsupported blockstate property in `<namespace>:<id>[...]`. |
| `VOLUME_TOO_LARGE` | Enclosing rasterization exceeds `131,072` blocks (or `scan_volume` exceeds `13,824`); split into smaller modular blueprints or tighter bounds. |
| `INVALID_BOUNDS` / `INVALID_RADIUS` / `INVALID_ARGUMENTS` | Ensure `scan_volume` has `min[i] <= max[i]`, `radius` in `4..24`, and valid primitive fields. |
| `LITEMATICA_UNAVAILABLE` | Report that Litematica placement failed and share the compiled `.litematic` path from the error message so the player can load it manually. |
| `CLIENT_TIMEOUT` | Main-thread dispatch timed out (5s); retry once or reduce scan/compile size. |
