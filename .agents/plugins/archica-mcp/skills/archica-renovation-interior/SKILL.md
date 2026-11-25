---
name: archica-renovation-interior
description: >-
  Guides inspecting existing Minecraft structures with scan_volume, snapping 5x5
  Shared-Pillar extensions onto built facades, framing multi-story interiors and
  staircases, and upgrading or weathering materials with replace_box. Use when
  remodeling, extending, furnishing, or detailing an existing build.
---

# Renovation, Extensions & Interior Detailing

Use `scan_volume` to measure what already exists in the world before drafting any extension, interior floor, or material upgrade. Always pair with `archica-mcp-workflow` (and `archica-5x5-architecture` for structural wings).

## 1. Surveying Existing Builds with `scan_volume`

1. Estimate the existing building's world bounding box from `get_build_context` (`[TERRAIN_GRID]` roof/wall height + `[PLAYER] pos`).
2. Call `scan_volume(min, max, include_states=true)`:
   - Keep total box volume `<= 13,824` blocks (e.g., max `24 x 24 x 24`). For larger builds, scan one wing or floor band at a time.
   - `include_states=true` preserves geometric properties (`facing`, `half`, `axis`) while stripping noisy defaults.
3. Decode the returned RLE Y-slices:
   - `[PALETTE]`: `0` is always `minecraft:air`.
   - Each non-empty `y=<N>` lists rows `z<Z>: count*id, ...` in **ascending X** starting from `min.x`.
   - Sum the `count` run lengths from `min.x` to locate exact wall corners, window openings, stair facings, and roof ridge lines.

---

## 2. Snapping 5x5 Extensions & Wings

When adding a porch, tower, or side wing onto an existing structure:
1. Measure the existing wall face's world `(x, y, z)`, ground plinth Y, eave Y, and ridge orientation.
2. Anchor `blueprint_create(origin=...)` directly on an existing corner or pillar intersection so `local (0, 0, 0)` aligns cleanly with the existing plinth.
3. Match `blueprint.facing` to the extension's outward direction (`+Z` extending away from the existing wall).
4. Adopt a `4*N + 1` Shared-Pillar width that aligns with the existing eave height so the new `gable_roof` either ties neatly into the existing roof slope or forms a clean perpendicular cross-gable.

---

## 3. Interior Floors & `staircase` Framing

Make multi-story interiors walkable and proportioned:
- **Floor-to-floor clearance**: With a `4`-block story interval (`y=0` floor, `y=1..3` headroom, `y=4` ceiling/next floor) or `5`-block interval (`4` blocks clear headroom), place upper floor decks (`fill_box`) inside the interior bounds (`2..4N-2` for Mode B, `1..3` for Mode A).
- **Stairwell cutout**: Carve a `clear_box` opening through the upper floor deck along the stair path (at least `3`–`4` blocks long along the climb axis so the player never hits their head on the ceiling).
- **`staircase` parameters**:
  - `from` = bottom step `[x, y, z]`, `to` = top step `[x, y, z]`.
  - Set `width=2` for main halls (`1` for compact `5x5` towers).
  - Set `fill_under=true` for solid stone/wood under-stair support (great for storage nooks or exterior stoops), or `fill_under=false` for open timber flights.
- **Interior zoning**: Use `oak_fence`, `bookshelf`, `barrel`, `crafting_table`, `furnace`, `bed`, `lantern`, and `carpet` around perimeter walls while keeping a `2`-block walkway through doorways and stair landings.

---

## 4. Material Upgrades & Weathering (`replace_box`)

When upgrading or aging an existing section scanned via `scan_volume`:
- Scan the target bounds, then place a blueprint anchored at `min` (`facing="NORTH"` or explicit world-aligned bounds) and use `replace_box` or `set_block` to swap target blocks:
  - **Bare-ID wildcard matching**: `target: "minecraft:oak_stairs"` matches *all* `oak_stairs` regardless of `facing`/`half`, whereas `target: "minecraft:oak_stairs[half=bottom]"` matches only bottom-half stairs.
  - Note: `replace_box` operates on blocks already inside the blueprint's `VoxelGrid`. For existing world structures, either reconstruct the target shell first from `scan_volume` data or draft an overlay replacement schematic for the walls/roof being remodeled.
