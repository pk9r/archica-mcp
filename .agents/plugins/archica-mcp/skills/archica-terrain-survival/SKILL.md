---
name: archica-terrain-survival
description: >-
  Guides site selection, slope and water foundation engineering, and survival material
  adaptation using Archica MCP's get_build_context terrain grid and inventory checks.
  Use when building on uneven terrain, near water, or in Minecraft survival mode.
---

# Terrain Adaptation & Survival Planning

Always inspect the real ground and inventory from `get_build_context` before placing a `5x5` blueprint. Never let a structure float over slopes or ignore survival inventory shortages.

## 1. Reading `get_build_context` Output

`get_build_context(radius=12)` returns four sections:
1. **`[PLAYER]`**: `pos=(x, y, z)`, `facing`, `biome`, `mode` (`survival` | `creative` | ...), and `suggested_origin=(ox, oy, oz)` (`ground_y + 1`, ~10 blocks ahead of the player on solid non-water, non-leaf ground).
2. **`[INVENTORY_MATERIALS]`**: Aggregated block counts in hotbar + main inventory (e.g., `oak_planks:192, cobblestone:128`).
3. **`[SURFACE_LEGEND]`**: 1-to-1 symbol mapping (`.` = `grass_block`, `~` = `water`, `,` = `dirt`, `:` = `sand`, `#` = `cobblestone`, `S` = `stone`, `T` = `oak_log`, `L` = `oak_leaves`).
4. **`[TERRAIN_GRID origin=(minX, minZ) size=WxH ref_y=playerY]`**: Non-RLE grid of `<symbol><deltaY>` (`?` if no surface in `[playerY - 16, playerY + 24]`).
   - Absolute surface Y at cell `(x, z)` = `ref_y + deltaY`.
   - Adjacent columns change `x` by `+1` left-to-right; rows advance `z` by `+1` top-to-bottom.

---

## 2. Site Placement & Slope Foundations

Before calling `blueprint_create`:
1. **Check footprint footprint vs `[TERRAIN_GRID]`**:
   - Rotate your planned `(4*Nx + 1) x (4*Nz + 1)` footprint into world coordinates for candidate `origin` + `facing`.
   - Find `min_ground_y` and `max_ground_y` across the footprint, plus any `~` (`water`) or `L` (`leaves`) cells.
2. **Avoid trees/water when flat land exists nearby**: Shift `origin` by a few blocks or request `radius=18..24` if the `radius=12` patch is obstructed.
3. **Choose an anchor strategy**:
   - **Gentle slope (`delta <= 2`)**: Set `origin.y = max_ground_y + 1` (so the ground floor never gets buried) and project a stone foundation skirt from `y = -1` down to `y = -(max_ground_y - min_ground_y + 1)` using `fill_box` / `hollow_box`.
   - **Steep slope or waterfront (`~`)**: Keep the `4i` Shared-Pillar grid and extend wooden/stone support piers (`repeat_pattern` of `line` ops from `y = 0` down to `y = -4` or `-6`) into the slope or riverbed, adding cross-bracing beams at `y = -1`.
   - **Clear high bumps inside floor**: If `origin.y` is set to the average ground level instead of `max_ground_y + 1`, prepend a `clear_box` at `y = 0..4` so the hologram guides the player on what dirt/grass to excavate.

---

## 3. Survival Material Adaptation

When `[PLAYER]` shows `mode=survival`:
1. **Plan within `[INVENTORY_MATERIALS]` first**: Scale bay count (`1x1` or `2x1` instead of `3x3`) or swap rare decorative blocks for abundant carried materials before drafting.
2. **Watch `survival_check.missing_materials`**: Every `blueprint_add_ops`, `blueprint_inspect`, and `blueprint_compile` response compares required blocks against the player's inventory without blocking compilation.
3. **Adapt or inform clearly**:
   - Either swap missing blocks via `blueprint_update_op` / `replace_box` using available equivalents, **or**
   - Keep the hologram active in Litematica (which acts as a shopping list) and clearly summarize the missing counts to the player.
4. Read [Survival Material Substitutions](./references/survival-substitutions.md) for fast, biome-appropriate fallback chains.
