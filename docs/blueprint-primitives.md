# Stateful Blueprints & Building Primitives

> **Status:** v1 Draft Engine, Primitives & Litematica Compilation Implemented (`blueprint_create`..`blueprint_list`, `blueprint_compile`, `blueprint_clear_placement`, forward-aligned facing transforms, `BlockCatalog` auto-orientation, 11 v1 primitives, and `.litematic` v7 export + `ClientLitematicaBridge`).

Rather than forcing an AI agent to emit thousands of individual `set_block` calls or regenerate an entire build from scratch on every feedback turn, Archica MCP uses **stateful in-memory blueprint sessions** driven by composable **building primitives**.

---

## 1. Stateful Blueprint Session Lifecycle

Each blueprint lives in memory as an ordered list of primitive operations attached to a world `origin` and base `facing`:

```text
  [blueprint_create] ---> Draft Session ("cottage_v1")
                               |
         +---------------------+---------------------+
         |                     |                     |
         v                     v                     v
  [blueprint_add_ops]   [blueprint_update_op]  [blueprint_undo /
  (append batch)        (edit roof/wall op)     remove_op]
         |                     |                     |
         +---------------------+---------------------+
                               |
                               v
                      [blueprint_compile]
                       * Rasterize ops in order -> Voxel Grid
                       * Write .litematic NBT file
                       * Replace & activate Litematica placement
```

### Session Properties
- **`blueprint_id`**: Unique slug (e.g., `oak_cottage`), also used as the `.litematic` filename (`<blueprint_id>.litematic`).
- **`origin`**: World `(x, y, z)` anchor corresponding to local `(0, 0, 0)`.
- **`facing`**: Cardinal orientation (`NORTH`, `SOUTH`, `EAST`, `WEST`, default `NORTH`) defining the blueprint's local `+Z` / `+X` forward axis.
- **`ops`**: Ordered list of operations, each assigned a monotonically increasing `op_id` (`op_1`, `op_2`, ..., never reused after removal or undo) for targeted updates or removal.
- **Evaluation order**: Operations rasterize sequentially from `op_1` to `op_N`, so later primitives (e.g., `clear_box` or `replace_box` for windows and doors) naturally carve into or override earlier walls and floors.
- **Safety cap**: Rasterization enforces a maximum enclosing bounding box of `131,072` blocks (`VOLUME_TOO_LARGE`).

---

## 2. Coordinate System & Facing Transforms

All primitive coordinates use **anchor-relative local space** centered at `(0, 0, 0)`:
- **`+Y`**: Upward (elevation).
- **`+Z` (local forward)** / **`+X` (local right)**: Forward-aligned local space rotated around `(0, 0, 0)` according to `facing` when mapping to world/schematic coordinates (`NORTH` -> `-Z` forward / `+X` right; `EAST` -> `+X` forward / `+Z` right; `SOUTH` -> `+Z` forward / `-X` right; `WEST` -> `-X` forward / `-Z` right), rotating directional blockstate properties (`facing`, `axis`) accordingly.
- **Inclusive bounds**: Box ranges `from`..`to` are inclusive on both endpoints regardless of min/max ordering (normalized internally as `[min(a,b), max(a,b)]`).

---

## 3. Blockstate Semantics & Auto-Orientation

Primitives accept standard Minecraft block identifiers (with optional explicit blockstate properties in bracket notation, e.g., `minecraft:oak_stairs[facing=north,half=bottom]`). When explicit properties are omitted, Archica MCP applies **auto-orientation**:

- **`stairs`** (`gable_roof`, `staircase`): Automatically computes `facing` toward the ascent/slope direction and sets `half=bottom` (or `half=top` for inverted under-eave steps).
- **`logs` / pillars** (`line`, `fill_box`): Infers `axis` (`x`, `y`, `z`) from the dominant direction of a `line` (vertical beams -> `axis=y`; horizontal beams along X -> `axis=x`).
- **`doors`**: Places both lower (`half=lower`) and upper (`half=upper`) halves cleanly when placed at a doorway coordinate, facing outward if specified or inferred from adjacent wall openings.
- **`glass_pane` / `iron_bars` / `fence`**: Leaves connection states (`north/south/east/west`) computed during rasterization or lets Litematica/Minecraft derive neighbor connections consistently.

---

## 4. V1 Building Primitives Catalog

Every operation in `blueprint_add_ops` / `blueprint_update_op` specifies a `type` plus its parameters:

| Primitive | Parameters | Expected Voxel Behavior |
|---|---|---|
| `set_block` | `pos: [x,y,z]`, `block: string` | Places a single block at local `pos` (for doors, places lower + upper halves automatically). |
| `fill_box` | `from: [x,y,z]`, `to: [x,y,z]`, `block: string` | Fills the solid axis-aligned cuboid between `from` and `to` (inclusive) with `block`. |
| `hollow_box` | `from: [x,y,z]`, `to: [x,y,z]`, `block: string`, `walls_only?: bool = false` | Fills only the outer 1-block shell of the cuboid. When `walls_only=true`, omits floor and ceiling faces (4 vertical walls only). |
| `line` | `from: [x,y,z]`, `to: [x,y,z]`, `block: string` | Rasterizes a 3D Bresenham voxel line between `from` and `to` (pillars, beams, diagonal rafters); auto-sets log `axis` on orthogonal lines. |
| `cylinder` | `center: [x,y,z]`, `radius: int`, `height: int`, `block: string`, `hollow?: bool = false` | Extrudes a vertical circular cylinder along `+Y` from `center.y` to `center.y + height - 1`; `hollow=true` emits perimeter ring only. |
| `dome` | `center: [x,y,z]`, `radius: int`, `block: string`, `hollow?: bool = true` | Generates an upper hemisphere (`y >= center.y`) of given `radius`; `hollow=true` creates a thin 1-block shell suitable for roofs/observatories. |
| `gable_roof` | `from: [x,y,z]`, `to: [x,y,z]`, `block: string`, `ridge_block?: string`, `ridge_axis: "x" \| "z"`, `overhang?: int = 1` | Generates a pitched A-frame roof spanning the footprint expanded by `overhang`, automatically orienting `stairs` slopes on both sides and capping the peak with `ridge_block` (slabs/blocks). |
| `staircase` | `from: [x,y,z]`, `to: [x,y,z]`, `block: string`, `width?: int = 1`, `fill_under?: bool = false` | Builds an ascending flight of stairs from `from` to `to`, orienting `stairs` `facing` along the climb direction and optionally filling support blocks underneath. |
| `replace_box` | `from: [x,y,z]`, `to: [x,y,z]`, `target: string`, `replacement: string` | Replaces every voxel matching `target` inside the inclusive box with `replacement` (ideal for swapping wall palettes or turning solid blocks into windows). |
| `clear_box` | `from: [x,y,z]`, `to: [x,y,z]` | Sets all voxels inside the inclusive box to `minecraft:air` (shortcut for cutting doorways, archways, and interior rooms). |
| `repeat_pattern` | `op: PrimitiveOp`, `count: int`, `step: [dx,dy,dz]` | Evaluates `op` `count` times (`1..256`, max nesting depth `4`), offsetting all coordinates by `i * step` for `i in 0 until count` (ideal for fence posts, colonnades, ribs, and repeated windows). |

---

## 5. Related Documents

- [Architecture & Litematica Integration](architecture.md)
- [Spatial Context Compression](spatial-context.md)
- [MCP Tools Reference](mcp-tools.md)
