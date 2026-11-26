package com.archica.mcp.server

import com.archica.mcp.ArchicaModInfo
import com.archica.mcp.blueprint.BlueprintErrorCode
import com.archica.mcp.blueprint.BlueprintException
import com.archica.mcp.context.BuildContextRequest
import com.archica.mcp.context.BuildContextResult
import com.archica.mcp.context.BuildContextScanner
import com.archica.mcp.context.ContextCompressionException
import com.archica.mcp.context.ContextErrorCode
import com.archica.mcp.context.ScanVolumeRequest
import com.archica.mcp.context.ScanVolumeResult
import com.archica.mcp.context.VolumeScanner
import com.archica.mcp.context.WorldContextSource
import com.archica.mcp.litematica.LitematicaUnavailableException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Suspend service boundary between the MCP transport layer and world-reading adapters.
 */
interface SpatialContextService {
    suspend fun getBuildContext(request: BuildContextRequest = BuildContextRequest()): BuildContextResult

    suspend fun scanVolume(request: ScanVolumeRequest): ScanVolumeResult
}

/**
 * Direct [SpatialContextService] backed by a pure-Kotlin [WorldContextSource] (primarily for unit tests).
 */
class LocalSpatialContextService(
    private val worldSource: WorldContextSource,
) : SpatialContextService {
    override suspend fun getBuildContext(request: BuildContextRequest): BuildContextResult =
        BuildContextScanner(worldSource).scan(request)

    override suspend fun scanVolume(request: ScanVolumeRequest): ScanVolumeResult =
        VolumeScanner(worldSource).scan(request)
}

/**
 * Resolves the embedded MCP server port from `-Darchica.mcp.port`, falling back to [fallbackPort] (default [ArchicaModInfo.DEFAULT_MCP_PORT]).
 */
object McpPortConfig {
    const val PORT_PROPERTY = "archica.mcp.port"
    private val logger = LoggerFactory.getLogger(McpPortConfig::class.java)

    fun resolvePort(
        rawProperty: String? = System.getProperty(PORT_PROPERTY),
        fallbackPort: Int = ArchicaModInfo.DEFAULT_MCP_PORT,
        onWarning: (String) -> Unit = { message -> logger.warn(message) },
    ): Int {
        val safeFallback = if (fallbackPort in 1..65535) fallbackPort else ArchicaModInfo.DEFAULT_MCP_PORT
        if (rawProperty == null) {
            return safeFallback
        }
        val parsed = rawProperty.trim().toIntOrNull()
        if (parsed == null || parsed !in 1..65535) {
            onWarning(
                "Invalid $PORT_PROPERTY value '$rawProperty' (expected integer 1..65535); " +
                    "falling back to port $safeFallback.",
            )
            return safeFallback
        }
        return parsed
    }
}

/** Unified machine-readable MCP tool error codes across all Archica tools. */
enum class McpErrorCode {
    NO_WORLD_LOADED,
    INVALID_RADIUS,
    INVALID_BOUNDS,
    VOLUME_TOO_LARGE,
    INVALID_ARGUMENTS,
    CLIENT_TIMEOUT,
    BLUEPRINT_EXISTS,
    BLUEPRINT_NOT_FOUND,
    OP_NOT_FOUND,
    INVALID_BLOCK_ID,
    LITEMATICA_UNAVAILABLE,
    INTERNAL_ERROR,
    ;

    companion object {
        fun fromContextError(code: ContextErrorCode): McpErrorCode = when (code) {
            ContextErrorCode.NO_WORLD_LOADED -> NO_WORLD_LOADED
            ContextErrorCode.INVALID_RADIUS -> INVALID_RADIUS
            ContextErrorCode.INVALID_BOUNDS -> INVALID_BOUNDS
            ContextErrorCode.VOLUME_TOO_LARGE -> VOLUME_TOO_LARGE
        }

        fun fromBlueprintError(code: BlueprintErrorCode): McpErrorCode = when (code) {
            BlueprintErrorCode.BLUEPRINT_EXISTS -> BLUEPRINT_EXISTS
            BlueprintErrorCode.BLUEPRINT_NOT_FOUND -> BLUEPRINT_NOT_FOUND
            BlueprintErrorCode.OP_NOT_FOUND -> OP_NOT_FOUND
            BlueprintErrorCode.INVALID_BLOCK_ID -> INVALID_BLOCK_ID
            BlueprintErrorCode.VOLUME_TOO_LARGE -> VOLUME_TOO_LARGE
            BlueprintErrorCode.INVALID_ARGUMENTS -> INVALID_ARGUMENTS
        }
    }
}

/** Inner payload of [McpErrorEnvelope]. */
@Serializable
data class McpErrorDetail(
    val code: String,
    val message: String,
)

/** Standard JSON error envelope returned inside `CallToolResult` when `isError = true`. */
@Serializable
data class McpErrorEnvelope(
    val error: McpErrorDetail,
)

/** Thrown when incoming MCP JSON arguments fail schema/type validation. */
class InvalidToolArgumentsException(
    override val message: String,
) : IllegalArgumentException(message)

/**
 * Executes tool invocations and maps domain, validation, timeout, and unexpected errors into [McpErrorEnvelope].
 */
object McpToolExecutor {
    private val json = Json { encodeDefaults = true }

    fun successResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = text)), isError = false)

    fun errorResult(code: McpErrorCode, message: String): CallToolResult {
        val payload = json.encodeToString(
            McpErrorEnvelope(
                McpErrorDetail(
                    code = code.name,
                    message = message.ifBlank { code.name },
                ),
            ),
        )
        return CallToolResult(content = listOf(TextContent(text = payload)), isError = true)
    }

    suspend fun execute(block: suspend () -> String): CallToolResult = try {
        successResult(block())
    } catch (e: InvalidToolArgumentsException) {
        errorResult(McpErrorCode.INVALID_ARGUMENTS, e.message)
    } catch (e: BlueprintException) {
        errorResult(McpErrorCode.fromBlueprintError(e.code), e.message)
    } catch (e: LitematicaUnavailableException) {
        errorResult(McpErrorCode.LITEMATICA_UNAVAILABLE, e.message)
    } catch (e: ContextCompressionException) {
        errorResult(McpErrorCode.fromContextError(e.code), e.message)
    } catch (e: TimeoutCancellationException) {
        errorResult(
            McpErrorCode.CLIENT_TIMEOUT,
            e.message ?: "Timed out waiting for Minecraft client main thread.",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        errorResult(
            McpErrorCode.INTERNAL_ERROR,
            t.message ?: "Unexpected error while executing MCP tool.",
        )
    }
}
