package com.archica.mcp.server

import com.archica.mcp.ArchicaModInfo
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Factory for configuring the Archica MCP [Server] instance and registering v1 spatial context tools.
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
            putJsonObject("min") {
                put("type", "array")
                putJsonObject("items") { put("type", "integer") }
                put("minItems", 3)
                put("maxItems", 3)
                put("description", "Minimum world [x, y, z] corner of the bounding box.")
            }
            putJsonObject("max") {
                put("type", "array")
                putJsonObject("items") { put("type", "integer") }
                put("minItems", 3)
                put("maxItems", 3)
                put("description", "Maximum world [x, y, z] corner of the bounding box.")
            }
            putJsonObject("include_states") {
                put("type", "boolean")
                put("default", false)
                put("description", "Include normalized geometric blockstate properties in the palette.")
            }
        },
        required = listOf("min", "max"),
    )

    private val readOnlyAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false,
    )

    fun create(
        spatialContext: SpatialContextService,
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

        return server
    }
}

/**
 * Embedded Ktor CIO Streamable HTTP host for Archica MCP with loopback binding and fail-silent startup.
 */
class ArchicaEmbeddedMcpServer(
    private val spatialContext: SpatialContextService,
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
                ArchicaMcpServerFactory.create(spatialContext, serverVersion)
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
