# Material Palettes & Texture Gradients

Use a **60 / 30 / 10 rule** on every 5x5 build:
- **60% Primary Wall & Roof Mass** (calm, readable surfaces)
- **30% Structural Frame & Plinth** (high-contrast corner pillars, crossbeams, foundation)
- **10% Accents & Relief** (trapdoors, fences, lanterns, sills, foliage)

---

## 1. Curated Palettes

| Style | Plinth (`y <= 0`) | Frame (`4i` pillars/beams) | Wall Infill | Roof (`gable_roof` + ridge) | Trim / Windows |
|---|---|---|---|---|---|
| **Oak & Cobble Cottage** | `cobblestone`, `stone_bricks` | `oak_log`, `spruce_log` | `oak_planks`, `white_terracotta` | `spruce_stairs`, `spruce_slab` | `glass_pane`, `spruce_trapdoor`, `lantern` |
| **Medieval Stone & Deepslate** | `deepslate_bricks`, `cobbled_deepslate` | `spruce_log`, `stripped_spruce_log` | `white_concrete_powder`, `calcite`, `stone_bricks` | `deepslate_tile_stairs`, `deepslate_tile_slab` | `gray_stained_glass_pane`, `iron_bars`, `chain` |
| **Spruce Taiga Lodge** | `mossy_cobblestone`, `cobblestone` | `spruce_log`, `dark_oak_log` | `stripped_oak_log`, `spruce_planks` | `dark_oak_stairs`, `cobblestone_stairs` | `oak_fence`, `campfire`, `lantern` |
| **Desert Sandstone** | `cut_sandstone`, `smooth_sandstone` | `stripped_birch_log`, `sandstone_wall` | `sandstone`, `terracotta` | `acacia_stairs` / flat parapet (`dome` accents) | `orange_stained_glass_pane`, `oak_trapdoor` |
| **Dark Oak & Copper Manor** | `stone_bricks`, `polished_andesite` | `dark_oak_log` | `birch_planks`, `smooth_quartz` | `oxidized_cut_copper_stairs`, `deepslate_brick_stairs` | `black_stained_glass_pane`, `dark_oak_fence` |

*(Prefix every block ID with `minecraft:` in MCP calls.)*

---

## 2. Weathering & Gradient Technique (`replace_box`)

Flat monochrome stone or plank walls look artificial. After placing structural walls, apply 2–3 targeted `replace_box` or scattered `set_block` swaps:
- **Base Dampness (`y = 0..1`)**: Swap `cobblestone` -> `mossy_cobblestone` or `stone_bricks` -> `cracked_stone_bricks` / `mossy_stone_bricks` near ground and water.
- **Wall Texture**: Mix `stone` + `andesite` + `cobblestone`, or `white_terracotta` + `calcite` for aged plaster.
- **Roof Contrast**: Trim outer gable edges (`z = minZ - overhang` and `z = maxZ + overhang`) with a darker stair material (e.g., `deepslate_brick_stairs` framing a `spruce_stairs` roof).
