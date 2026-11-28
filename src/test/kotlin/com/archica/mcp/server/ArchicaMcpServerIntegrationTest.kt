package com.archica.mcp.server

import com.archica.mcp.blueprint.BlueprintDraftStore
import com.archica.mcp.blueprint.BlueprintService
import com.archica.mcp.blueprint.FakeBlockCatalog
import com.archica.mcp.blueprint.SurvivalSnapshotProvider
import com.archica.mcp.context.BuildContextRequest
import com.archica.mcp.context.BuildContextResult
import com.archica.mcp.context.BuildContextScanner
import com.archica.mcp.context.FakeWorldContextSource
import com.archica.mcp.context.InventoryEntry
import com.archica.mcp.context.ScanVolumeRequest
import com.archica.mcp.context.ScanVolumeResult
import com.archica.mcp.context.VolumeScanner
import com.archica.mcp.litematica.FakeLitematicaBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `streamable http server serves tools list spatial calls and unified json errors`() = runBlocking {
        val world = FakeWorldContextSource(
            inventory = listOf(InventoryEntry("minecraft:oak_planks", 4)),
        )
        world.fillFloor(-16, 16, -16, 16, y = 64, blockId = "minecraft:grass_block")
        world.setBlock(1, 65, 1, "minecraft:cobblestone")
        world.setBlock(2, 65, 1, "minecraft:oak_stairs", mapOf("facing" to "east", "half" to "bottom", "waterlogged" to "false"))

        val inventoryEnabled = AtomicBoolean(false)
        val spatialService = object : SpatialContextService {
            override suspend fun getBuildContext(request: BuildContextRequest): BuildContextResult =
                BuildContextScanner(world).scan(request.copy(includeInventory = inventoryEnabled.get()))

            override suspend fun scanVolume(request: ScanVolumeRequest): ScanVolumeResult =
                VolumeScanner(world).scan(request)
        }
        val blueprintService = BlueprintService(
            catalog = FakeBlockCatalog(),
            survivalProvider = SurvivalSnapshotProvider {
                if (!inventoryEnabled.get()) null else SurvivalSnapshotProvider.fromWorldSource(world).getSnapshotOrNull()
            },
        )
        val server = ArchicaEmbeddedMcpServer(
            spatialContext = spatialService,
            blueprintService = blueprintService,
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
            val initResult = extractJsonRpcResult(initResponse.body())
            val instructions = initResult["instructions"]?.jsonPrimitive?.content
            assertNotNull(instructions, "Expected server instructions in initialize result")
            assertTrue(instructions.contains("get_build_context"))
            assertTrue(instructions.contains("+Z forward"))
            assertTrue(instructions.contains("omitted by default"))
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
            assertEquals(
                setOf(
                    "get_build_context",
                    "scan_volume",
                    "blueprint_create",
                    "blueprint_add_ops",
                    "blueprint_update_op",
                    "blueprint_remove_op",
                    "blueprint_undo",
                    "blueprint_inspect",
                    "blueprint_list",
                    "blueprint_compile",
                    "blueprint_clear_placement",
                ),
                toolNames,
            )

            val addOpsTool = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "blueprint_add_ops" }.jsonObject
            val addOpsProps = addOpsTool["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
            assertTrue(addOpsProps.containsKey("phase"))
            val addOpsItemProps = addOpsProps["ops"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
            assertTrue(addOpsItemProps.containsKey("ridge_axis"))
            assertTrue(addOpsItemProps.containsKey("step"))
            assertTrue(addOpsItemProps.containsKey("phase"))

            val updateOpTool = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "blueprint_update_op" }.jsonObject
            val updateOpTopProps = updateOpTool["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
            assertTrue(updateOpTopProps.containsKey("phase"))
            val updateOpProps = updateOpTopProps["op"]!!.jsonObject["properties"]!!.jsonObject
            assertTrue(updateOpProps.containsKey("center"))
            assertTrue(updateOpProps.containsKey("fill_under"))

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
            assertFalse(buildText.contains("[INVENTORY_MATERIALS]"))
            assertTrue(buildText.contains("[TERRAIN_GRID origin=(-4, -4) size=9x9 ref_y=65]"))

            // When player enables inventory tracking, get_build_context includes [INVENTORY_MATERIALS]
            inventoryEnabled.set(true)
            val enabledBuildContextResponse = postMcp(
                endpoint = endpoint,
                sessionId = sessionId,
                body = """{"jsonrpc":"2.0","id":301,"method":"tools/call","params":{"name":"get_build_context","arguments":{"radius":4}}}""",
            )
            assertEquals(200, enabledBuildContextResponse.statusCode())
            val enabledBuildText = firstTextContent(extractJsonRpcResult(enabledBuildContextResponse.body()))
            assertTrue(enabledBuildText.contains("[INVENTORY_MATERIALS]"))
            assertTrue(enabledBuildText.contains("oak_planks:4"))
            inventoryEnabled.set(false)

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
    fun `streamable http server executes full blueprint draft lifecycle and error envelopes`() = runBlocking {
        val world = FakeWorldContextSource(
            inventory = listOf(InventoryEntry("minecraft:oak_planks", 4)),
        )
        val server = ArchicaEmbeddedMcpServer(
            spatialContext = LocalSpatialContextService(world),
            blueprintService = BlueprintService(
                catalog = FakeBlockCatalog(),
                survivalProvider = SurvivalSnapshotProvider.fromWorldSource(world),
            ),
            port = 0,
        )
        assertTrue(server.start())
        try {
            val port = requireNotNull(server.resolvedPort())
            val endpoint = URI.create("http://127.0.0.1:$port/mcp")
            val sessionId = initializeMcpSession(endpoint)

            val createRes = callTool(
                endpoint,
                sessionId,
                id = 10,
                name = "blueprint_create",
                argsJson = """{"blueprint_id":"oak_cottage","origin":[100,64,100],"facing":"EAST"}""",
            )
            assertFalse(createRes["isError"]?.jsonPrimitive?.boolean ?: false)
            assertEquals(0, json.parseToJsonElement(firstTextContent(createRes)).jsonObject["op_count"]!!.jsonPrimitive.int)

            val dupRes = callTool(
                endpoint,
                sessionId,
                id = 11,
                name = "blueprint_create",
                argsJson = """{"blueprint_id":"oak_cottage","origin":[0,64,0]}""",
            )
            assertTrue(dupRes["isError"]!!.jsonPrimitive.boolean)
            assertEquals(
                McpErrorCode.BLUEPRINT_EXISTS.name,
                json.decodeFromString<McpErrorEnvelope>(firstTextContent(dupRes)).error.code,
            )

            val addRes = callTool(
                endpoint,
                sessionId,
                id = 12,
                name = "blueprint_add_ops",
                argsJson = """{"blueprint_id":"oak_cottage","ops":[{"type":"fill_box","from":[0,0,0],"to":[2,0,2],"block":"minecraft:oak_planks"},{"type":"set_block","pos":[1,1,0],"block":"minecraft:oak_door"}]}""",
            )
            assertFalse(addRes["isError"]?.jsonPrimitive?.boolean ?: false)
            val addJson = json.parseToJsonElement(firstTextContent(addRes)).jsonObject
            assertEquals(listOf("op_1", "op_2"), addJson["added_op_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(11, addJson["total_blocks"]!!.jsonPrimitive.int)
            assertEquals(5, addJson["survival_check"]!!.jsonObject["missing_materials"]!!.jsonObject["minecraft:oak_planks"]!!.jsonPrimitive.int)

            val updateRes = callTool(
                endpoint,
                sessionId,
                id = 13,
                name = "blueprint_update_op",
                argsJson = """{"blueprint_id":"oak_cottage","op_id":"op_2","op":{"type":"set_block","pos":[1,1,0],"block":"minecraft:cobblestone"}}""",
            )
            assertFalse(updateRes["isError"]?.jsonPrimitive?.boolean ?: false)
            assertEquals(10, json.parseToJsonElement(firstTextContent(updateRes)).jsonObject["total_blocks"]!!.jsonPrimitive.int)

            val inspectRes = callTool(
                endpoint,
                sessionId,
                id = 14,
                name = "blueprint_inspect",
                argsJson = """{"blueprint_id":"oak_cottage","include_ops":false}""",
            )
            val inspectJson = json.parseToJsonElement(firstTextContent(inspectRes)).jsonObject
            assertFalse(inspectJson.containsKey("ops"))
            assertNotNull(inspectJson["world_bounding_box"])

            val removeRes = callTool(
                endpoint,
                sessionId,
                id = 15,
                name = "blueprint_remove_op",
                argsJson = """{"blueprint_id":"oak_cottage","op_id":"op_2"}""",
            )
            assertEquals(1, json.parseToJsonElement(firstTextContent(removeRes)).jsonObject["total_ops"]!!.jsonPrimitive.int)

            val undoRes = callTool(
                endpoint,
                sessionId,
                id = 16,
                name = "blueprint_undo",
                argsJson = """{"blueprint_id":"oak_cottage","count":1}""",
            )
            assertEquals(0, json.parseToJsonElement(firstTextContent(undoRes)).jsonObject["total_ops"]!!.jsonPrimitive.int)

            val listRes = callTool(endpoint, sessionId, id = 17, name = "blueprint_list", argsJson = "{}")
            val listJson = json.parseToJsonElement(firstTextContent(listRes)).jsonObject
            assertEquals(1, listJson["blueprints"]!!.jsonArray.size)

            val badBlockRes = callTool(
                endpoint,
                sessionId,
                id = 18,
                name = "blueprint_add_ops",
                argsJson = """{"blueprint_id":"oak_cottage","ops":[{"type":"set_block","pos":[0,0,0],"block":"minecraft:not_a_block"}]}""",
            )
            assertTrue(badBlockRes["isError"]!!.jsonPrimitive.boolean)
            assertEquals(
                McpErrorCode.INVALID_BLOCK_ID.name,
                json.decodeFromString<McpErrorEnvelope>(firstTextContent(badBlockRes)).error.code,
            )

            val missingOpRes = callTool(
                endpoint,
                sessionId,
                id = 19,
                name = "blueprint_remove_op",
                argsJson = """{"blueprint_id":"oak_cottage","op_id":"op_999"}""",
            )
            assertEquals(
                McpErrorCode.OP_NOT_FOUND.name,
                json.decodeFromString<McpErrorEnvelope>(firstTextContent(missingOpRes)).error.code,
            )

            val missingBpRes = callTool(
                endpoint,
                sessionId,
                id = 20,
                name = "blueprint_inspect",
                argsJson = """{"blueprint_id":"missing_bp"}""",
            )
            assertEquals(
                McpErrorCode.BLUEPRINT_NOT_FOUND.name,
                json.decodeFromString<McpErrorEnvelope>(firstTextContent(missingBpRes)).error.code,
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `streamable http server executes blueprint_compile, blueprint_clear_placement, and LITEMATICA_UNAVAILABLE`() = runBlocking {
        val world = FakeWorldContextSource(
            inventory = listOf(InventoryEntry("minecraft:oak_planks", 4)),
        )
        val schematicsDir = tempDir.resolve("schematics")
        val bridge = FakeLitematicaBridge(schematicsDir)
        val server = ArchicaEmbeddedMcpServer(
            spatialContext = LocalSpatialContextService(world),
            blueprintService = BlueprintService(
                catalog = FakeBlockCatalog(),
                survivalProvider = SurvivalSnapshotProvider.fromWorldSource(world),
                litematicaBridge = bridge,
            ),
            port = 0,
        )
        assertTrue(server.start())
        try {
            val port = requireNotNull(server.resolvedPort())
            val endpoint = URI.create("http://127.0.0.1:$port/mcp")
            val sessionId = initializeMcpSession(endpoint)

            // Create empty blueprint and verify INVALID_ARGUMENTS on compile
            callTool(
                endpoint,
                sessionId,
                id = 30,
                name = "blueprint_create",
                argsJson = """{"blueprint_id":"hut","origin":[100,64,100],"facing":"EAST"}""",
            )
            val emptyCompile = callTool(
                endpoint,
                sessionId,
                id = 31,
                name = "blueprint_compile",
                argsJson = """{"blueprint_id":"hut"}""",
            )
            assertTrue(emptyCompile["isError"]!!.jsonPrimitive.boolean)
            assertEquals(
                McpErrorCode.INVALID_ARGUMENTS.name,
                json.decodeFromString<McpErrorEnvelope>(firstTextContent(emptyCompile)).error.code,
            )

            // Add ops, compile & activate
            callTool(
                endpoint,
                sessionId,
                id = 32,
                name = "blueprint_add_ops",
                argsJson = """{"blueprint_id":"hut","ops":[{"type":"fill_box","from":[0,0,0],"to":[2,1,2],"block":"minecraft:oak_planks"}]}""",
            )
            val firstCompile = callTool(
                endpoint,
                sessionId,
                id = 33,
                name = "blueprint_compile",
                argsJson = """{"blueprint_id":"hut","activate":true}""",
            )
            assertFalse(firstCompile["isError"]?.jsonPrimitive?.boolean ?: false)
            val firstJson = json.parseToJsonElement(firstTextContent(firstCompile)).jsonObject
            assertEquals("schematics/archica/hut.litematic", firstJson["schematic_path"]!!.jsonPrimitive.content)
            assertTrue(firstJson["activated"]!!.jsonPrimitive.boolean)
            assertFalse(firstJson["replaced_existing_placement"]!!.jsonPrimitive.boolean)
            assertEquals(18, firstJson["total_blocks"]!!.jsonPrimitive.int)
            assertTrue(Files.exists(schematicsDir.resolve("archica/hut.litematic")))

            // List confirms has_active_placement = true
            val listRes = callTool(endpoint, sessionId, id = 34, name = "blueprint_list", argsJson = "{}")
            val listJson = json.parseToJsonElement(firstTextContent(listRes)).jsonObject
            assertTrue(listJson["blueprints"]!!.jsonArray.first().jsonObject["has_active_placement"]!!.jsonPrimitive.boolean)

            // Re-compile replaces prior placement
            val recompile = callTool(
                endpoint,
                sessionId,
                id = 35,
                name = "blueprint_compile",
                argsJson = """{"blueprint_id":"hut","activate":true}""",
            )
            val recompileJson = json.parseToJsonElement(firstTextContent(recompile)).jsonObject
            assertTrue(recompileJson["replaced_existing_placement"]!!.jsonPrimitive.boolean)

            // Clear keeping file
            val clearKeep = callTool(
                endpoint,
                sessionId,
                id = 36,
                name = "blueprint_clear_placement",
                argsJson = """{"blueprint_id":"hut","delete_file":false}""",
            )
            val clearKeepJson = json.parseToJsonElement(firstTextContent(clearKeep)).jsonObject
            assertTrue(clearKeepJson["cleared"]!!.jsonPrimitive.boolean)
            assertFalse(clearKeepJson["deleted_file"]!!.jsonPrimitive.boolean)
            assertTrue(Files.exists(schematicsDir.resolve("archica/hut.litematic")))

            // Clear deleting file
            val clearDelete = callTool(
                endpoint,
                sessionId,
                id = 37,
                name = "blueprint_clear_placement",
                argsJson = """{"blueprint_id":"hut","delete_file":true}""",
            )
            val clearDeleteJson = json.parseToJsonElement(firstTextContent(clearDelete)).jsonObject
            assertFalse(clearDeleteJson["cleared"]!!.jsonPrimitive.boolean)
            assertTrue(clearDeleteJson["deleted_file"]!!.jsonPrimitive.boolean)
            assertFalse(Files.exists(schematicsDir.resolve("archica/hut.litematic")))

            // Adapter failure maps to LITEMATICA_UNAVAILABLE and reports saved schematic_path
            bridge.adapterFailureMessage = "Litematica manager unavailable"
            val failedCompile = callTool(
                endpoint,
                sessionId,
                id = 38,
                name = "blueprint_compile",
                argsJson = """{"blueprint_id":"hut","activate":true}""",
            )
            assertTrue(failedCompile["isError"]!!.jsonPrimitive.boolean)
            val err = json.decodeFromString<McpErrorEnvelope>(firstTextContent(failedCompile)).error
            assertEquals(McpErrorCode.LITEMATICA_UNAVAILABLE.name, err.code)
            assertTrue(err.message.contains("schematics/archica/hut.litematic"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `dns rebinding protection rejects external origin header`() = runBlocking {
        val world = FakeWorldContextSource()
        val server = ArchicaEmbeddedMcpServer(
            spatialContext = LocalSpatialContextService(world),
            blueprintService = BlueprintService(FakeBlockCatalog()),
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
                blueprintService = BlueprintService(FakeBlockCatalog()),
                port = occupied.localPort,
            )
            assertFalse(server.start())
            assertFalse(server.isRunning)
            server.stop()
        }
    }

    @Test
    fun `runtime server controller supports stop start and port rebind while keeping loopback security`() = runBlocking {
        val world = FakeWorldContextSource()
        val controller = ArchicaMcpServerController(
            spatialContext = LocalSpatialContextService(world),
            blueprintService = BlueprintService(FakeBlockCatalog()),
            initialPort = 0,
        )
        assertFalse(controller.status().running)
        assertTrue(controller.start() is ArchicaMcpServerController.StartOutcome.Started)
        assertTrue(controller.status().running)
        assertTrue(controller.start() is ArchicaMcpServerController.StartOutcome.AlreadyRunning)

        // Invalid port leaves running server untouched
        val invalidOutcome = controller.start(70000)
        assertTrue(invalidOutcome is ArchicaMcpServerController.StartOutcome.InvalidPort)
        assertTrue(controller.status().running)

        assertTrue(controller.stop())
        assertFalse(controller.stop())
        assertFalse(controller.status().running)

        val freePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val rebound = controller.start(freePort)
        assertTrue(rebound is ArchicaMcpServerController.StartOutcome.Started)
        try {
            assertEquals("http://127.0.0.1:$freePort/mcp", rebound.status.endpointUrl)
            val endpoint = URI.create(rebound.status.endpointUrl)
            val sessionId = initializeMcpSession(endpoint)
            val listRes = postMcp(endpoint, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", sessionId)
            assertEquals(200, listRes.statusCode())

            val evilReq = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))
                .build()
            assertEquals(403, httpClient.send(evilReq, HttpResponse.BodyHandlers.ofString()).statusCode())
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `mcp blueprint drafts persist across simulated server restarts`() = runBlocking {
        val world = FakeWorldContextSource()
        val schematicsDir = tempDir.resolve("schematics")
        val draftsDir = schematicsDir.resolve("archica")
        val bridge = FakeLitematicaBridge(schematicsDir)

        val firstService = BlueprintService(
            catalog = FakeBlockCatalog(),
            litematicaBridge = bridge,
            draftStore = BlueprintDraftStore(draftsDir),
        )
        val firstServer = ArchicaEmbeddedMcpServer(
            spatialContext = LocalSpatialContextService(world),
            blueprintService = firstService,
            port = 0,
        )
        assertTrue(firstServer.start())
        try {
            val port = requireNotNull(firstServer.resolvedPort())
            val endpoint = URI.create("http://127.0.0.1:$port/mcp")
            val sessionId = initializeMcpSession(endpoint)

            callTool(
                endpoint,
                sessionId,
                id = 100,
                name = "blueprint_create",
                argsJson = """{"blueprint_id":"persisted_cottage","origin":[10,64,20],"facing":"EAST"}""",
            )
            callTool(
                endpoint,
                sessionId,
                id = 101,
                name = "blueprint_add_ops",
                argsJson = """{"blueprint_id":"persisted_cottage","ops":[{"type":"fill_box","from":[0,0,0],"to":[2,0,2],"block":"minecraft:oak_planks"},{"type":"set_block","pos":[1,1,0],"block":"minecraft:stone"}]}""",
            )
        } finally {
            firstServer.stop()
        }

        // Simulate restart with fresh BlueprintService + ArchicaEmbeddedMcpServer
        val secondService = BlueprintService(
            catalog = FakeBlockCatalog(),
            litematicaBridge = bridge,
            draftStore = BlueprintDraftStore(draftsDir),
        )
        assertEquals(1, secondService.loadPersistedDrafts())
        val secondServer = ArchicaEmbeddedMcpServer(
            spatialContext = LocalSpatialContextService(world),
            blueprintService = secondService,
            port = 0,
        )
        assertTrue(secondServer.start())
        try {
            val port = requireNotNull(secondServer.resolvedPort())
            val endpoint = URI.create("http://127.0.0.1:$port/mcp")
            val sessionId = initializeMcpSession(endpoint)

            val listRes = callTool(endpoint, sessionId, id = 200, name = "blueprint_list", argsJson = "{}")
            val listJson = json.parseToJsonElement(firstTextContent(listRes)).jsonObject
            val listed = listJson["blueprints"]!!.jsonArray.single().jsonObject
            assertEquals("persisted_cottage", listed["blueprint_id"]!!.jsonPrimitive.content)
            assertEquals(2, listed["op_count"]!!.jsonPrimitive.int)

            val inspectRes = callTool(
                endpoint,
                sessionId,
                id = 201,
                name = "blueprint_inspect",
                argsJson = """{"blueprint_id":"persisted_cottage","include_ops":true}""",
            )
            val inspectJson = json.parseToJsonElement(firstTextContent(inspectRes)).jsonObject
            assertEquals(10, inspectJson["total_blocks"]!!.jsonPrimitive.int)
            assertEquals(2, inspectJson["ops"]!!.jsonArray.size)

            val addMoreRes = callTool(
                endpoint,
                sessionId,
                id = 202,
                name = "blueprint_add_ops",
                argsJson = """{"blueprint_id":"persisted_cottage","ops":[{"type":"set_block","pos":[1,2,0],"block":"minecraft:glass"}]}""",
            )
            val addMoreJson = json.parseToJsonElement(firstTextContent(addMoreRes)).jsonObject
            assertEquals(listOf("op_3"), addMoreJson["added_op_ids"]!!.jsonArray.map { it.jsonPrimitive.content })

            val compileRes = callTool(
                endpoint,
                sessionId,
                id = 203,
                name = "blueprint_compile",
                argsJson = """{"blueprint_id":"persisted_cottage","activate":true}""",
            )
            val compileJson = json.parseToJsonElement(firstTextContent(compileRes)).jsonObject
            assertTrue(compileJson["activated"]!!.jsonPrimitive.boolean)
            assertEquals(11, compileJson["total_blocks"]!!.jsonPrimitive.int)
            assertTrue(Files.exists(schematicsDir.resolve("archica/persisted_cottage.litematic")))

            // Phased build end-to-end over MCP
            callTool(
                endpoint,
                sessionId,
                id = 204,
                name = "blueprint_create",
                argsJson = """{"blueprint_id":"phased_tower","origin":[0,64,0]}""",
            )
            callTool(
                endpoint,
                sessionId,
                id = 205,
                name = "blueprint_add_ops",
                argsJson = """{"blueprint_id":"phased_tower","phase":{"id":"base","title":"Foundation"},"ops":[{"type":"fill_box","from":[0,0,0],"to":[2,0,2],"block":"minecraft:cobblestone"}]}""",
            )
            callTool(
                endpoint,
                sessionId,
                id = 206,
                name = "blueprint_add_ops",
                argsJson = """{"blueprint_id":"phased_tower","phase":{"id":"roof","title":"Roof"},"ops":[{"type":"fill_box","from":[0,1,0],"to":[2,1,2],"block":"minecraft:oak_planks"}]}""",
            )
            val phasedInspect = callTool(
                endpoint,
                sessionId,
                id = 207,
                name = "blueprint_inspect",
                argsJson = """{"blueprint_id":"phased_tower","include_ops":false}""",
            )
            val phasedInspectJson = json.parseToJsonElement(firstTextContent(phasedInspect)).jsonObject
            assertEquals(2, phasedInspectJson["phases"]!!.jsonArray.size)
            assertEquals("base", phasedInspectJson["phase_view"]!!.jsonObject["active_phase_id"]!!.jsonPrimitive.content)

            val phasedCompile = callTool(
                endpoint,
                sessionId,
                id = 208,
                name = "blueprint_compile",
                argsJson = """{"blueprint_id":"phased_tower","activate":true}""",
            )
            val phasedCompileJson = json.parseToJsonElement(firstTextContent(phasedCompile)).jsonObject
            assertEquals(2, phasedCompileJson["phase_count"]!!.jsonPrimitive.int)
            assertEquals("base", phasedCompileJson["active_phase_id"]!!.jsonPrimitive.content)
            assertEquals("ISOLATED", phasedCompileJson["view_mode"]!!.jsonPrimitive.content)
        } finally {
            secondServer.stop()
        }
    }

    private fun initializeMcpSession(endpoint: URI): String {
        val initResponse = postMcp(
            endpoint = endpoint,
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}""",
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
        return sessionId
    }

    private fun callTool(
        endpoint: URI,
        sessionId: String,
        id: Int,
        name: String,
        argsJson: String,
    ): JsonObject {
        val response = postMcp(
            endpoint = endpoint,
            sessionId = sessionId,
            body = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$argsJson}}""",
        )
        assertEquals(200, response.statusCode())
        return extractJsonRpcResult(response.body())
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
