package com.archica.mcp.context

/**
 * Deterministic 1-to-1 single-character symbol allocator for `[SURFACE_LEGEND]`.
 *
 * Excludes digits (`0-9`), sign characters (`+`, `-`), `?` (out-of-window marker), and whitespace
 * so every `<symbol><deltaY>` token in `[TERRAIN_GRID]` parses unambiguously.
 */
class SurfaceLegend private constructor(
    val blockToSymbol: Map<String, Char>,
) {
    fun symbolFor(blockId: String): Char =
        blockToSymbol[blockId]
            ?: error("Block ID '$blockId' was not registered in SurfaceLegend")

    fun formatLine(): String {
        if (blockToSymbol.isEmpty()) return "(none)"
        return blockToSymbol.entries.joinToString("  ") { (blockId, symbol) -> "$symbol=$blockId" }
    }

    companion object {
        const val OUT_OF_WINDOW_SYMBOL: Char = '?'

        private val preferredSymbols: Map<String, Char> = linkedMapOf(
            "minecraft:grass_block" to '.',
            "minecraft:water" to '~',
            "minecraft:dirt" to ',',
            "minecraft:sand" to ':',
            "minecraft:cobblestone" to '#',
            "minecraft:stone" to 'S',
            "minecraft:oak_log" to 'T',
            "minecraft:oak_leaves" to 'L',
        )

        private val fallbackPool: List<Char> = buildList {
            addAll('a'..'z')
            addAll('A'..'Z')
            addAll(listOf('~', '.', ',', ':', ';', '#', '@', '%', '&', '*', '!', '=', '/', '^', '_', '|'))
        }

        /** Assigns one distinct symbol per distinct block ID in deterministic [orderedBlockIds] order. */
        fun assign(orderedBlockIds: Iterable<String>): SurfaceLegend {
            val uniqueIds = orderedBlockIds.distinct()
            val assigned = LinkedHashMap<String, Char>(uniqueIds.size)
            val usedSymbols = HashSet<Char>(uniqueIds.size)

            // Reserve intuitive defaults first for blocks that actually appear on the grid.
            for (blockId in uniqueIds) {
                val preferred = preferredSymbols[blockId] ?: continue
                if (usedSymbols.add(preferred)) {
                    assigned[blockId] = preferred
                }
            }

            // Assign remaining distinct blocks strictly 1-to-1 from the unused pool.
            val finalMap = LinkedHashMap<String, Char>(uniqueIds.size)
            for (blockId in uniqueIds) {
                val symbol = assigned[blockId] ?: nextAvailableSymbol(usedSymbols)
                usedSymbols.add(symbol)
                finalMap[blockId] = symbol
            }

            return SurfaceLegend(finalMap)
        }

        private fun nextAvailableSymbol(usedSymbols: Set<Char>): Char =
            fallbackPool.firstOrNull { it !in usedSymbols }
                ?: throw IllegalStateException("Surface legend exceeded ${fallbackPool.size} unique symbols")
    }
}
