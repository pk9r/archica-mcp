package com.archica.mcp.blueprint

/**
 * Pure-Kotlin [BlockCatalog] test double covering common Minecraft building blocks and blockstate properties.
 */
class FakeBlockCatalog(
    extraBlocks: Map<String, FakeBlockDef> = emptyMap(),
) : BlockCatalog {
    data class FakeBlockDef(
        val kind: BlockKind = BlockKind.SOLID,
        val allowedProperties: Map<String, Set<String>> = emptyMap(),
    )

    private val registry: Map<String, FakeBlockDef> = defaultBlocks() + extraBlocks

    override fun resolve(spec: BlockSpec): ResolvedBlockSpec {
        val def = registry[spec.blockId]
            ?: throw BlueprintException(
                BlueprintErrorCode.INVALID_BLOCK_ID,
                "Unknown block identifier '${spec.blockId}'.",
            )

        for ((key, value) in spec.properties) {
            val validValues = def.allowedProperties[key]
                ?: throw BlueprintException(
                    BlueprintErrorCode.INVALID_BLOCK_ID,
                    "Block '${spec.blockId}' does not support property '$key'.",
                )
            if (value !in validValues) {
                throw BlueprintException(
                    BlueprintErrorCode.INVALID_BLOCK_ID,
                    "Invalid value '$value' for property '$key' on '${spec.blockId}'.",
                )
            }
        }
        return ResolvedBlockSpec(spec = spec, kind = def.kind)
    }

    companion object {
        private val FACINGS = setOf("north", "south", "east", "west")
        private val AXES = setOf("x", "y", "z")
        private val STAIR_PROPS = mapOf(
            "facing" to FACINGS,
            "half" to setOf("top", "bottom"),
            "shape" to setOf("straight", "inner_left", "inner_right", "outer_left", "outer_right"),
            "waterlogged" to setOf("true", "false"),
        )
        private val LOG_PROPS = mapOf("axis" to AXES)
        private val DOOR_PROPS = mapOf(
            "facing" to FACINGS,
            "half" to setOf("lower", "upper"),
            "hinge" to setOf("left", "right"),
            "open" to setOf("true", "false"),
            "powered" to setOf("true", "false"),
        )
        private val SLAB_PROPS = mapOf(
            "type" to setOf("top", "bottom", "double"),
            "waterlogged" to setOf("true", "false"),
        )
        private val CONNECTABLE_PROPS = mapOf(
            "north" to setOf("true", "false"),
            "south" to setOf("true", "false"),
            "east" to setOf("true", "false"),
            "west" to setOf("true", "false"),
            "waterlogged" to setOf("true", "false"),
        )

        private fun defaultBlocks(): Map<String, FakeBlockDef> = mapOf(
            "minecraft:air" to FakeBlockDef(BlockKind.AIR),
            "minecraft:cave_air" to FakeBlockDef(BlockKind.AIR),
            "minecraft:stone" to FakeBlockDef(BlockKind.SOLID),
            "minecraft:cobblestone" to FakeBlockDef(BlockKind.SOLID),
            "minecraft:stone_bricks" to FakeBlockDef(BlockKind.SOLID),
            "minecraft:glass" to FakeBlockDef(BlockKind.SOLID),
            "minecraft:oak_planks" to FakeBlockDef(BlockKind.SOLID),
            "minecraft:spruce_planks" to FakeBlockDef(BlockKind.SOLID),
            "minecraft:oak_slab" to FakeBlockDef(BlockKind.SOLID, SLAB_PROPS),
            "minecraft:cobblestone_slab" to FakeBlockDef(BlockKind.SOLID, SLAB_PROPS),
            "minecraft:oak_stairs" to FakeBlockDef(BlockKind.STAIRS, STAIR_PROPS),
            "minecraft:cobblestone_stairs" to FakeBlockDef(BlockKind.STAIRS, STAIR_PROPS),
            "minecraft:stone_brick_stairs" to FakeBlockDef(BlockKind.STAIRS, STAIR_PROPS),
            "minecraft:oak_log" to FakeBlockDef(BlockKind.LOG_OR_PILLAR, LOG_PROPS),
            "minecraft:spruce_log" to FakeBlockDef(BlockKind.LOG_OR_PILLAR, LOG_PROPS),
            "minecraft:quartz_pillar" to FakeBlockDef(BlockKind.LOG_OR_PILLAR, LOG_PROPS),
            "minecraft:oak_door" to FakeBlockDef(BlockKind.DOOR, DOOR_PROPS),
            "minecraft:iron_door" to FakeBlockDef(BlockKind.DOOR, DOOR_PROPS),
            "minecraft:glass_pane" to FakeBlockDef(BlockKind.CONNECTABLE, CONNECTABLE_PROPS),
            "minecraft:oak_fence" to FakeBlockDef(BlockKind.CONNECTABLE, CONNECTABLE_PROPS),
            "minecraft:iron_bars" to FakeBlockDef(BlockKind.CONNECTABLE, CONNECTABLE_PROPS),
        )
    }
}
