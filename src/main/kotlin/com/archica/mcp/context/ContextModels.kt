package com.archica.mcp.context

import kotlin.math.roundToInt

/** Machine-readable error codes for spatial context compression operations. */
enum class ContextErrorCode {
    NO_WORLD_LOADED,
    VOLUME_TOO_LARGE,
    INVALID_RADIUS,
    INVALID_BOUNDS,
}

/** Structured exception thrown by spatial context scanners on validation or runtime guards. */
class ContextCompressionException(
    val code: ContextErrorCode,
    override val message: String,
) : RuntimeException(message)

/** Integer 3D block coordinate. */
data class BlockPos3i(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun formatTuple(): String = "($x, $y, $z)"
}

/** Cardinal horizontal facing direction matching Minecraft yaw conventions. */
enum class CardinalDirection {
    NORTH,
    SOUTH,
    EAST,
    WEST,
    ;

    companion object {
        /** Maps Minecraft yaw degrees (0=SOUTH, 90=WEST, +/-180=NORTH, -90/270=EAST) to a cardinal direction. */
        fun fromYaw(yaw: Float): CardinalDirection {
            val normalized = ((yaw % 360f) + 360f) % 360f
            return when {
                normalized >= 315f || normalized < 45f -> SOUTH
                normalized < 135f -> WEST
                normalized < 225f -> NORTH
                else -> EAST
            }
        }
    }
}

/** Immutable sample of a single block in the world. */
data class BlockSample(
    val blockId: String,
    val properties: Map<String, String> = emptyMap(),
    val isPassThroughClutter: Boolean? = null,
) {
    val isAir: Boolean
        get() = blockId in AIR_BLOCK_IDS

    val isLiquid: Boolean
        get() = blockId == WATER_ID || blockId == LAVA_ID

    val isLeaf: Boolean
        get() = blockId.endsWith("_leaves")

    /** Returns true if this block should be skipped when searching top-down for outdoor surfaces. */
    fun shouldSkipInSurfaceScan(): Boolean {
        if (isAir) return true
        if (isLiquid || isLeaf) return false
        isPassThroughClutter?.let { return it }
        return PassThroughBlocks.isClutter(this)
    }

    /** Returns true when this block is solid, non-liquid, non-leaf ground suitable for anchoring a build origin. */
    fun isSolidGroundForOrigin(): Boolean = !isAir && !isLiquid && !isLeaf && !shouldSkipInSurfaceScan()

    companion object {
        const val AIR_ID = "minecraft:air"
        const val WATER_ID = "minecraft:water"
        const val LAVA_ID = "minecraft:lava"
        private val AIR_BLOCK_IDS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

        val AIR = BlockSample(AIR_ID)
    }
}

/** Built-in fallback classification for non-structural outdoor ground clutter. */
object PassThroughBlocks {
    private val exactClutterIds = setOf(
        "minecraft:short_grass",
        "minecraft:tall_grass",
        "minecraft:short_dry_grass",
        "minecraft:tall_dry_grass",
        "minecraft:fern",
        "minecraft:large_fern",
        "minecraft:dead_bush",
        "minecraft:bush",
        "minecraft:firefly_bush",
        "minecraft:seagrass",
        "minecraft:tall_seagrass",
        "minecraft:dandelion",
        "minecraft:poppy",
        "minecraft:blue_orchid",
        "minecraft:allium",
        "minecraft:azure_bluet",
        "minecraft:red_tulip",
        "minecraft:orange_tulip",
        "minecraft:white_tulip",
        "minecraft:pink_tulip",
        "minecraft:oxeye_daisy",
        "minecraft:cornflower",
        "minecraft:lily_of_the_valley",
        "minecraft:wither_rose",
        "minecraft:torchflower",
        "minecraft:pitcher_plant",
        "minecraft:sunflower",
        "minecraft:lilac",
        "minecraft:rose_bush",
        "minecraft:peony",
        "minecraft:pink_petals",
        "minecraft:wildflowers",
        "minecraft:leaf_litter",
        "minecraft:cactus_flower",
        "minecraft:torch",
        "minecraft:wall_torch",
        "minecraft:soul_torch",
        "minecraft:soul_wall_torch",
        "minecraft:redstone_torch",
        "minecraft:redstone_wall_torch",
        "minecraft:vine",
        "minecraft:glow_lichen",
        "minecraft:brown_mushroom",
        "minecraft:red_mushroom",
        "minecraft:sweet_berry_bush",
        "minecraft:sugar_cane",
        "minecraft:wheat",
        "minecraft:carrots",
        "minecraft:potatoes",
        "minecraft:beetroots",
        "minecraft:lever",
        "minecraft:tripwire",
        "minecraft:tripwire_hook",
        "minecraft:rail",
        "minecraft:powered_rail",
        "minecraft:detector_rail",
        "minecraft:activator_rail",
    )

    private val clutterSuffixes = listOf(
        "_sapling",
        "_carpet",
        "_sign",
        "_hanging_sign",
        "_banner",
        "_pressure_plate",
        "_button",
    )

    fun isClutter(sample: BlockSample): Boolean {
        val id = sample.blockId
        if (id == "minecraft:snow") {
            val layers = sample.properties["layers"]?.toIntOrNull() ?: 1
            return layers <= 1
        }
        if (id in exactClutterIds) return true
        return clutterSuffixes.any { id.endsWith(it) }
    }
}

/** Snapshot of player pose and world metadata needed for context headers. */
data class PlayerSnapshot(
    val pos: BlockPos3i,
    val yaw: Float,
    val pitch: Float,
    val biome: String,
    val dimension: String,
    val gameMode: String,
) {
    val facing: CardinalDirection
        get() = CardinalDirection.fromYaw(yaw)

    fun yawInt(): Int = yaw.roundToInt()

    fun pitchInt(): Int = pitch.roundToInt()
}

/** Single item stack entry extracted from player hotbar or main inventory. */
data class InventoryEntry(
    val itemId: String,
    val count: Int,
)

/** Abstraction over world and player state so compression logic stays pure and testable. */
interface WorldContextSource {
    fun isWorldLoaded(): Boolean

    fun worldMinY(): Int

    fun worldMaxY(): Int

    fun getPlayerSnapshot(): PlayerSnapshot

    fun getInventoryMaterials(): List<InventoryEntry>

    fun getBlock(x: Int, y: Int, z: Int): BlockSample
}
