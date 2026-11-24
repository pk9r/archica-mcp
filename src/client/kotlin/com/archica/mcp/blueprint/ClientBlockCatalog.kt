package com.archica.mcp.blueprint

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.StairBlock

/**
 * Minecraft `26.3` [BlockCatalog] backed by [BuiltInRegistries.BLOCK] and block [StateDefinition] metadata.
 */
class ClientBlockCatalog : BlockCatalog {
    override fun resolve(spec: BlockSpec): ResolvedBlockSpec {
        val identifier = Identifier.tryParse(spec.blockId)
            ?: throw BlueprintException(
                BlueprintErrorCode.INVALID_BLOCK_ID,
                "Malformed block identifier '${spec.blockId}'.",
            )
        val block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null)
            ?: throw BlueprintException(
                BlueprintErrorCode.INVALID_BLOCK_ID,
                "Unknown block identifier '${spec.blockId}'.",
            )

        val stateDefinition = block.stateDefinition
        for ((key, value) in spec.properties) {
            val property = stateDefinition.getProperty(key)
                ?: throw BlueprintException(
                    BlueprintErrorCode.INVALID_BLOCK_ID,
                    "Block '${spec.blockId}' does not support property '$key'.",
                )
            if (property.getValue(value).isEmpty) {
                throw BlueprintException(
                    BlueprintErrorCode.INVALID_BLOCK_ID,
                    "Invalid value '$value' for property '$key' on '${spec.blockId}'.",
                )
            }
        }

        return ResolvedBlockSpec(spec = spec, kind = classify(block))
    }

    private fun classify(block: Block): BlockKind {
        val defaultState = block.defaultBlockState()
        return when {
            defaultState.isAir -> BlockKind.AIR
            block is StairBlock -> BlockKind.STAIRS
            block is RotatedPillarBlock -> BlockKind.LOG_OR_PILLAR
            block is DoorBlock -> BlockKind.DOOR
            block is CrossCollisionBlock -> BlockKind.CONNECTABLE
            else -> BlockKind.SOLID
        }
    }
}
