package com.archica.mcp.server

import com.archica.mcp.blueprint.BlueprintBounds
import com.archica.mcp.blueprint.BlueprintCreateRequest
import com.archica.mcp.blueprint.BlueprintCreateResult
import com.archica.mcp.blueprint.BlueprintInspectResult
import com.archica.mcp.blueprint.BlueprintListResult
import com.archica.mcp.blueprint.BlueprintMutationResult
import com.archica.mcp.blueprint.PrimitiveOp
import com.archica.mcp.blueprint.RidgeAxis
import com.archica.mcp.blueprint.StoredBlueprintOp
import com.archica.mcp.blueprint.SurvivalMaterialCheck
import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import com.archica.mcp.server.JsonArgumentSupport.optionalBoolean
import com.archica.mcp.server.JsonArgumentSupport.optionalInt
import com.archica.mcp.server.JsonArgumentSupport.optionalString
import com.archica.mcp.server.JsonArgumentSupport.requireArray
import com.archica.mcp.server.JsonArgumentSupport.requireBlockPos3i
import com.archica.mcp.server.JsonArgumentSupport.requireInt
import com.archica.mcp.server.JsonArgumentSupport.requireObject
import com.archica.mcp.server.JsonArgumentSupport.requireString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Parsed payloads for blueprint MCP tools. */
data class BlueprintAddOpsRequest(val blueprintId: String, val ops: List<PrimitiveOp>)
data class BlueprintUpdateOpRequest(val blueprintId: String, val opId: String, val op: PrimitiveOp)
data class BlueprintRemoveOpRequest(val blueprintId: String, val opId: String)
data class BlueprintUndoRequest(val blueprintId: String, val count: Int)
data class BlueprintInspectRequest(val blueprintId: String, val includeOps: Boolean)

/** Strict JSON argument parsers and compact JSON response formatters for blueprint MCP tools. */
object BlueprintToolCodec {
    private val json = Json { encodeDefaults = true }

    fun parseCreate(arguments: JsonObject?): BlueprintCreateRequest {
        val args = arguments ?: throw InvalidToolArgumentsException("Missing required arguments: 'blueprint_id' and 'origin'.")
        val blueprintId = args.requireString("blueprint_id")
        val origin = args["origin"].requireBlockPos3i("origin")
        val facingRaw = args.optionalString("facing")
        val facing = if (facingRaw == null) {
            CardinalDirection.NORTH
        } else {
            runCatching { CardinalDirection.valueOf(facingRaw.uppercase()) }.getOrElse {
                throw InvalidToolArgumentsException(
                    "Invalid facing '$facingRaw': expected NORTH, SOUTH, EAST, or WEST.",
                )
            }
        }
        val overwrite = args.optionalBoolean("overwrite", default = false)
        return BlueprintCreateRequest(
            blueprintId = blueprintId,
            origin = origin,
            facing = facing,
            overwrite = overwrite,
        )
    }

    fun parseAddOps(arguments: JsonObject?): BlueprintAddOpsRequest {
        val args = arguments ?: throw InvalidToolArgumentsException("Missing required arguments: 'blueprint_id' and 'ops'.")
        val blueprintId = args.requireString("blueprint_id")
        val opsArray = args.requireArray("ops")
        if (opsArray.isEmpty()) {
            throw InvalidToolArgumentsException("Parameter 'ops' must contain at least one operation.")
        }
        val ops = opsArray.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw InvalidToolArgumentsException("Element 'ops[$index]' must be a JSON object.")
            parsePrimitiveOp(obj, path = "ops[$index]")
        }
        return BlueprintAddOpsRequest(blueprintId = blueprintId, ops = ops)
    }

    fun parseUpdateOp(arguments: JsonObject?): BlueprintUpdateOpRequest {
        val args = arguments ?: throw InvalidToolArgumentsException("Missing required arguments: 'blueprint_id', 'op_id', and 'op'.")
        return BlueprintUpdateOpRequest(
            blueprintId = args.requireString("blueprint_id"),
            opId = args.requireString("op_id"),
            op = parsePrimitiveOp(args.requireObject("op"), path = "op"),
        )
    }

    fun parseRemoveOp(arguments: JsonObject?): BlueprintRemoveOpRequest {
        val args = arguments ?: throw InvalidToolArgumentsException("Missing required arguments: 'blueprint_id' and 'op_id'.")
        return BlueprintRemoveOpRequest(
            blueprintId = args.requireString("blueprint_id"),
            opId = args.requireString("op_id"),
        )
    }

    fun parseUndo(arguments: JsonObject?): BlueprintUndoRequest {
        val args = arguments ?: throw InvalidToolArgumentsException("Missing required argument: 'blueprint_id'.")
        return BlueprintUndoRequest(
            blueprintId = args.requireString("blueprint_id"),
            count = args.optionalInt("count", default = 1),
        )
    }

    fun parseInspect(arguments: JsonObject?): BlueprintInspectRequest {
        val args = arguments ?: throw InvalidToolArgumentsException("Missing required argument: 'blueprint_id'.")
        return BlueprintInspectRequest(
            blueprintId = args.requireString("blueprint_id"),
            includeOps = args.optionalBoolean("include_ops", default = true),
        )
    }

    fun parsePrimitiveOp(obj: JsonObject, path: String = "op"): PrimitiveOp {
        val type = obj.requireString("type")
        return when (type) {
            "set_block" -> PrimitiveOp.SetBlock(
                pos = obj["pos"].requireBlockPos3i("$path.pos"),
                block = obj.requireString("block"),
            )
            "fill_box" -> PrimitiveOp.FillBox(
                from = obj["from"].requireBlockPos3i("$path.from"),
                to = obj["to"].requireBlockPos3i("$path.to"),
                block = obj.requireString("block"),
            )
            "hollow_box" -> PrimitiveOp.HollowBox(
                from = obj["from"].requireBlockPos3i("$path.from"),
                to = obj["to"].requireBlockPos3i("$path.to"),
                block = obj.requireString("block"),
                wallsOnly = obj.optionalBoolean("walls_only", default = false),
            )
            "line" -> PrimitiveOp.Line(
                from = obj["from"].requireBlockPos3i("$path.from"),
                to = obj["to"].requireBlockPos3i("$path.to"),
                block = obj.requireString("block"),
            )
            "cylinder" -> PrimitiveOp.Cylinder(
                center = obj["center"].requireBlockPos3i("$path.center"),
                radius = obj.requireInt("radius"),
                height = obj.requireInt("height"),
                block = obj.requireString("block"),
                hollow = obj.optionalBoolean("hollow", default = false),
            )
            "dome" -> PrimitiveOp.Dome(
                center = obj["center"].requireBlockPos3i("$path.center"),
                radius = obj.requireInt("radius"),
                block = obj.requireString("block"),
                hollow = obj.optionalBoolean("hollow", default = true),
            )
            "gable_roof" -> {
                val axisRaw = obj.requireString("ridge_axis")
                val ridgeAxis = when (axisRaw.lowercase()) {
                    "x" -> RidgeAxis.X
                    "z" -> RidgeAxis.Z
                    else -> throw InvalidToolArgumentsException(
                        "Invalid '$path.ridge_axis' value '$axisRaw': expected 'x' or 'z'.",
                    )
                }
                PrimitiveOp.GableRoof(
                    from = obj["from"].requireBlockPos3i("$path.from"),
                    to = obj["to"].requireBlockPos3i("$path.to"),
                    block = obj.requireString("block"),
                    ridgeBlock = obj.optionalString("ridge_block"),
                    ridgeAxis = ridgeAxis,
                    overhang = obj.optionalInt("overhang", default = 1),
                )
            }
            "staircase" -> PrimitiveOp.Staircase(
                from = obj["from"].requireBlockPos3i("$path.from"),
                to = obj["to"].requireBlockPos3i("$path.to"),
                block = obj.requireString("block"),
                width = obj.optionalInt("width", default = 1),
                fillUnder = obj.optionalBoolean("fill_under", default = false),
            )
            "replace_box" -> PrimitiveOp.ReplaceBox(
                from = obj["from"].requireBlockPos3i("$path.from"),
                to = obj["to"].requireBlockPos3i("$path.to"),
                target = obj.requireString("target"),
                replacement = obj.requireString("replacement"),
            )
            "clear_box" -> PrimitiveOp.ClearBox(
                from = obj["from"].requireBlockPos3i("$path.from"),
                to = obj["to"].requireBlockPos3i("$path.to"),
            )
            "repeat_pattern" -> PrimitiveOp.RepeatPattern(
                op = parsePrimitiveOp(obj.requireObject("op"), path = "$path.op"),
                count = obj.requireInt("count"),
                step = obj["step"].requireBlockPos3i("$path.step"),
            )
            else -> throw InvalidToolArgumentsException("Unknown primitive operation type '$type' at '$path.type'.")
        }
    }

    fun formatCreate(result: BlueprintCreateResult): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("blueprint_id", result.blueprintId)
            putPos("origin", result.origin)
            put("facing", result.facing.name)
            put("op_count", result.opCount)
        },
    )

    fun formatMutation(result: BlueprintMutationResult): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("blueprint_id", result.blueprintId)
            if (result.addedOpIds.isNotEmpty()) {
                putJsonArray("added_op_ids") { result.addedOpIds.forEach { add(it) } }
            }
            result.updatedOpId?.let { put("updated_op_id", it) }
            if (result.removedOpIds.isNotEmpty() || (result.addedOpIds.isEmpty() && result.updatedOpId == null)) {
                putJsonArray("removed_op_ids") { result.removedOpIds.forEach { add(it) } }
            }
            put("total_ops", result.totalOps)
            putBoundsOrNull("bounding_box", result.boundingBox)
            put("total_blocks", result.totalBlocks)
            putMaterials(result.materials)
            result.survivalCheck?.let { putSurvivalCheck(it) }
        },
    )

    fun formatInspect(result: BlueprintInspectResult): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("blueprint_id", result.blueprintId)
            putPos("origin", result.origin)
            put("facing", result.facing.name)
            put("total_ops", result.totalOps)
            result.ops?.let { ops ->
                putJsonArray("ops") { ops.forEach { add(storedOpToJson(it)) } }
            }
            putBoundsOrNull("bounding_box", result.localBoundingBox)
            putBoundsOrNull("world_bounding_box", result.worldBoundingBox)
            put("total_blocks", result.totalBlocks)
            putMaterials(result.materials)
            result.survivalCheck?.let { putSurvivalCheck(it) }
        },
    )

    fun formatList(result: BlueprintListResult): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonArray("blueprints") {
                for (item in result.blueprints) {
                    add(
                        buildJsonObject {
                            put("blueprint_id", item.blueprintId)
                            putPos("origin", item.origin)
                            put("facing", item.facing.name)
                            put("op_count", item.opCount)
                            put("has_active_placement", item.hasActivePlacement)
                        },
                    )
                }
            }
        },
    )

    private fun storedOpToJson(stored: StoredBlueprintOp): JsonObject = buildJsonObject {
        put("op_id", stored.opId)
        putPrimitiveFields(stored.op)
    }

    private fun JsonObjectBuilder.putPrimitiveFields(op: PrimitiveOp) {
        put("type", op.typeName)
        when (op) {
            is PrimitiveOp.SetBlock -> {
                putPos("pos", op.pos)
                put("block", op.block)
            }
            is PrimitiveOp.FillBox -> {
                putPos("from", op.from)
                putPos("to", op.to)
                put("block", op.block)
            }
            is PrimitiveOp.HollowBox -> {
                putPos("from", op.from)
                putPos("to", op.to)
                put("block", op.block)
                put("walls_only", op.wallsOnly)
            }
            is PrimitiveOp.Line -> {
                putPos("from", op.from)
                putPos("to", op.to)
                put("block", op.block)
            }
            is PrimitiveOp.Cylinder -> {
                putPos("center", op.center)
                put("radius", op.radius)
                put("height", op.height)
                put("block", op.block)
                put("hollow", op.hollow)
            }
            is PrimitiveOp.Dome -> {
                putPos("center", op.center)
                put("radius", op.radius)
                put("block", op.block)
                put("hollow", op.hollow)
            }
            is PrimitiveOp.GableRoof -> {
                putPos("from", op.from)
                putPos("to", op.to)
                put("block", op.block)
                op.ridgeBlock?.let { put("ridge_block", it) }
                put("ridge_axis", op.ridgeAxis.name.lowercase())
                put("overhang", op.overhang)
            }
            is PrimitiveOp.Staircase -> {
                putPos("from", op.from)
                putPos("to", op.to)
                put("block", op.block)
                put("width", op.width)
                put("fill_under", op.fillUnder)
            }
            is PrimitiveOp.ReplaceBox -> {
                putPos("from", op.from)
                putPos("to", op.to)
                put("target", op.target)
                put("replacement", op.replacement)
            }
            is PrimitiveOp.ClearBox -> {
                putPos("from", op.from)
                putPos("to", op.to)
            }
            is PrimitiveOp.RepeatPattern -> {
                putJsonObject("op") { putPrimitiveFields(op.op) }
                put("count", op.count)
                putPos("step", op.step)
            }
        }
    }

    private fun JsonObjectBuilder.putPos(key: String, pos: BlockPos3i) {
        putJsonArray(key) {
            add(pos.x)
            add(pos.y)
            add(pos.z)
        }
    }

    private fun JsonObjectBuilder.putBoundsOrNull(key: String, bounds: BlueprintBounds?) {
        if (bounds == null) {
            put(key, kotlinx.serialization.json.JsonNull)
            return
        }
        putJsonObject(key) {
            putPos("min", bounds.min)
            putPos("max", bounds.max)
        }
    }

    private fun JsonObjectBuilder.putMaterials(materials: Map<String, Int>) {
        putJsonObject("materials") {
            for ((blockId, count) in materials) {
                put(blockId, count)
            }
        }
    }

    private fun JsonObjectBuilder.putSurvivalCheck(check: SurvivalMaterialCheck) {
        putJsonObject("survival_check") {
            put("game_mode", check.gameMode)
            putJsonObject("missing_materials") {
                for ((blockId, count) in check.missingMaterials) {
                    put(blockId, count)
                }
            }
        }
    }
}
