package com.archica.mcp.context

/** Deterministic in-memory [WorldContextSource] fixture for spatial compression unit tests. */
class FakeWorldContextSource(
    var loaded: Boolean = true,
    var minY: Int = -64,
    var maxY: Int = 319,
    var player: PlayerSnapshot = PlayerSnapshot(
        pos = BlockPos3i(0, 65, 0),
        yaw = 180f, // NORTH (-Z)
        pitch = 0f,
        biome = "minecraft:plains",
        dimension = "minecraft:overworld",
        gameMode = "survival",
    ),
    var inventory: List<InventoryEntry> = emptyList(),
) : WorldContextSource {
    private val blocks = HashMap<BlockPos3i, BlockSample>()

    fun setBlock(
        x: Int,
        y: Int,
        z: Int,
        blockId: String,
        properties: Map<String, String> = emptyMap(),
        isPassThroughClutter: Boolean? = null,
    ) {
        blocks[BlockPos3i(x, y, z)] = BlockSample(blockId, properties, isPassThroughClutter)
    }

    fun fillFloor(minX: Int, maxX: Int, minZ: Int, maxZ: Int, y: Int, blockId: String = "minecraft:grass_block") {
        for (z in minZ..maxZ) {
            for (x in minX..maxX) {
                setBlock(x, y, z, blockId)
            }
        }
    }

    override fun isWorldLoaded(): Boolean = loaded

    override fun worldMinY(): Int = minY

    override fun worldMaxY(): Int = maxY

    override fun getPlayerSnapshot(): PlayerSnapshot = player

    override fun getInventoryMaterials(): List<InventoryEntry> = inventory

    override fun getBlock(x: Int, y: Int, z: Int): BlockSample =
        blocks[BlockPos3i(x, y, z)] ?: BlockSample.AIR
}
