package com.archica.mcp

/** Core mod identity and default embedded MCP endpoint constants. */
object ArchicaModInfo {
    const val MOD_ID = "archica-mcp"
    const val MOD_NAME = "Archica MCP"
    const val DEFAULT_MCP_HOST = "127.0.0.1"
    const val DEFAULT_MCP_PORT = 25580
    const val DEFAULT_MCP_PATH = "/mcp"

    fun defaultEndpointUrl(): String = "http://$DEFAULT_MCP_HOST:$DEFAULT_MCP_PORT$DEFAULT_MCP_PATH"
}
