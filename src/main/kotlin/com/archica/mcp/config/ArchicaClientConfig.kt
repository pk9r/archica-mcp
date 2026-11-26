package com.archica.mcp.config

import com.archica.mcp.ArchicaModInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persistent non-privacy client preferences stored at `config/archica-mcp.json`.
 * Session-only player inventory opt-in is intentionally excluded so it always resets to `off` on restart.
 */
@Serializable
data class ArchicaClientConfig(
    val mcpPort: Int = ArchicaModInfo.DEFAULT_MCP_PORT,
    val autoStartMcp: Boolean = true,
    val showPlacementFeedback: Boolean = true,
) {
    fun sanitized(onWarning: (String) -> Unit = {}): ArchicaClientConfig {
        if (mcpPort in 1..65535) return this
        onWarning(
            "Invalid configured mcpPort '$mcpPort' (expected integer 1..65535); " +
                "falling back to default port ${ArchicaModInfo.DEFAULT_MCP_PORT}.",
        )
        return copy(mcpPort = ArchicaModInfo.DEFAULT_MCP_PORT)
    }
}

/**
 * Reads and writes [ArchicaClientConfig] JSON with safe fallback on missing, malformed, or out-of-range values.
 */
class ArchicaConfigStore(
    val configPath: Path,
) {
    private val logger = LoggerFactory.getLogger(ArchicaConfigStore::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(onWarning: (String) -> Unit = { message -> logger.warn(message) }): ArchicaClientConfig {
        if (!Files.exists(configPath)) {
            return ArchicaClientConfig()
        }
        return try {
            val raw = Files.readString(configPath, StandardCharsets.UTF_8)
            json.decodeFromString<ArchicaClientConfig>(raw).sanitized(onWarning)
        } catch (t: Throwable) {
            onWarning(
                "Failed to read Archica config at '$configPath' (${t.message ?: t::class.simpleName}); " +
                    "using default configuration.",
            )
            ArchicaClientConfig()
        }
    }

    fun save(config: ArchicaClientConfig): ArchicaClientConfig {
        val sanitized = config.sanitized { message -> logger.warn(message) }
        val parent = configPath.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        val payload = json.encodeToString(sanitized) + "\n"
        val tempFile = configPath.resolveSibling("${configPath.fileName}.tmp")
        Files.writeString(tempFile, payload, StandardCharsets.UTF_8)
        try {
            Files.move(tempFile, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Throwable) {
            Files.move(tempFile, configPath, StandardCopyOption.REPLACE_EXISTING)
        }
        return sanitized
    }
}
