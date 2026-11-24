package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i

/** Machine-readable error codes emitted by blueprint session and rasterization operations. */
enum class BlueprintErrorCode {
    BLUEPRINT_EXISTS,
    BLUEPRINT_NOT_FOUND,
    OP_NOT_FOUND,
    INVALID_BLOCK_ID,
    VOLUME_TOO_LARGE,
    INVALID_ARGUMENTS,
}

/** Structured exception thrown by blueprint session, block validation, and rasterization operations. */
class BlueprintException(
    val code: BlueprintErrorCode,
    override val message: String,
) : RuntimeException(message)

/** Functional classification used for blockstate auto-orientation and survival counting. */
enum class BlockKind {
    AIR,
    SOLID,
    STAIRS,
    LOG_OR_PILLAR,
    DOOR,
    CONNECTABLE,
}

/** Parsed Minecraft block identifier with optional normalized blockstate properties. */
data class BlockSpec(
    val blockId: String,
    val properties: Map<String, String> = emptyMap(),
) {
    val isAir: Boolean
        get() = blockId in AIR_IDS

    /** Formats as `id` or `id[k1=v1,k2=v2]` with sorted property keys. */
    fun format(): String {
        if (properties.isEmpty()) return blockId
        val encodedProps = properties.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        return "$blockId[$encodedProps]"
    }

    /** Returns a copy with [defaults] applied only for keys absent from [properties]. */
    fun withDefaultProperties(defaults: Map<String, String>): BlockSpec {
        if (defaults.isEmpty()) return this
        val merged = LinkedHashMap<String, String>(properties.size + defaults.size)
        merged.putAll(properties)
        for ((key, value) in defaults) {
            merged.putIfAbsent(key, value)
        }
        return copy(properties = merged)
    }

    /** Returns true when [voxel] satisfies this target matcher (bare ID matches any properties). */
    fun matches(voxel: BlockSpec): Boolean {
        if (blockId != voxel.blockId) return false
        if (properties.isEmpty()) return true
        return properties.all { (k, v) -> voxel.properties[k] == v }
    }

    companion object {
        const val AIR_ID = "minecraft:air"
        private val AIR_IDS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
        private val BLOCK_ID_REGEX = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
        private val PROP_KEY_REGEX = Regex("^[a-z0-9_]+$")
        private val PROP_VALUE_REGEX = Regex("^[a-z0-9_]+$")

        val AIR = BlockSpec(AIR_ID)

        /** Parses `namespace:id` or `namespace:id[k=v,...]`, throwing [BlueprintException] on malformed syntax. */
        fun parse(raw: String): BlockSpec {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw BlueprintException(BlueprintErrorCode.INVALID_BLOCK_ID, "Block identifier cannot be blank.")
            }

            val bracketStart = trimmed.indexOf('[')
            if (bracketStart < 0) {
                if (trimmed.contains(']')) {
                    throw BlueprintException(
                        BlueprintErrorCode.INVALID_BLOCK_ID,
                        "Malformed blockstate syntax in '$raw': unexpected ']'.",
                    )
                }
                if (!BLOCK_ID_REGEX.matches(trimmed)) {
                    throw BlueprintException(
                        BlueprintErrorCode.INVALID_BLOCK_ID,
                        "Invalid block identifier '$raw': expected '<namespace>:<id>'.",
                    )
                }
                return BlockSpec(trimmed)
            }

            if (!trimmed.endsWith(']') || trimmed.indexOf('[', bracketStart + 1) >= 0) {
                throw BlueprintException(
                    BlueprintErrorCode.INVALID_BLOCK_ID,
                    "Malformed blockstate properties in '$raw'.",
                )
            }

            val blockId = trimmed.substring(0, bracketStart)
            if (!BLOCK_ID_REGEX.matches(blockId)) {
                throw BlueprintException(
                    BlueprintErrorCode.INVALID_BLOCK_ID,
                    "Invalid block identifier '$blockId' in '$raw'.",
                )
            }

            val propsRaw = trimmed.substring(bracketStart + 1, trimmed.length - 1).trim()
            if (propsRaw.isEmpty()) {
                return BlockSpec(blockId)
            }

            val parsedProps = LinkedHashMap<String, String>()
            for (entry in propsRaw.split(',')) {
                val pair = entry.trim().split('=')
                if (pair.size != 2) {
                    throw BlueprintException(
                        BlueprintErrorCode.INVALID_BLOCK_ID,
                        "Invalid blockstate entry '$entry' in '$raw': expected 'key=value'.",
                    )
                }
                val key = pair[0].trim()
                val value = pair[1].trim()
                if (!PROP_KEY_REGEX.matches(key) || !PROP_VALUE_REGEX.matches(value)) {
                    throw BlueprintException(
                        BlueprintErrorCode.INVALID_BLOCK_ID,
                        "Invalid blockstate property '$key=$value' in '$raw'.",
                    )
                }
                parsedProps[key] = value
            }
            return BlockSpec(blockId = blockId, properties = parsedProps)
        }
    }
}

/** Validated [BlockSpec] paired with its [BlockKind] classification. */
data class ResolvedBlockSpec(
    val spec: BlockSpec,
    val kind: BlockKind,
)

/** Abstraction over Minecraft block registries so blueprint validation remains pure Kotlin and testable. */
interface BlockCatalog {
    fun resolve(spec: BlockSpec): ResolvedBlockSpec

    fun parseAndResolve(raw: String): ResolvedBlockSpec = resolve(BlockSpec.parse(raw))
}

/** Ridge orientation axis for [PrimitiveOp.GableRoof]. */
enum class RidgeAxis {
    X,
    Z,
}

/** Inclusive 3D integer bounding box. */
data class BlueprintBounds(
    val min: BlockPos3i,
    val max: BlockPos3i,
) {
    val sizeX: Int get() = max.x - min.x + 1
    val sizeY: Int get() = max.y - min.y + 1
    val sizeZ: Int get() = max.z - min.z + 1

    fun volumeLong(): Long = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()

    companion object {
        fun normalized(a: BlockPos3i, b: BlockPos3i): BlueprintBounds = BlueprintBounds(
            min = BlockPos3i(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
            max = BlockPos3i(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z)),
        )
    }
}

/** Composable v1 building primitive operations evaluated in anchor-relative local space. */
sealed interface PrimitiveOp {
    val typeName: String

    fun offset(delta: BlockPos3i): PrimitiveOp

    data class SetBlock(
        val pos: BlockPos3i,
        val block: String,
    ) : PrimitiveOp {
        override val typeName: String get() = "set_block"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(pos = pos.plus(delta))
    }

    data class FillBox(
        val from: BlockPos3i,
        val to: BlockPos3i,
        val block: String,
    ) : PrimitiveOp {
        override val typeName: String get() = "fill_box"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(from = from.plus(delta), to = to.plus(delta))
    }

    data class HollowBox(
        val from: BlockPos3i,
        val to: BlockPos3i,
        val block: String,
        val wallsOnly: Boolean = false,
    ) : PrimitiveOp {
        override val typeName: String get() = "hollow_box"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(from = from.plus(delta), to = to.plus(delta))
    }

    data class Line(
        val from: BlockPos3i,
        val to: BlockPos3i,
        val block: String,
    ) : PrimitiveOp {
        override val typeName: String get() = "line"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(from = from.plus(delta), to = to.plus(delta))
    }

    data class Cylinder(
        val center: BlockPos3i,
        val radius: Int,
        val height: Int,
        val block: String,
        val hollow: Boolean = false,
    ) : PrimitiveOp {
        override val typeName: String get() = "cylinder"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(center = center.plus(delta))
    }

    data class Dome(
        val center: BlockPos3i,
        val radius: Int,
        val block: String,
        val hollow: Boolean = true,
    ) : PrimitiveOp {
        override val typeName: String get() = "dome"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(center = center.plus(delta))
    }

    data class GableRoof(
        val from: BlockPos3i,
        val to: BlockPos3i,
        val block: String,
        val ridgeBlock: String? = null,
        val ridgeAxis: RidgeAxis,
        val overhang: Int = 1,
    ) : PrimitiveOp {
        override val typeName: String get() = "gable_roof"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(from = from.plus(delta), to = to.plus(delta))
    }

    data class Staircase(
        val from: BlockPos3i,
        val to: BlockPos3i,
        val block: String,
        val width: Int = 1,
        val fillUnder: Boolean = false,
    ) : PrimitiveOp {
        override val typeName: String get() = "staircase"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(from = from.plus(delta), to = to.plus(delta))
    }

    data class ReplaceBox(
        val from: BlockPos3i,
        val to: BlockPos3i,
        val target: String,
        val replacement: String,
    ) : PrimitiveOp {
        override val typeName: String get() = "replace_box"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(from = from.plus(delta), to = to.plus(delta))
    }

    data class ClearBox(
        val from: BlockPos3i,
        val to: BlockPos3i,
    ) : PrimitiveOp {
        override val typeName: String get() = "clear_box"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(from = from.plus(delta), to = to.plus(delta))
    }

    data class RepeatPattern(
        val op: PrimitiveOp,
        val count: Int,
        val step: BlockPos3i,
    ) : PrimitiveOp {
        override val typeName: String get() = "repeat_pattern"
        override fun offset(delta: BlockPos3i): PrimitiveOp = copy(op = op.offset(delta))
    }
}

/** Operation stored inside a blueprint session with its stable monotonic [opId]. */
data class StoredBlueprintOp(
    val opId: String,
    val op: PrimitiveOp,
)

internal fun BlockPos3i.plus(delta: BlockPos3i): BlockPos3i =
    BlockPos3i(x + delta.x, y + delta.y, z + delta.z)

internal fun BlockPos3i.times(factor: Int): BlockPos3i =
    BlockPos3i(x * factor, y * factor, z * factor)
