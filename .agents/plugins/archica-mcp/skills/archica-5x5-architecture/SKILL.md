---
name: archica-5x5-architecture
description: >-
  Applies the Shared-Pillar 5x5 Minecraft building technique (4-block pillar stride,
  4N+1 odd footprint spans, 3-layer facade depth, repeat_pattern bays, odd-span gable
  roofs with gable-wall infill, and material palettes) using Archica MCP primitives.
  Use when designing or building houses, towers, workshops, bridges, or town structures.
---

# 5x5 Shared-Pillar Architecture

Use the **Shared-Pillar 5x5** system to turn Archica MCP primitives into structured, multi-layered Minecraft architecture instead of flat boxes. Always pair this with `archica-mcp-workflow`.

## 1. The Shared-Pillar Grid (`stride = 4`, `4N + 1` Span)

Adjacent 5x5 bays share boundary pillars so corner/bay columns land at multiples of `4`:

```text
  Local +Z (forward)
    ^
  4 |  P === w === P === w === P      P: Grid pillars at x = 0, 4, 8, ...
  3 |  |           .           |      w: 3-block bay infill (x = 1..3, 5..7)
  2 |  w     .     .     .     w      .: Interior space
  1 |  |           .           |
  0 |  P === w === D === w === P
    +-----------------------------> Local +X (right)
       0   1  2  3 4   5  6  7 8
```

- **Footprint math**: `Nx` bays by `Nz` bays = `(4 * Nx + 1)` by `(4 * Nz + 1)` blocks (e.g., `1x1` = `5x5`, `2x2` = `9x9`, `3x2` = `13x9`).
- **Why `4N + 1` works**: Every footprint width is **odd**, guaranteeing a centered entrance/window in every bay (`+2` offset from any pillar) and an exact single-block `ridge_block` peak in `gable_roof`.
- **Story height**: Use `4` or `5` blocks per story (`y = 0` plinth/floor, `y = 1..4` wall opening, `y = 4` or `5` crossbeam/ceiling, `y = 5..8` or `6..9` second floor).
- **L-shapes & wings**: Compose compound footprints by attaching a secondary `4M + 1` wing sharing a grid line with the main block.

---

## 2. Two Depth Modes (Never Build Flat Boxes)

Choose depth layering based on footprint size:

### Mode A: 1-Bay (`5x5`, `0..4`) — Flush Wall + Exterior Trim
For compact watchtowers, shrines, or starter huts where recessing walls would leave only a `1x1` interior:
1. Build corner pillars (`line` `*_log`) at `(0,0..4,0)`, `(4,0..4,0)`, `(0,0..4,4)`, `(4,0..4,4)` and top beams at `y=4`.
2. Build `hollow_box` (`walls_only=true`) at `[0,1,0]..[4,3,4]` (or `[0,1,0]..[4,4,4]` before pillars) leaving a `3x3` interior.
3. Carve `clear_box` openings at bay center `x=2` / `z=2` and place `glass_pane` (naturally recessed half a block).
4. Push depth **outward** to `-1` and `+5`: inverted `stairs[half=top]` sills/eaves, `trapdoor` shutters, `fence` brackets, and `lantern`s.

### Mode B: Multi-Bay (`>= 2` bays, `9x9+`) — Protruding Timber Frame + Recessed Wall
For cottages, halls, and workshops (`0..4N`):
1. **Layer 0 (Outer Frame on `4i` grid)**: Place vertical pillars (`repeat_pattern` with `step=[4,0,0]` / `[0,0,4]`) on the perimeter `x=0,4,...,4N` and `z=0,4,...,4N`, plus horizontal crossbeams at `y=4` (or `y=5`) and structural tie-logs connecting intermediate outer pillars back into the inset wall (`z=0` to `z=1`).
2. **Layer -1 (Recessed Main Wall, 1 block inward)**: Place `hollow_box` (`walls_only=true`) at `[1, 1, 1]..[4*Nx - 1, 4, 4*Nz - 1]`. Notice `1..4N-1` remains odd (`7` wide for `9x9`) with a generous `5x5` interior (`2..6`), while the `4i` outer frame stands 1 full block proud of the wall!
3. **Layer +1 (Gallery & Openings)**: Carve doorways/windows in the recessed wall, insert `glass_pane` and `door`s, and fill the 1-block gallery between outer pillars (`z=0`) with stairs, fences, planter boxes, or benches.

---

## 3. `gable_roof` Rules & Gable-Wall Infill

`gable_roof` expands `[from, to]` horizontally by `overhang` (default `1`) on all four sides:
- **`ridge_axis: "z"`**: Slope climbs along `X`; ridge runs along `Z`.
- **`ridge_axis: "x"`**: Slope climbs along `Z`; ridge runs along `X`.
- **Mandatory Gable Infill**: `gable_roof` emits **only** the stair slopes and peak `ridge_block` — it leaves the triangular gable ends hollow! Fill each gable wall explicitly after `gable_roof` using stepped `fill_box` / `line` ops.

Example (`9x9` Mode B house `[0..8, 0..8]`, inset walls at `z=1` & `z=7`, `gable_roof` at `y=5`, `ridge_axis="z"`, `overhang=1`):
- Expanded roof `X` spans `-1..9` (`span = 11`, odd -> peak at `x=4, y=10`).
- Fill gable triangles at `z=1` and `z=7`:
  - `y=6`: `x=1..7`
  - `y=7`: `x=2..6`
  - `y=8`: `x=3..5`
  - `y=9`: `x=4..4`
- Optional depth boost: add inverted under-eave stairs (`[half=top]`) or gable-end timber trim along the roof rake.

---

## 4. `repeat_pattern` & Build Order

Execute `blueprint_add_ops` in strict visual layers so later primitives carve and detail cleanly:
1. **Plinth / Foundation** (`fill_box` at `y=0`, extending to `y < 0` on slopes — see `archica-terrain-survival`).
2. **Grid Pillars & Beams** (`line` + `repeat_pattern` with `step=[4,0,0]` / `[0,0,4]` / `[0,5,0]`).
3. **Recessed Walls & Interior Floors** (`hollow_box` `walls_only=true`, `fill_box` slab/plank floors).
4. **Openings** (`clear_box` for doorways, windows, archways).
5. **Roof & Gable Infill** (`gable_roof` or `dome`, followed by gable-end `fill_box` triangles + chimney).
6. **Joinery, Trim & Weathering** (`set_block` doors/lanterns/panes/shutters, `staircase`, and `replace_box` for 15–25% texture variation).

---

## 5. References

Read these files when drafting concrete `ops` payloads or selecting blocks:
- [5x5 Bay Recipes & Worked Cottage Example](./references/5x5-bay-recipes.md) — Ready-to-adapt JSON `ops` for Mode A/B frames, windows, arched doors, gable infill, and chimneys.
- [Material Palettes & Texture Gradients](./references/material-palettes.md) — Curated 3-tier block palettes (structural frame, primary wall, roof/accent) and weathering guidelines.
