package com.archica.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

class ArchicaModInfoTest {
    @Test
    fun `default endpoint targets loopback streamable http path`() {
        assertEquals("archica-mcp", ArchicaModInfo.MOD_ID)
        assertEquals("http://127.0.0.1:25580/mcp", ArchicaModInfo.defaultEndpointUrl())
    }
}
