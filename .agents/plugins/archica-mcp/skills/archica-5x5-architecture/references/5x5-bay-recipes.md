# 5x5 Bay Recipes & Worked Examples

All coordinates are local (`+Y` up, `+Z` forward, `+X` right) for `Shared-Pillar 5x5` (`stride = 4`, `4N + 1` span).

---

## 1. Mode B Multi-Bay Skeleton (`2x2` = `9x9`, `0..8`)

1-block-deep timber frame on `x,z in {0, 4, 8}` with recessed walls at `1..7`:

```json
[
  { "type": "fill_box", "from": [1, 0, 1], "to": [7, 0, 7], "block": "minecraft:cobblestone" },
  {
    "type": "repeat_pattern",
    "count": 3,
    "step": [4, 0, 0],
    "op": {
      "type": "repeat_pattern",
      "count": 2,
      "step": [0, 0, 8],
      "op": { "type": "line", "from": [0, 0, 0], "to": [0, 4, 0], "block": "minecraft:oak_log" }
    }
  },
  { "type": "line", "from": [0, 0, 4], "to": [0, 4, 4], "block": "minecraft:oak_log" },
  { "type": "line", "from": [8, 0, 4], "to": [8, 4, 4], "block": "minecraft:oak_log" },
  { "type": "line", "from": [0, 4, 0], "to": [8, 4, 0], "block": "minecraft:oak_log" },
  { "type": "line", "from": [0, 4, 8], "to": [8, 4, 8], "block": "minecraft:oak_log" },
  { "type": "line", "from": [0, 4, 0], "to": [0, 4, 8], "block": "minecraft:oak_log" },
  { "type": "line", "from": [8, 4, 0], "to": [8, 4, 8], "block": "minecraft:oak_log" },
  { "type": "set_block", "pos": [4, 4, 1], "block": "minecraft:oak_log[axis=z]" },
  { "type": "set_block", "pos": [4, 4, 7], "block": "minecraft:oak_log[axis=z]" },
  { "type": "set_block", "pos": [1, 4, 4], "block": "minecraft:oak_log[axis=x]" },
  { "type": "set_block", "pos": [7, 4, 4], "block": "minecraft:oak_log[axis=x]" },
  { "type": "hollow_box", "from": [1, 1, 1], "to": [7, 4, 7], "block": "minecraft:oak_planks", "walls_only": true }
]
```

*Note:* The four `set_block` tie-logs at `(4,4,1)`, `(4,4,7)`, `(1,4,4)`, `(7,4,4)` visually anchor the mid-span outer pillars into the recessed wall.

---

## 2. Bay Wall Modules (Width 3 Between Pillars)

Use these inside any 3-block bay (`x = 1..3` or `5..7`) on a `z = 1` front recessed wall (facing `-Z` toward `z = 0`):

### A. Recessed Window Bay (at `x = 1..3, z = 1`)
```json
[
  { "type": "clear_box", "from": [2, 2, 1], "to": [2, 3, 1] },
  { "type": "set_block", "pos": [2, 2, 1], "block": "minecraft:glass_pane" },
  { "type": "set_block", "pos": [2, 3, 1], "block": "minecraft:glass_pane" },
  { "type": "set_block", "pos": [2, 1, 0], "block": "minecraft:oak_stairs[facing=north,half=top]" },
  { "type": "set_block", "pos": [1, 2, 0], "block": "minecraft:spruce_trapdoor[facing=west,half=bottom,open=true]" },
  { "type": "set_block", "pos": [3, 2, 0], "block": "minecraft:spruce_trapdoor[facing=east,half=bottom,open=true]" }
]
```

### B. Framed Entrance Door Bay (at `x = 1..3, z = 1` with `z = 0` stoop)
```json
[
  { "type": "clear_box", "from": [2, 1, 1], "to": [2, 2, 1] },
  { "type": "set_block", "pos": [2, 1, 1], "block": "minecraft:oak_door" },
  { "type": "set_block", "pos": [2, 0, 0], "block": "minecraft:cobblestone_stairs[facing=north,half=bottom]" },
  { "type": "set_block", "pos": [1, 1, 0], "block": "minecraft:oak_fence" },
  { "type": "set_block", "pos": [3, 1, 0], "block": "minecraft:oak_fence" },
  { "type": "set_block", "pos": [2, 3, 0], "block": "minecraft:lantern[hanging=true]" }
]
```

### C. Wide 3-Block Arch Window (`x = 5..7, z = 1`, taller `y = 1..5` story)
```json
[
  { "type": "clear_box", "from": [5, 2, 1], "to": [7, 4, 1] },
  { "type": "fill_box", "from": [5, 2, 1], "to": [7, 4, 1], "block": "minecraft:glass_pane" },
  { "type": "set_block", "pos": [5, 4, 1], "block": "minecraft:cobblestone_stairs[facing=west,half=top]" },
  { "type": "set_block", "pos": [7, 4, 1], "block": "minecraft:cobblestone_stairs[facing=east,half=top]" }
]
```

---

## 3. `gable_roof` + Gable Triangle Infill (`9x9` Mode B, `ridge_axis="z"`)

Places an overhanging A-frame roof starting at `y=5` (peak at `y=10`) and fills both `z=1` and `z=7` gable ends:

```json
[
  {
    "type": "gable_roof",
    "from": [0, 5, 0],
    "to": [8, 5, 8],
    "block": "minecraft:spruce_stairs",
    "ridge_block": "minecraft:spruce_slab[type=bottom]",
    "ridge_axis": "z",
    "overhang": 1
  },
  { "type": "fill_box", "from": [1, 6, 1], "to": [7, 6, 1], "block": "minecraft:oak_planks" },
  { "type": "fill_box", "from": [2, 7, 1], "to": [6, 7, 1], "block": "minecraft:oak_planks" },
  { "type": "fill_box", "from": [3, 8, 1], "to": [5, 8, 1], "block": "minecraft:oak_planks" },
  { "type": "set_block", "pos": [4, 9, 1], "block": "minecraft:oak_planks" },
  { "type": "fill_box", "from": [1, 6, 7], "to": [7, 6, 7], "block": "minecraft:oak_planks" },
  { "type": "fill_box", "from": [2, 7, 7], "to": [6, 7, 7], "block": "minecraft:oak_planks" },
  { "type": "fill_box", "from": [3, 8, 7], "to": [5, 8, 7], "block": "minecraft:oak_planks" },
  { "type": "set_block", "pos": [4, 9, 7], "block": "minecraft:oak_planks" }
]
```

*Tip:* Swap the `z=1` and `z=7` attic center blocks at `[4, 7, 1]` / `[4, 7, 7]` to `minecraft:glass_pane` for attic gable windows!

---

## 4. Multi-Story Stacking (`repeat_pattern` along `+Y`)

For a 2- or 3-story tower/hall with `4`-block floor intervals:
- Story 1 pillars/walls: `y = 0..4`
- Repeat structural ring along `step: [0, 4, 0]` (`count: 2` or `3`)
- Insert floor planks (`fill_box` inside `2..4N-2` at `y=4`, `y=8`) leaving a `clear_box` stairwell (`2x3` or `2x4`) for `staircase`.
- Add a stone chimney on an exterior bay edge (`fill_box` from `y=0` to `peakY + 2`, capped with `campfire` + `trapdoor` surround).
