# MCP Tools Reference

> **Status:** All 11 v1 Spatial Context, Blueprint Draft Session, and Litematica Compilation & Placement tools are implemented over Streamable HTTP at `http://127.0.0.1:25580/mcp` (override port with `-Darchica.mcp.port=<port>`).

Archica MCP exposes 11 tools grouped into **Spatial Context** (reading player surroundings) and **Stateful Blueprints & Litematica Activation** (drafting, refining, and compiling `.litematic` holograms).

---

## 1. Tool Inventory

| Category | Tool Name | Purpose |
|---|---|---|
| Context | `get_build_context` | Returns player pose, suggested anchor, inventory materials, and compact 2D terrain grid around the player. |
| Context | `scan_volume` | Returns RLE-compressed 3D Y-slices for a bounded world box (`min`..`max`). |
| Blueprint | `blueprint_create` | Creates or resets a named blueprint draft session with world `origin` and `facing`. |
| Blueprint | `blueprint_add_ops` | Appends a batch of building primitives and returns assigned `op_id`s + material summary. |
| Blueprint | `blueprint_update_op` | Replaces the parameters of an existing `op_id` in place for fast iteration. |
| Blueprint | `blueprint_remove_op` | Deletes a specific `op_id` from the blueprint session. |
| Blueprint | `blueprint_undo` | Pops the last `count` operations from the blueprint session. |
| Blueprint | `blueprint_inspect` | Returns current operations, bounding box, block counts, and survival material check without writing to disk. |
| Blueprint | `blueprint_list` | Lists all active in-memory blueprint drafts and active Litematica placements. |
| Litematica | `blueprint_compile` | Rasterizes the blueprint, writes `.minecraft/schematics/archica/<blueprint_id>.litematic`, and activates/replaces the Litematica placement. |
| Litematica | `blueprint_clear_placement` | Removes the active Litematica hologram placement for `blueprint_id` (optionally deleting the `.litematic` file). |

---

## 2. Spatial Context Tools

### `get_build_context`
Scans the outdoor player-radius region (see [Spatial Context Compression](spatial-context.md)).

**Input Schema:**
```json
{
  "type": "object",
  "properties": {
    "radius": { "type": "integer", "minimum": 4, "maximum": 24, "default": 12 }
  }
}
```

**Output:** Returns a concise text payload containing `[PLAYER]`, `[INVENTORY_MATERIALS]`, `[SURFACE_LEGEND]`, and aligned non-RLE `[TERRAIN_GRID]` as defined in [Spatial Context Compression](spatial-context.md).

---

### `scan_volume`
Performs an on-demand 3D inspection of a bounded box (recommended max `24 x 24 x 24`, hard cap `13,824` blocks).

**Input Schema:**
```json
{
  "type": "object",
  "required": ["min", "max"],
  "properties": {
    "min": { "type": "array", "items": { "type": "integer" }, "minItems": 3, "maxItems": 3 },
    "max": { "type": "array", "items": { "type": "integer" }, "minItems": 3, "maxItems": 3 },
    "include_states": { "type": "boolean", "default": false }
  }
}
```

**Output:** Returns `[VOLUME]`, `[PALETTE]` (bare block IDs or compact normalized blockstates when `include_states=true`), and RLE-encoded non-empty `y=<N>` slices.

---

## 3. Blueprint Session Tools

### `blueprint_create`
Initializes a stateful blueprint session anchored at `origin`.

**Input Schema:**
```json
{
  "type": "object",
  "required": ["blueprint_id", "origin"],
  "properties": {
    "blueprint_id": { "type": "string", "pattern": "^[a-z0-9_-]{1,48}$" },
    "origin": { "type": "array", "items": { "type": "integer" }, "minItems": 3, "maxItems": 3 },
    "facing": { "type": "string", "enum": ["NORTH", "SOUTH", "EAST", "WEST"], "default": "NORTH" },
    "overwrite": { "type": "boolean", "default": false }
  }
}
```

**Example Output:**
```json
{
  "blueprint_id": "oak_cottage",
  "origin": [124, 65, -52],
  "facing": "NORTH",
  "op_count": 0
}
```

---

### `blueprint_add_ops`
Appends one or more primitive operations (see [Stateful Blueprints & Building Primitives](blueprint-primitives.md)).

**Input Schema:**
```json
{
  "type": "object",
  "required": ["blueprint_id", "ops"],
  "properties": {
    "blueprint_id": { "type": "string" },
    "ops": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["type"],
        "properties": {
          "type": {
            "type": "string",
            "enum": [
              "set_block", "fill_box", "hollow_box", "line",
              "cylinder", "dome", "gable_roof", "staircase",
              "replace_box", "clear_box", "repeat_pattern"
            ]
          }
        },
        "additionalProperties": true
      }
    }
  }
}
```

**Example Output (with Survival Material Check):**
```json
{
  "blueprint_id": "oak_cottage",
  "added_op_ids": ["op_1", "op_2", "op_3"],
  "total_ops": 3,
  "bounding_box": { "min": [-1, 0, -1], "max": [9, 7, 9] },
  "total_blocks": 342,
  "materials": {
    "minecraft:cobblestone": 96,
    "minecraft:oak_planks": 148,
    "minecraft:oak_stairs": 98
  },
  "survival_check": {
    "game_mode": "survival",
    "missing_materials": {
      "minecraft:oak_stairs": 66
    }
  }
}
```

> **Note:** When `game_mode` is `survival`, `survival_check.missing_materials` compares required materials against the player's current inventory/hotbar. It is returned as a warning to guide AI material choices and does **not** block drafting or compiling a `.litematic` hologram.

---

### `blueprint_update_op`
Replaces an existing operation in place while preserving its order in the evaluation pipeline.

**Input Schema:**
```json
{
  "type": "object",
  "required": ["blueprint_id", "op_id", "op"],
  "properties": {
    "blueprint_id": { "type": "string" },
    "op_id": { "type": "string" },
    "op": { "type": "object", "required": ["type"], "additionalProperties": true }
  }
}
```

---

### `blueprint_remove_op`
Removes a single operation by `op_id`.

**Input Schema:**
```json
{
  "type": "object",
  "required": ["blueprint_id", "op_id"],
  "properties": {
    "blueprint_id": { "type": "string" },
    "op_id": { "type": "string" }
  }
}
```

---

### `blueprint_undo`
Removes the most recent `count` operations from the blueprint.

**Input Schema:**
```json
{
  "type": "object",
  "required": ["blueprint_id"],
  "properties": {
    "blueprint_id": { "type": "string" },
    "count": { "type": "integer", "minimum": 1, "default": 1 }
  }
}
```

---

### `blueprint_inspect`
Inspects the ordered `ops` list, local/world bounding box, material summary, and survival availability without writing a schematic file.

**Input Schema:**
```json
{
  "type": "object",
  "required": ["blueprint_id"],
  "properties": {
    "blueprint_id": { "type": "string" },
    "include_ops": { "type": "boolean", "default": true }
  }
}
```

---

### `blueprint_list`
Lists all active blueprint sessions in memory and whether each currently has an active Litematica placement.

**Input Schema:**
```json
{
  "type": "object",
  "properties": {}
}
```

---

## 4. Compilation & Litematica Tools

### `blueprint_compile`
Rasterizes the blueprint, writes `<schematicsBaseDir>/archica/<blueprint_id>.litematic` (supporting `activate = false` even when no world is loaded; rejecting empty blueprints with `INVALID_ARGUMENTS`), and—when `activate=true`—loads and places it in Litematica at `world_origin` (`worldBounds.min`, shifted by `origin_override` when supplied), replacing any prior `archica:<blueprint_id>` placement.

**Input Schema:**
```json
{
  "type": "object",
  "required": ["blueprint_id"],
  "properties": {
    "blueprint_id": { "type": "string", "pattern": "^[a-z0-9_-]{1,48}$" },
    "activate": { "type": "boolean", "default": true },
    "origin_override": { "type": "array", "items": { "type": "integer" }, "minItems": 3, "maxItems": 3 }
  }
}
```

**Example Output:**
```json
{
  "blueprint_id": "oak_cottage",
  "schematic_path": "schematics/archica/oak_cottage.litematic",
  "activated": true,
  "replaced_existing_placement": true,
  "world_origin": [123, 65, -62],
  "enclosing_size": [11, 8, 11],
  "total_blocks": 342,
  "survival_check": {
    "game_mode": "survival",
    "missing_materials": {
      "minecraft:oak_stairs": 66
    }
  }
}
```

---

### `blueprint_clear_placement`
Removes the active Litematica placement tagged `archica:<blueprint_id>` and optionally deletes its `.litematic` file.

**Input Schema:**
```json
{
  "type": "object",
  "required": ["blueprint_id"],
  "properties": {
    "blueprint_id": { "type": "string", "pattern": "^[a-z0-9_-]{1,48}$" },
    "delete_file": { "type": "boolean", "default": false }
  }
}
```

**Example Output:**
```json
{
  "blueprint_id": "oak_cottage",
  "cleared": true,
  "deleted_file": false
}
```

---

## 5. Standard Error Payloads

All tools return structured MCP errors (`isError = true`) with a machine-readable `code` and human-readable `message`:

```json
{
  "error": {
    "code": "NO_WORLD_LOADED",
    "message": "Minecraft client is not currently in a world (title screen or disconnecting)."
  }
}
```

| Error Code | Condition |
|---|---|
| `NO_WORLD_LOADED` | `client.world` or `client.player` is null when a world-reading or placement tool is called. |
| `INVALID_RADIUS` | Requested `get_build_context` `radius` is outside `4..24`. |
| `INVALID_BOUNDS` | Requested `scan_volume` `min[i] > max[i]` on any axis. |
| `VOLUME_TOO_LARGE` | Requested `scan_volume` (`13,824` blocks) or blueprint rasterization enclosing volume (`131,072` blocks) exceeds the safety cap. |
| `INVALID_ARGUMENTS` | Required MCP tool arguments are missing or have invalid JSON types/shapes. |
| `CLIENT_TIMEOUT` | Main-thread world dispatch exceeded its bounded timeout (default `5,000ms`). |
| `INTERNAL_ERROR` | Unexpected unhandled error while executing an MCP tool. |
| `BLUEPRINT_EXISTS` | `blueprint_create` called for an existing `blueprint_id` with `overwrite=false`. |
| `BLUEPRINT_NOT_FOUND` | Operation targets an unknown `blueprint_id`. |
| `OP_NOT_FOUND` | `blueprint_update_op` or `blueprint_remove_op` references a missing `op_id`. |
| `INVALID_BLOCK_ID` | A primitive references an unknown block identifier or invalid blockstate property. |
| `LITEMATICA_UNAVAILABLE` | Litematica / MaLiLib failed to initialize or placement registration/clearance threw an adapter error (the `.litematic` file path is included in the error message if already written). |

---

## 6. Related Documents

- [Architecture & Litematica Integration](architecture.md)
- [Spatial Context Compression](spatial-context.md)
- [Stateful Blueprints & Building Primitives](blueprint-primitives.md)
