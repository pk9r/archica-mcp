# Survival Material Substitutions

Use these fallback chains when `survival_check.missing_materials` flags shortages in `[INVENTORY_MATERIALS]`:

| Architectural Role | Preferred | Survival Fallback 1 | Survival Fallback 2 |
|---|---|---|---|
| **Foundation / Plinth** | `minecraft:stone_bricks` | `minecraft:cobblestone` | `minecraft:cobbled_deepslate` / `minecraft:dirt` (temporary) |
| **Grid Pillars (`4i`)** | `minecraft:oak_log` / `minecraft:spruce_log` | Any local biome `*_log` | `minecraft:cobblestone_wall` or `minecraft:cobblestone` |
| **Wall Infill** | `minecraft:white_terracotta` / `minecraft:calcite` | `minecraft:oak_planks` / `minecraft:birch_planks` | `minecraft:cobblestone` / `minecraft:sandstone` |
| **Roof Slopes** | `minecraft:spruce_stairs` / `minecraft:deepslate_tile_stairs` | `minecraft:oak_stairs` / `minecraft:cobblestone_stairs` | `minecraft:*_slab` steps (requires half the wood/stone) |
| **Windows** | `minecraft:glass_pane` | `minecraft:oak_fence` (medieval open shutters) | `minecraft:glass` |
| **Lighting** | `minecraft:lantern` | `minecraft:torch` / `minecraft:wall_torch` | `minecraft:glowstone` / `minecraft:campfire` |

*Survival efficiency tips:*
- Crafting `stairs` yields `4` stairs per `6` full blocks (`1.5x` cost), whereas `slabs` yield `6` slabs per `3` blocks (`0.5x` cost). If the player is short on wood or stone, step a roof or sill with slabs or reduce `overhang` from `1` to `0`.
- Remember `get_build_context` reads only carried hotbar + main inventory; if the player has chests nearby, ask whether they want a smaller draft or already have chest reserves before downsizing.
