package com.archica.mcp.ui

import com.archica.mcp.ArchicaMcpClient
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/**
 * Optional Mod Menu entrypoint exposing [ArchicaConfigScreen] from `Esc -> Mods -> Archica MCP -> Config`.
 */
object ArchicaModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> ArchicaMcpClient.createConfigScreen(parent) }
}
