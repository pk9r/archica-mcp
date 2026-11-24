package com.archica.mcp.server

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.BuildContextRequest
import com.archica.mcp.context.BuildContextScanner
import com.archica.mcp.context.ScanVolumeRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Strict JSON argument parsers for spatial context MCP tools. */
object SpatialToolArguments {
    fun parseGetBuildContext(arguments: JsonObject?): BuildContextRequest {
        val radiusElement = arguments?.get("radius")
        if (radiusElement == null || radiusElement is JsonNull) {
            return BuildContextRequest(radius = BuildContextScanner.DEFAULT_RADIUS)
        }
        return BuildContextRequest(radius = radiusElement.requireInt("radius"))
    }

    fun parseScanVolume(arguments: JsonObject?): ScanVolumeRequest {
        if (arguments == null) {
            throw InvalidToolArgumentsException("Missing required arguments: 'min' and 'max'.")
        }
        val min = arguments["min"].requireBlockPos3i("min")
        val max = arguments["max"].requireBlockPos3i("max")
        val includeStatesElement = arguments["include_states"]
        val includeStates = if (includeStatesElement == null || includeStatesElement is JsonNull) {
            false
        } else {
            includeStatesElement.requireBoolean("include_states")
        }
        return ScanVolumeRequest(min = min, max = max, includeStates = includeStates)
    }

    private fun JsonElement?.requireBlockPos3i(fieldName: String): BlockPos3i {
        if (this == null || this is JsonNull) {
            throw InvalidToolArgumentsException("Missing required parameter '$fieldName'.")
        }
        if (this !is JsonArray || size != 3) {
            throw InvalidToolArgumentsException("Parameter '$fieldName' must be an array of 3 integers [x, y, z].")
        }
        return BlockPos3i(
            x = this[0].requireInt("$fieldName[0]"),
            y = this[1].requireInt("$fieldName[1]"),
            z = this[2].requireInt("$fieldName[2]"),
        )
    }

    private fun JsonElement.requireInt(fieldName: String): Int {
        val primitive = this as? JsonPrimitive
        val parsed = if (primitive != null && !primitive.isString) primitive.intOrNull else null
        return parsed ?: throw InvalidToolArgumentsException("Parameter '$fieldName' must be an integer.")
    }

    private fun JsonElement.requireBoolean(fieldName: String): Boolean {
        val primitive = this as? JsonPrimitive
        val parsed = if (primitive != null && !primitive.isString) primitive.booleanOrNull else null
        return parsed ?: throw InvalidToolArgumentsException("Parameter '$fieldName' must be a boolean.")
    }
}
