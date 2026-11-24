package com.archica.mcp

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

/** Fabric client entrypoint for Archica MCP. */
object ArchicaMcpClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger(ArchicaModInfo.MOD_ID)

    override fun onInitializeClient() {
        logger.info(
            "{} client initialized (planned MCP endpoint: {})",
            ArchicaModInfo.MOD_NAME,
            ArchicaModInfo.defaultEndpointUrl(),
        )
    }
}
