package com.archica.mcp.server

import com.archica.mcp.context.BlockPos3i
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Shared strict JSON extraction helpers for MCP tool argument parsing. */
internal object JsonArgumentSupport {
    fun JsonObject.requireString(fieldName: String): String {
        val el = this[fieldName]
        if (el == null || el is JsonNull) {
            throw InvalidToolArgumentsException("Missing required parameter '$fieldName'.")
        }
        val prim = el as? JsonPrimitive
        if (prim == null || !prim.isString) {
            throw InvalidToolArgumentsException("Parameter '$fieldName' must be a string.")
        }
        return prim.content
    }

    fun JsonObject.optionalString(fieldName: String): String? {
        val el = this[fieldName]
        if (el == null || el is JsonNull) return null
        val prim = el as? JsonPrimitive
        if (prim == null || !prim.isString) {
            throw InvalidToolArgumentsException("Parameter '$fieldName' must be a string.")
        }
        return prim.content
    }

    fun JsonObject.requireObject(fieldName: String): JsonObject {
        val el = this[fieldName]
        if (el == null || el is JsonNull) {
            throw InvalidToolArgumentsException("Missing required parameter '$fieldName'.")
        }
        return el as? JsonObject
            ?: throw InvalidToolArgumentsException("Parameter '$fieldName' must be a JSON object.")
    }

    fun JsonObject.requireArray(fieldName: String): JsonArray {
        val el = this[fieldName]
        if (el == null || el is JsonNull) {
            throw InvalidToolArgumentsException("Missing required parameter '$fieldName'.")
        }
        return el as? JsonArray
            ?: throw InvalidToolArgumentsException("Parameter '$fieldName' must be a JSON array.")
    }

    fun JsonElement?.requireBlockPos3i(fieldName: String): BlockPos3i {
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

    fun JsonObject.requireInt(fieldName: String): Int {
        val el = this[fieldName]
        if (el == null || el is JsonNull) {
            throw InvalidToolArgumentsException("Missing required parameter '$fieldName'.")
        }
        return el.requireInt(fieldName)
    }

    fun JsonObject.optionalInt(fieldName: String, default: Int): Int {
        val el = this[fieldName]
        if (el == null || el is JsonNull) return default
        return el.requireInt(fieldName)
    }

    fun JsonObject.optionalBoolean(fieldName: String, default: Boolean): Boolean {
        val el = this[fieldName]
        if (el == null || el is JsonNull) return default
        return el.requireBoolean(fieldName)
    }

    fun JsonElement.requireInt(fieldName: String): Int {
        val primitive = this as? JsonPrimitive
        val parsed = if (primitive != null && !primitive.isString) primitive.intOrNull else null
        return parsed ?: throw InvalidToolArgumentsException("Parameter '$fieldName' must be an integer.")
    }

    fun JsonElement.requireBoolean(fieldName: String): Boolean {
        val primitive = this as? JsonPrimitive
        val parsed = if (primitive != null && !primitive.isString) primitive.booleanOrNull else null
        return parsed ?: throw InvalidToolArgumentsException("Parameter '$fieldName' must be a boolean.")
    }
}
