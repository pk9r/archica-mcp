package com.archica.mcp.server

import com.archica.mcp.context.FakeWorldContextSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchicaMcpServerIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `streamable http server serves tools list spatial calls and unified json errors`() = runBlocking {
        val world = FakeWorldContextSource()
        world.fillFloor(-16, 16, -16, 16, y = 64, blockId = "minecraft:grass_block")
        world.setBlock(1, 65, 1, "minecraft:cobblestone")
        world.setBlock(2, 65, 1, "minecraft:oak_stairs", mapOf("facing" to "east", "half" to "bottom", "waterlogged" to "false"))

        val server = ArchicaEmbeddedMcpServer(
            spatialContext = LocalSpatialContextService(world),
            port = 0,
        )
        assertTrue(server.start())
        try {
            val port = requireNotNull(server.resolvedPort())
            val endpoint = URI.create("http://127.0.0.1:$port/mcp")

            val initResponse = postMcp(
                endpoint = endpoint,
                body = """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}
                """.trimIndent(),
            )
            assertEquals(200, initResponse.statusCode())
            val sessionId = initResponse.headers().firstValue("mcp-session-id").orElse(null)
            assertNotNull(sessionId, "Expected mcp-session-id response header")

            val initializedNotice = postMcp(
                endpoint = endpoint,
                sessionId = sessionId,
                body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            )
            assertTrue(initializedNotice.statusCode() in 200..299)

            val listResponse = postMcp(
                endpoint = endpoint,
                sessionId = sessionId,
                body = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            )
            assertEquals(200, listResponse.statusCode())
            val tools = extractJsonRpcResult(listResponse.body())["tools"]!!.jsonArray
            val toolNames = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("get_build_context", "scan_volume"), toolNames)

            val buildContextResponse = postMcp(
                endpoint = endpoint,
                sessionId = sessionId,
                body = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_build_context","arguments":{"radius":4}}}""",
            )
            assertEquals(200, buildContextResponse.statusCode())
            val buildResult = extractJsonRpcResult(buildContextResponse.body())
            assertFalse(buildResult["isError"]?.jsonPrimitive?.boolean ?: false)
            val buildText = firstTextContent(buildResult)
            assertTrue(buildText.contains("[PLAYER]"))
            assertTrue(buildText.contains("[TERRAIN_GRID origin=(-4, -4) size=9x9 ref_y=65]"))

            val scanVolumeResponse = postMcp(
                endpoint = endpoint,
                sessionId = sessionId,
                body = """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"scan_volume","arguments":{"min":[1,65,1],"max":[2,65,1],"include_states":true}}}""",
            )
            assertEquals(200, scanVolumeResponse.statusCode())
            val scanResult = extractJsonRpcResult(scanVolumeResponse.body())
            assertFalse(scanResult["isError"]?.jsonPrimitive?.boolean ?: false)
            val scanText = firstTextContent(scanResult)
            assertTrue(scanText.contains("[VOLUME min=(1, 65, 1) max=(2, 65, 1) size=2x1x1]"))
            assertTrue(scanText.contains("minecraft:oak_stairs[facing=east,half=bottom]"))

            val invalidArgsResponse = postMcp(
                endpoint = endpoint,
                sessionId = sessionId,
                body = """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"scan_volume","arguments":{"min":[1,2]}}}""",
            )
            assertEquals(200, invalidArgsResponse.statusCode())
            val invalidArgsResult = extractJsonRpcResult(invalidArgsResponse.body())
            assertTrue(invalidArgsResult["isError"]!!.jsonPrimitive.boolean)
            val invalidEnvelope = json.decodeFromString<McpErrorEnvelope>(firstTextContent(invalidArgsResult))
            assertEquals(McpErrorCode.INVALID_ARGUMENTS.name, invalidEnvelope.error.code)

            world.loaded = false
            val noWorldResponse = postMcp(
                endpoint = endpoint,
                sessionId = sessionId,
                body = """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_build_context","arguments":{}}}""",
            )
            assertEquals(200, noWorldResponse.statusCode())
            val noWorldResult = extractJsonRpcResult(noWorldResponse.body())
            assertTrue(noWorldResult["isError"]!!.jsonPrimitive.boolean)
            val noWorldEnvelope = json.decodeFromString<McpErrorEnvelope>(firstTextContent(noWorldResult))
            assertEquals(McpErrorCode.NO_WORLD_LOADED.name, noWorldEnvelope.error.code)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `dns rebinding protection rejects external origin header`() = runBlocking {
        val world = FakeWorldContextSource()
        val server = ArchicaEmbeddedMcpServer(
            spatialContext = LocalSpatialContextService(world),
            port = 0,
        )
        assertTrue(server.start())
        try {
            val port = requireNotNull(server.resolvedPort())
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Origin", "https://evil.example")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""",
                    ),
                )
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(403, response.statusCode())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `startup fails silently when port is already bound`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val server = ArchicaEmbeddedMcpServer(
                spatialContext = LocalSpatialContextService(FakeWorldContextSource()),
                port = occupied.localPort,
            )
            assertFalse(server.start())
            assertFalse(server.isRunning)
            server.stop()
        }
    }

    private fun postMcp(
        endpoint: URI,
        body: String,
        sessionId: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId)
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun extractJsonRpcResult(rawBody: String): JsonObject {
        val jsonPayload = if (rawBody.trimStart().startsWith("{")) {
            rawBody
        } else {
            rawBody.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .first { it.startsWith("{") }
        }
        return json.parseToJsonElement(jsonPayload).jsonObject["result"]!!.jsonObject
    }

    private fun firstTextContent(result: JsonObject): String {
        val content: JsonArray = result["content"]!!.jsonArray
        return content.first().jsonObject["text"]!!.jsonPrimitive.content
    }
}
