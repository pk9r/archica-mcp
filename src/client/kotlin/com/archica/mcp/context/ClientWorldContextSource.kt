package com.archica.mcp.context

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/**
 * Minecraft `26.3` client-thread [WorldContextSource] implementation backed by [ClientLevel] and [LocalPlayer].
 *
 * All reads must occur on the Minecraft client render/main thread.
 */
class ClientWorldContextSource(
    private val client: Minecraft = Minecraft.getInstance(),
) : WorldContextSource {
    private val mutablePos = BlockPos.MutableBlockPos()

    override fun isWorldLoaded(): Boolean = client.level != null && client.player != null

    override fun worldMinY(): Int = requireLevel().minY

    override fun worldMaxY(): Int = requireLevel().maxY

    override fun getPlayerSnapshot(): PlayerSnapshot {
        val level = requireLevel()
        val player = requirePlayer()
        val pos = player.blockPosition()
        val biomeId = level.getBiome(pos)
            .unwrapKey()
            .map { it.identifier().toString() }
            .orElse("minecraft:unknown")
        val dimensionId = level.dimension().identifier().toString()
        val gameMode = client.gameMode?.playerMode?.serializedName ?: "unknown"

        return PlayerSnapshot(
            pos = BlockPos3i(pos.x, pos.y, pos.z),
            yaw = player.yRot,
            pitch = player.xRot,
            biome = biomeId,
            dimension = dimensionId,
            gameMode = gameMode,
        )
    }

    override fun getInventoryMaterials(): List<InventoryEntry> {
        val player = requirePlayer()
        val items = player.inventory.nonEquipmentItems
        val entries = ArrayList<InventoryEntry>()

        for (stack in items) {
            if (stack.isEmpty) continue
            val blockItem = stack.item as? BlockItem ?: continue
            val blockId = BuiltInRegistries.BLOCK.getKey(blockItem.block).toString()
            entries.add(InventoryEntry(itemId = blockId, count = stack.count))
        }
        return entries
    }

    override fun getBlock(x: Int, y: Int, z: Int): BlockSample {
        val level = requireLevel()
        mutablePos.set(x, y, z)
        val state = level.getBlockState(mutablePos)
        if (state.isAir) return BlockSample.AIR

        val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        val props = extractProperties(state)

        val isPassThrough = when {
            !state.fluidState.isEmpty -> false
            blockId.endsWith("_leaves") -> false
            PassThroughBlocks.isClutter(BlockSample(blockId, props)) -> true
            else -> state.canBeReplaced() && state.getCollisionShape(level, mutablePos).isEmpty
        }

        return BlockSample(
            blockId = blockId,
            properties = props,
            isPassThroughClutter = isPassThrough,
        )
    }

    private fun extractProperties(state: BlockState): Map<String, String> {
        val props = state.properties
        if (props.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>(props.size)
        for (prop in props) {
            result[prop.name] = propertyValueName(state, prop)
        }
        return result
    }

    private fun <T : Comparable<T>> propertyValueName(state: BlockState, property: Property<T>): String =
        property.getName(state.getValue(property))

    private fun requireLevel(): ClientLevel =
        client.level ?: throw ContextCompressionException(
            ContextErrorCode.NO_WORLD_LOADED,
            "Minecraft client is not currently in a world (title screen or disconnecting).",
        )

    private fun requirePlayer(): LocalPlayer =
        client.player ?: throw ContextCompressionException(
            ContextErrorCode.NO_WORLD_LOADED,
            "Minecraft client has no active local player.",
        )
}
