package com.archica.mcp.config

import com.archica.mcp.ArchicaModInfo
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchicaClientConfigTest {
    @Test
    fun `missing config file returns defaults without warnings`() {
        val dir = createTempDirectory("archica-config-missing")
        val warnings = mutableListOf<String>()
        val store = ArchicaConfigStore(dir.resolve("archica-mcp.json"))

        val loaded = store.load(onWarning = warnings::add)

        assertEquals(ArchicaClientConfig(ArchicaModInfo.DEFAULT_MCP_PORT, autoStartMcp = true, showPlacementFeedback = true), loaded)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `save and load round trips preferences and excludes inventory tracking`() {
        val dir = createTempDirectory("archica-config-roundtrip")
        val path = dir.resolve("nested/archica-mcp.json")
        val store = ArchicaConfigStore(path)

        val saved = store.save(
            ArchicaClientConfig(
                mcpPort = 25590,
                autoStartMcp = false,
                showPlacementFeedback = false,
            ),
        )
        val rawJson = Files.readString(path)
        val reloaded = store.load()

        assertEquals(ArchicaClientConfig(25590, autoStartMcp = false, showPlacementFeedback = false), saved)
        assertEquals(saved, reloaded)
        assertFalse(rawJson.contains("inventory", ignoreCase = true))
    }

    @Test
    fun `corrupted json and out of range port fall back safely with warning`() {
        val dir = createTempDirectory("archica-config-invalid")
        val path = dir.resolve("archica-mcp.json")
        val store = ArchicaConfigStore(path)

        Files.writeString(path, "{ not valid json")
        val parseWarnings = mutableListOf<String>()
        assertEquals(ArchicaClientConfig(), store.load(onWarning = parseWarnings::add))
        assertEquals(1, parseWarnings.size)

        Files.writeString(path, """{"mcpPort": 70000, "autoStartMcp": false, "showPlacementFeedback": false}""")
        val rangeWarnings = mutableListOf<String>()
        val loaded = store.load(onWarning = rangeWarnings::add)
        assertEquals(
            ArchicaClientConfig(
                mcpPort = ArchicaModInfo.DEFAULT_MCP_PORT,
                autoStartMcp = false,
                showPlacementFeedback = false,
            ),
            loaded,
        )
        assertEquals(1, rangeWarnings.size)
    }
}
