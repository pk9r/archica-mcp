package com.archica.mcp.server

import com.archica.mcp.ArchicaModInfo
import com.archica.mcp.blueprint.BlueprintService
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Factory for configuring the Archica MCP [Server] instance and registering spatial context + blueprint tools.
 */
object ArchicaMcpServerFactory {
    val PRIMITIVE_TYPES = listOf(
        "set_block",
        "fill_box",
        "hollow_box",
        "line",
        "cylinder",
        "dome",
        "gable_roof",
        "staircase",
        "replace_box",
        "clear_box",
        "repeat_pattern",
    )

    val PRIMITIVE_OP_GUIDE = """
        Primitive operation object keyed by 'type' using local anchor-relative coordinates (+Y up, +Z forward, +X right; inclusive [from, to] box bounds).
        Supported variants:
        - set_block: {type, pos:[x,y,z], block}
        - fill_box: {type, from:[x,y,z], to:[x,y,z], block}
        - hollow_box: {type, from:[x,y,z], to:[x,y,z], block, walls_only?:bool}
        - line: {type, from:[x,y,z], to:[x,y,z], block}
        - cylinder: {type, center:[x,y,z], radius:int, height:int, block, hollow?:bool}
        - dome: {type, center:[x,y,z], radius:int, block, hollow?:bool}
        - gable_roof: {type, from:[x,y,z], to:[x,y,z], block, ridge_block?:string, ridge_axis:"x"|"z", overhang?:int}
        - staircase: {type, from:[x,y,z], to:[x,y,z], block, width?:int, fill_under?:bool}
        - replace_box: {type, from:[x,y,z], to:[x,y,z], target, replacement}
        - clear_box: {type, from:[x,y,z], to:[x,y,z]}
        - repeat_pattern: {type, op:PrimitiveOp, count:int(1..256), step:[dx,dy,dz]}
    """.trimIndent()

    val SERVER_INSTRUCTIONS = """
        Archica MCP builds client-side Litematica holograms in Minecraft without modifying world blocks directly.
        Recommended workflow:
        1. Call get_build_context (optional radius 4..24) to read player pose, suggested_origin, 2D surface terrain grid, and carried survival materials. Use scan_volume for bounded 3D RLE slices of existing structures.
        2. Call blueprint_create(blueprint_id, origin, facing) to start a draft session anchored at origin.
        3. Use blueprint_add_ops, blueprint_update_op, blueprint_remove_op, and blueprint_undo to refine operations in anchor-relative local coordinates (+Y up, +Z forward, +X right; inclusive box bounds).
        4. Call blueprint_compile(blueprint_id, activate=true) to write a .litematic schematic and activate/replace the in-world Litematica hologram.
    """.trimIndent()

    private val getBuildContextSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("radius") {
                put("type", "integer")
                put("minimum", 4)
                put("maximum", 24)
                put("default", 12)
                put("description", "Square XZ scan radius around player (4..24, default 12).")
            }
        },
    )

    private val scanVolumeSchema = ToolSchema(
        properties = buildJsonObject {
            putInt3Array("min", "Minimum world [x, y, z] corner of the bounding box.")
            putInt3Array("max", "Maximum world [x, y, z] corner of the bounding box.")
            putJsonObject("include_states") {
                put("type", "boolean")
                put("default", false)
                put("description", "Include normalized geometric blockstate properties in the palette.")
            }
        },
        required = listOf("min", "max"),
    )

    private val blueprintCreateSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putInt3Array("origin", "World [x, y, z] anchor corresponding to blueprint local (0, 0, 0).")
            putJsonObject("facing") {
                put("type", "string")
                putJsonArray("enum") {
                    add("NORTH")
                    add("SOUTH")
                    add("EAST")
                    add("WEST")
                }
                put("default", "NORTH")
                put("description", "Cardinal forward orientation for local +Z axis.")
            }
            putJsonObject("overwrite") {
                put("type", "boolean")
                put("default", false)
                put("description", "Replace an existing draft session with the same blueprint_id.")
            }
        },
        required = listOf("blueprint_id", "origin"),
    )

    private val blueprintAddOpsSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putJsonObject("ops") {
                put("type", "array")
                put("minItems", 1)
                put("description", "Ordered list of building primitives appended to the blueprint session.")
                putJsonObject("items") {
                    putPrimitiveOpObjectSchema("Single building primitive operation. $PRIMITIVE_OP_GUIDE")
                }
            }
        },
        required = listOf("blueprint_id", "ops"),
    )

    private val blueprintUpdateOpSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putJsonObject("op_id") {
                put("type", "string")
                put("description", "Target operation ID (for example 'op_2') to replace in place.")
            }
            putJsonObject("op") {
                putPrimitiveOpObjectSchema("Replacement building primitive operation. $PRIMITIVE_OP_GUIDE")
            }
        },
        required = listOf("blueprint_id", "op_id", "op"),
    )

    private val blueprintRemoveOpSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putJsonObject("op_id") { put("type", "string") }
        },
        required = listOf("blueprint_id", "op_id"),
    )

    private val blueprintUndoSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putJsonObject("count") {
                put("type", "integer")
                put("minimum", 1)
                put("default", 1)
            }
        },
        required = listOf("blueprint_id"),
    )

    private val blueprintInspectSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putJsonObject("include_ops") {
                put("type", "boolean")
                put("default", true)
            }
        },
        required = listOf("blueprint_id"),
    )

    private val blueprintListSchema = ToolSchema(properties = buildJsonObject {})

    private val blueprintCompileSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putJsonObject("activate") {
                put("type", "boolean")
                put("default", true)
                put("description", "Load and activate/replace the compiled schematic placement in Litematica.")
            }
            putInt3Array("origin_override", "Optional world [x, y, z] anchor overriding session origin for this compilation.")
        },
        required = listOf("blueprint_id"),
    )

    private val blueprintClearPlacementSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putJsonObject("delete_file") {
                put("type", "boolean")
                put("default", false)
                put("description", "Also delete the compiled .litematic file from disk.")
            }
        },
        required = listOf("blueprint_id"),
    )

    private val readOnlyAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false,
    )

    private val draftMutationAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = false,
        openWorldHint = false,
    )

    fun create(
        spatialContext: SpatialContextService,
        blueprintService: BlueprintService,
        serverVersion: String = "0.1.0",
    ): Server {
        val server = Server(
            serverInfo = Implementation(
                name = ArchicaModInfo.MOD_ID,
                version = serverVersion,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
            instructions = SERVER_INSTRUCTIONS,
        )

        server.addTool(
            name = "get_build_context",
            description = "Returns player pose, suggested build origin, inventory materials, surface legend, and aligned 2D terrain grid.",
            inputSchema = getBuildContextSchema,
            toolAnnotations = readOnlyAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = SpatialToolArguments.parseGetBuildContext(request.arguments)
                spatialContext.getBuildContext(parsed).formatText()
            }
        }

        server.addTool(
            name = "scan_volume",
            description = "Scans a bounded 3D world box (max 13,824 blocks) and returns RLE-compressed non-empty Y-slices.",
            inputSchema = scanVolumeSchema,
            toolAnnotations = readOnlyAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = SpatialToolArguments.parseScanVolume(request.arguments)
                spatialContext.scanVolume(parsed).formatText()
            }
        }

        server.addTool(
            name = "blueprint_create",
            description = "Creates or resets a named in-memory blueprint draft session anchored at world origin.",
            inputSchema = blueprintCreateSchema,
            toolAnnotations = draftMutationAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = BlueprintToolCodec.parseCreate(request.arguments)
                BlueprintToolCodec.formatCreate(blueprintService.create(parsed))
            }
        }

        server.addTool(
            name = "blueprint_add_ops",
            description = "Appends composable building primitives to a draft blueprint and returns op_ids, bounds, and material summary.",
            inputSchema = blueprintAddOpsSchema,
            toolAnnotations = draftMutationAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = BlueprintToolCodec.parseAddOps(request.arguments)
                BlueprintToolCodec.formatMutation(blueprintService.addOps(parsed.blueprintId, parsed.ops))
            }
        }

        server.addTool(
            name = "blueprint_update_op",
            description = "Replaces an existing primitive operation in place by op_id while preserving evaluation order.",
            inputSchema = blueprintUpdateOpSchema,
            toolAnnotations = draftMutationAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = BlueprintToolCodec.parseUpdateOp(request.arguments)
                BlueprintToolCodec.formatMutation(blueprintService.updateOp(parsed.blueprintId, parsed.opId, parsed.op))
            }
        }

        server.addTool(
            name = "blueprint_remove_op",
            description = "Removes a single primitive operation by op_id from a draft blueprint session.",
            inputSchema = blueprintRemoveOpSchema,
            toolAnnotations = draftMutationAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = BlueprintToolCodec.parseRemoveOp(request.arguments)
                BlueprintToolCodec.formatMutation(blueprintService.removeOp(parsed.blueprintId, parsed.opId))
            }
        }

        server.addTool(
            name = "blueprint_undo",
            description = "Removes the most recent count primitive operations from a draft blueprint session.",
            inputSchema = blueprintUndoSchema,
            toolAnnotations = draftMutationAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = BlueprintToolCodec.parseUndo(request.arguments)
                BlueprintToolCodec.formatMutation(blueprintService.undo(parsed.blueprintId, parsed.count))
            }
        }

        server.addTool(
            name = "blueprint_inspect",
            description = "Inspects active operations, local/world bounding boxes, material counts, and survival checks without writing to disk.",
            inputSchema = blueprintInspectSchema,
            toolAnnotations = readOnlyAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = BlueprintToolCodec.parseInspect(request.arguments)
                BlueprintToolCodec.formatInspect(blueprintService.inspect(parsed.blueprintId, parsed.includeOps))
            }
        }

        server.addTool(
            name = "blueprint_list",
            description = "Lists all active in-memory blueprint draft sessions and active placement status.",
            inputSchema = blueprintListSchema,
            toolAnnotations = readOnlyAnnotations,
        ) {
            McpToolExecutor.execute {
                BlueprintToolCodec.formatList(blueprintService.list())
            }
        }

        server.addTool(
            name = "blueprint_compile",
            description = "Rasterizes a blueprint to a .litematic schematic file and optionally activates/replaces its Litematica placement.",
            inputSchema = blueprintCompileSchema,
            toolAnnotations = draftMutationAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = BlueprintToolCodec.parseCompile(request.arguments)
                BlueprintToolCodec.formatCompile(blueprintService.compile(parsed))
            }
        }

        server.addTool(
            name = "blueprint_clear_placement",
            description = "Removes the active Litematica placement for a blueprint and optionally deletes its .litematic file.",
            inputSchema = blueprintClearPlacementSchema,
            toolAnnotations = draftMutationAnnotations,
        ) { request ->
            McpToolExecutor.execute {
                val parsed = BlueprintToolCodec.parseClearPlacement(request.arguments)
                BlueprintToolCodec.formatClearPlacement(blueprintService.clearPlacement(parsed))
            }
        }

        return server
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putPrimitiveOpObjectSchema(description: String) {
        put("type", "object")
        put("description", description)
        putJsonArray("required") { add("type") }
        putJsonObject("properties") {
            putJsonObject("type") {
                put("type", "string")
                putJsonArray("enum") {
                    PRIMITIVE_TYPES.forEach { add(it) }
                }
                put("description", "Primitive operation variant name.")
            }
            putInt3Array("pos", "Local [x, y, z] position (set_block).")
            putInt3Array("from", "Local [x, y, z] start corner (fill_box, hollow_box, line, gable_roof, staircase, replace_box, clear_box).")
            putInt3Array("to", "Local [x, y, z] end corner (fill_box, hollow_box, line, gable_roof, staircase, replace_box, clear_box).")
            putInt3Array("center", "Local [x, y, z] center coordinate (cylinder, dome).")
            putJsonObject("block") {
                put("type", "string")
                put("description", "Namespaced block ID with optional [prop=val] states (for example 'minecraft:oak_planks').")
            }
            putJsonObject("ridge_block") {
                put("type", "string")
                put("description", "Optional ridge cap block ID for gable_roof.")
            }
            putJsonObject("ridge_axis") {
                put("type", "string")
                putJsonArray("enum") { add("x"); add("z") }
                put("description", "Roof ridge axis ('x' or 'z') for gable_roof.")
            }
            putJsonObject("overhang") {
                put("type", "integer")
                put("minimum", 0)
                put("default", 1)
                put("description", "Footprint overhang in blocks for gable_roof.")
            }
            putJsonObject("radius") {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Horizontal/spherical radius in blocks (cylinder, dome).")
            }
            putJsonObject("height") {
                put("type", "integer")
                put("minimum", 1)
                put("description", "Vertical extrusion height in blocks (cylinder).")
            }
            putJsonObject("hollow") {
                put("type", "boolean")
                put("description", "Emit perimeter/shell only (cylinder default false; dome default true).")
            }
            putJsonObject("walls_only") {
                put("type", "boolean")
                put("default", false)
                put("description", "Omit floor and ceiling faces in hollow_box.")
            }
            putJsonObject("width") {
                put("type", "integer")
                put("minimum", 1)
                put("default", 1)
                put("description", "Staircase width in blocks.")
            }
            putJsonObject("fill_under") {
                put("type", "boolean")
                put("default", false)
                put("description", "Fill solid support blocks beneath staircase steps.")
            }
            putJsonObject("target") {
                put("type", "string")
                put("description", "Target block ID or state filter for replace_box.")
            }
            putJsonObject("replacement") {
                put("type", "string")
                put("description", "Replacement block ID/state for replace_box.")
            }
            putJsonObject("op") {
                put("type", "object")
                put("description", "Nested primitive operation repeated by repeat_pattern.")
            }
            putJsonObject("count") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 256)
                put("description", "Repetition count (1..256) for repeat_pattern.")
            }
            putInt3Array("step", "Local [dx, dy, dz] offset applied per iteration in repeat_pattern.")
        }
        put("additionalProperties", true)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putBlueprintIdProperty() {
        putJsonObject("blueprint_id") {
            put("type", "string")
            put("pattern", "^[a-z0-9_-]{1,48}$")
            put("description", "Blueprint session slug.")
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putInt3Array(name: String, description: String) {
        putJsonObject(name) {
            put("type", "array")
            putJsonObject("items") { put("type", "integer") }
            put("minItems", 3)
            put("maxItems", 3)
            put("description", description)
        }
    }
}

/**
 * Embedded Ktor CIO Streamable HTTP host for Archica MCP with loopback binding and fail-silent startup.
 */
class ArchicaEmbeddedMcpServer(
    private val spatialContext: SpatialContextService,
    private val blueprintService: BlueprintService,
    private val host: String = ArchicaModInfo.DEFAULT_MCP_HOST,
    private val port: Int = McpPortConfig.resolvePort(),
    private val path: String = ArchicaModInfo.DEFAULT_MCP_PATH,
    private val serverVersion: String = "0.1.0",
) {
    private val logger = LoggerFactory.getLogger(ArchicaEmbeddedMcpServer::class.java)

    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val endpointUrl: String
        get() = "http://$host:$port$path"

    val isRunning: Boolean
        get() = engine != null

    /** Starts Ktor CIO without blocking; returns true on bind success, or logs a warning and returns false. */
    fun start(): Boolean {
        if (engine != null) return true
        val created = embeddedServer(CIO, host = host, port = port) {
            mcpStreamableHttp(
                path = path,
                enableDnsRebindingProtection = true,
            ) {
                ArchicaMcpServerFactory.create(spatialContext, blueprintService, serverVersion)
            }
        }
        return try {
            created.start(wait = false)
            engine = created
            logger.info("{} MCP server listening at {}", ArchicaModInfo.MOD_NAME, endpointUrl)
            true
        } catch (t: Throwable) {
            runCatching { created.stop(100, 500) }
            engine = null
            logger.warn(
                "{} failed to bind MCP endpoint {} ({}); continuing without MCP server. " +
                    "Override port via -D{}=<port>.",
                ArchicaModInfo.MOD_NAME,
                endpointUrl,
                t.message ?: t::class.simpleName,
                McpPortConfig.PORT_PROPERTY,
                t,
            )
            false
        }
    }

    suspend fun resolvedPort(): Int? =
        engine?.engine?.resolvedConnectors()?.firstOrNull()?.port

    fun stop(gracePeriodMillis: Long = 500L, timeoutMillis: Long = 2_000L) {
        val current = engine ?: return
        engine = null
        runCatching {
            current.stop(gracePeriodMillis, timeoutMillis)
            logger.info("{} MCP server stopped.", ArchicaModInfo.MOD_NAME)
        }.onFailure { t ->
            logger.warn("{} error while stopping MCP server: {}", ArchicaModInfo.MOD_NAME, t.message, t)
        }
    }
}

/**
 * Thread-safe runtime controller for starting, stopping, and rebinding [ArchicaEmbeddedMcpServer] without restarting the client.
 */
class ArchicaMcpServerController(
    private val spatialContext: SpatialContextService,
    private val blueprintService: BlueprintService,
    private val host: String = ArchicaModInfo.DEFAULT_MCP_HOST,
    initialPort: Int = McpPortConfig.resolvePort(),
    private val path: String = ArchicaModInfo.DEFAULT_MCP_PATH,
    private val serverVersion: String = "0.1.0",
) {
    data class ServerStatus(
        val running: Boolean,
        val host: String,
        val port: Int,
        val endpointUrl: String,
    )

    sealed interface StartOutcome {
        val status: ServerStatus

        data class Started(override val status: ServerStatus, val restarted: Boolean) : StartOutcome
        data class AlreadyRunning(override val status: ServerStatus) : StartOutcome
        data class InvalidPort(val requestedPort: Int, override val status: ServerStatus) : StartOutcome
        data class BindFailed(override val status: ServerStatus) : StartOutcome
    }

    private val lock = Any()
    private var configuredPort: Int = initialPort
    private var activeServer: ArchicaEmbeddedMcpServer? = null

    fun status(): ServerStatus = synchronized(lock) {
        currentStatusLocked()
    }

    fun start(portOverride: Int? = null): StartOutcome = synchronized(lock) {
        if (portOverride != null && portOverride !in 1..65535) {
            return StartOutcome.InvalidPort(portOverride, currentStatusLocked())
        }
        val targetPort = portOverride ?: configuredPort
        val current = activeServer
        if (current != null && current.isRunning) {
            if (portOverride == null || portOverride == configuredPort) {
                return StartOutcome.AlreadyRunning(currentStatusLocked())
            }
            current.stop()
            activeServer = null
        }

        val restarted = current != null
        val candidate = ArchicaEmbeddedMcpServer(
            spatialContext = spatialContext,
            blueprintService = blueprintService,
            host = host,
            port = targetPort,
            path = path,
            serverVersion = serverVersion,
        )
        return if (candidate.start()) {
            activeServer = candidate
            configuredPort = targetPort
            StartOutcome.Started(currentStatusLocked(), restarted = restarted)
        } else {
            activeServer = null
            StartOutcome.BindFailed(
                ServerStatus(
                    running = false,
                    host = host,
                    port = targetPort,
                    endpointUrl = "http://$host:$targetPort$path",
                ),
            )
        }
    }

    fun stop(): Boolean {
        val toStop = synchronized(lock) {
            val current = activeServer
            activeServer = null
            current
        } ?: return false
        toStop.stop()
        return true
    }

    suspend fun resolvedPort(): Int? = synchronized(lock) { activeServer }?.resolvedPort()

    private fun currentStatusLocked(): ServerStatus {
        val running = activeServer?.isRunning == true
        return ServerStatus(
            running = running,
            host = host,
            port = configuredPort,
            endpointUrl = "http://$host:$configuredPort$path",
        )
    }
}
