package com.archica.mcp.server

import com.archica.mcp.ArchicaModInfo
import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.BuildContextRequest
import com.archica.mcp.context.BuildContextResult
import com.archica.mcp.context.ContextCompressionException
import com.archica.mcp.context.ContextErrorCode
import com.archica.mcp.context.ScanVolumeRequest
import com.archica.mcp.context.ScanVolumeResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerCoreTest {
    private val json = Json

    @Test
    fun `port config returns default when unset and accepts valid range`() {
        val warnings = mutableListOf<String>()
        assertEquals(
            ArchicaModInfo.DEFAULT_MCP_PORT,
            McpPortConfig.resolvePort(rawProperty = null, onWarning = warnings::add),
        )
        assertTrue(warnings.isEmpty())

        assertEquals(25581, McpPortConfig.resolvePort(rawProperty = " 25581 ", onWarning = warnings::add))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `port config warns and falls back on non-numeric or out-of-range values`() {
        listOf("", "abc", "0", "65536", "-1").forEach { invalid ->
            val warnings = mutableListOf<String>()
            val resolved = McpPortConfig.resolvePort(rawProperty = invalid, onWarning = warnings::add)
            assertEquals(ArchicaModInfo.DEFAULT_MCP_PORT, resolved)
            assertEquals(1, warnings.size, "Expected warning for '$invalid'")
        }
    }

    @Test
    fun `parseGetBuildContext handles omitted null and integer radius`() {
        assertEquals(BuildContextRequest(12), SpatialToolArguments.parseGetBuildContext(null))
        assertEquals(
            BuildContextRequest(12),
            SpatialToolArguments.parseGetBuildContext(JsonObject(mapOf("radius" to JsonNull))),
        )
        assertEquals(
            BuildContextRequest(8),
            SpatialToolArguments.parseGetBuildContext(buildJsonObject { put("radius", 8) }),
        )
    }

    @Test
    fun `parseGetBuildContext rejects non-integer radius`() {
        listOf(
            buildJsonObject { put("radius", "8") },
            buildJsonObject { put("radius", 8.5) },
            buildJsonObject { put("radius", true) },
        ).forEach { args ->
            assertFailsWith<InvalidToolArgumentsException> {
                SpatialToolArguments.parseGetBuildContext(args)
            }
        }
    }

    @Test
    fun `parseScanVolume validates required coordinates and optional include_states`() {
        val parsed = SpatialToolArguments.parseScanVolume(
            buildJsonObject {
                put("min", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(64)); add(JsonPrimitive(-2)) })
                put("max", buildJsonArray { add(JsonPrimitive(3)); add(JsonPrimitive(66)); add(JsonPrimitive(0)) })
                put("include_states", true)
            },
        )
        assertEquals(ScanVolumeRequest(BlockPos3i(1, 64, -2), BlockPos3i(3, 66, 0), includeStates = true), parsed)

        val defaultStates = SpatialToolArguments.parseScanVolume(
            buildJsonObject {
                put("min", buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive(0)); add(JsonPrimitive(0)) })
                put("max", buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive(0)); add(JsonPrimitive(0)) })
            },
        )
        assertFalse(defaultStates.includeStates)
    }

    @Test
    fun `parseScanVolume rejects missing or malformed coordinates and flags`() {
        val invalidCases = listOf(
            null,
            buildJsonObject {
                put("min", buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive(0)); add(JsonPrimitive(0)) })
            },
            buildJsonObject {
                put("min", buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive(0)) })
                put("max", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(1)); add(JsonPrimitive(1)) })
            },
            buildJsonObject {
                put("min", buildJsonArray { add(JsonPrimitive("0")); add(JsonPrimitive(0)); add(JsonPrimitive(0)) })
                put("max", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(1)); add(JsonPrimitive(1)) })
            },
            buildJsonObject {
                put("min", buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive(0)); add(JsonPrimitive(0)) })
                put("max", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(1)); add(JsonPrimitive(1)) })
                put("include_states", "true")
            },
        )

        invalidCases.forEach { args ->
            assertFailsWith<InvalidToolArgumentsException> {
                SpatialToolArguments.parseScanVolume(args)
            }
        }
    }

    @Test
    fun `execute maps validation context timeout and unexpected errors to unified json envelope`() = runBlocking {
        val invalidArgs = McpToolExecutor.execute {
            throw InvalidToolArgumentsException("bad radius")
        }
        assertErrorEnvelope(invalidArgs, McpErrorCode.INVALID_ARGUMENTS, "bad radius")

        val noWorld = McpToolExecutor.execute {
            throw ContextCompressionException(ContextErrorCode.NO_WORLD_LOADED, "not in world")
        }
        assertErrorEnvelope(noWorld, McpErrorCode.NO_WORLD_LOADED, "not in world")

        val invalidBounds = McpToolExecutor.execute {
            throw ContextCompressionException(ContextErrorCode.INVALID_BOUNDS, "min > max")
        }
        assertErrorEnvelope(invalidBounds, McpErrorCode.INVALID_BOUNDS, "min > max")

        val volumeTooLarge = McpToolExecutor.execute {
            throw ContextCompressionException(ContextErrorCode.VOLUME_TOO_LARGE, "too large")
        }
        assertErrorEnvelope(volumeTooLarge, McpErrorCode.VOLUME_TOO_LARGE, "too large")

        val timedOut = McpToolExecutor.execute {
            withTimeout(1) {
                kotlinx.coroutines.delay(500)
                "unreachable"
            }
        }
        assertErrorEnvelope(timedOut, McpErrorCode.CLIENT_TIMEOUT)

        val internal = McpToolExecutor.execute {
            error("boom")
        }
        assertErrorEnvelope(internal, McpErrorCode.INTERNAL_ERROR, "boom")
    }

    @Test
    fun `execute rethrows non-timeout CancellationException`() {
        assertFailsWith<CancellationException> {
            runBlocking {
                McpToolExecutor.execute {
                    throw CancellationException("caller cancelled")
                }
            }
        }
    }

    private fun assertErrorEnvelope(
        result: CallToolResult,
        expectedCode: McpErrorCode,
        expectedMessage: String? = null,
    ) {
        assertEquals(true, result.isError)
        assertEquals(1, result.content.size)
        val text = (result.content.single() as TextContent).text
        val parsed = json.decodeFromString<McpErrorEnvelope>(text)
        assertEquals(expectedCode.name, parsed.error.code)
        if (expectedMessage != null) {
            assertEquals(expectedMessage, parsed.error.message)
        } else {
            assertTrue(parsed.error.message.isNotBlank())
        }
    }
}
