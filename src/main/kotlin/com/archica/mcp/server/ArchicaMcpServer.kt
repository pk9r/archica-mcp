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
                putJsonObject("items") {
                    put("type", "object")
                    putJsonArray("required") { add("type") }
                    putJsonObject("properties") {
                        putJsonObject("type") {
                            put("type", "string")
                            putJsonArray("enum") {
                                listOf(
                                    "set_block", "fill_box", "hollow_box", "line",
                                    "cylinder", "dome", "gable_roof", "staircase",
                                    "replace_box", "clear_box", "repeat_pattern",
                                ).forEach { add(it) }
                            }
                        }
                    }
                    put("additionalProperties", true)
                }
            }
        },
        required = listOf("blueprint_id", "ops"),
    )

    private val blueprintUpdateOpSchema = ToolSchema(
        properties = buildJsonObject {
            putBlueprintIdProperty()
            putJsonObject("op_id") { put("type", "string") }
            putJsonObject("op") {
                put("type", "object")
                putJsonArray("required") { add("type") }
                put("additionalProperties", true)
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
