package com.archica.mcp.server

import com.archica.mcp.context.BuildContextRequest
import com.archica.mcp.context.BuildContextScanner
import com.archica.mcp.context.ScanVolumeRequest
import com.archica.mcp.server.JsonArgumentSupport.optionalBoolean
import com.archica.mcp.server.JsonArgumentSupport.requireBlockPos3i
import com.archica.mcp.server.JsonArgumentSupport.requireInt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

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
        val includeStates = arguments.optionalBoolean("include_states", default = false)
        return ScanVolumeRequest(min = min, max = max, includeStates = includeStates)
    }
}
