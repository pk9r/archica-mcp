package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import kotlin.math.abs

/**
 * Forward-aligned local-to-world coordinate and blockstate rotation helpers, plus auto-orientation rules.
 *
 * Local space convention: `+Y` up, `+Z` forward (local `north`), `+X` right (local `east`).
 */
object BlueprintTransform {
    private val CLOCKWISE_FACINGS = listOf("north", "east", "south", "west")

    fun clockwiseTurns(facing: CardinalDirection): Int = when (facing) {
        CardinalDirection.NORTH -> 0
        CardinalDirection.EAST -> 1
        CardinalDirection.SOUTH -> 2
        CardinalDirection.WEST -> 3
    }

    /** Rotates a local offset `(lx, ly, lz)` around `(0, 0, 0)` into world offset space for [facing]. */
    fun toWorldOffset(local: BlockPos3i, facing: CardinalDirection): BlockPos3i = when (facing) {
        CardinalDirection.NORTH -> BlockPos3i(local.x, local.y, -local.z)
        CardinalDirection.EAST -> BlockPos3i(local.z, local.y, local.x)
        CardinalDirection.SOUTH -> BlockPos3i(-local.x, local.y, local.z)
        CardinalDirection.WEST -> BlockPos3i(-local.z, local.y, -local.x)
    }

    /** Transforms a local coordinate into absolute world space using [origin] and [facing]. */
    fun toWorldPos(local: BlockPos3i, origin: BlockPos3i, facing: CardinalDirection): BlockPos3i =
        origin.plus(toWorldOffset(local, facing))

    /** Transforms a local [BlueprintBounds] into its enclosing world-space bounds. */
    fun toWorldBounds(localBounds: BlueprintBounds, origin: BlockPos3i, facing: CardinalDirection): BlueprintBounds {
        val c1 = toWorldPos(localBounds.min, origin, facing)
        val c2 = toWorldPos(localBounds.max, origin, facing)
        return BlueprintBounds.normalized(c1, c2)
    }

    /** Rotates directional blockstate properties (`facing`, `axis`) from local space to [blueprintFacing]. */
    fun transformBlockSpec(spec: BlockSpec, blueprintFacing: CardinalDirection): BlockSpec {
        val turns = clockwiseTurns(blueprintFacing)
        if (turns == 0 || spec.properties.isEmpty()) return spec

        val updated = LinkedHashMap<String, String>(spec.properties.size)
        for ((key, value) in spec.properties) {
            updated[key] = when (key) {
                "facing" -> rotateFacingValue(value, turns)
                "axis" -> rotateAxisValue(value, turns)
                else -> value
            }
        }
        return spec.copy(properties = updated)
    }

    private fun rotateFacingValue(value: String, turns: Int): String {
        val idx = CLOCKWISE_FACINGS.indexOf(value)
        if (idx < 0) return value
        return CLOCKWISE_FACINGS[(idx + turns) and 3]
    }

    private fun rotateAxisValue(value: String, turns: Int): String {
        if (turns % 2 == 0) return value
        return when (value) {
            "x" -> "z"
            "z" -> "x"
            else -> value
        }
    }
}

/** Auto-orientation helpers deriving default directional properties in blueprint local space. */
object BlockAutoOrienter {
    /**
     * Maps a local horizontal vector `(dx, dz)` to local cardinal facing (`+Z`=north, `+X`=east, `-Z`=south, `-X`=west).
     */
    fun localFacingFromDelta(dx: Int, dz: Int, fallback: String = "north"): String = when {
        dx == 0 && dz == 0 -> fallback
        abs(dx) > abs(dz) -> if (dx > 0) "east" else "west"
        else -> if (dz > 0) "north" else "south"
    }

    /** Applies default `facing` and `half` when [resolved] is `STAIRS`. */
    fun orientStairs(resolved: ResolvedBlockSpec, towardX: Int, towardZ: Int, half: String = "bottom"): BlockSpec {
        if (resolved.kind != BlockKind.STAIRS) return resolved.spec
        val facing = localFacingFromDelta(towardX, towardZ)
        return resolved.spec.withDefaultProperties(mapOf("facing" to facing, "half" to half))
    }

    /** Applies default `axis` from the dominant delta when [resolved] is `LOG_OR_PILLAR`. */
    fun orientLog(resolved: ResolvedBlockSpec, dx: Int, dy: Int, dz: Int): BlockSpec {
        if (resolved.kind != BlockKind.LOG_OR_PILLAR) return resolved.spec
        val adx = abs(dx)
        val ady = abs(dy)
        val adz = abs(dz)
        val axis = when {
            ady >= adx && ady >= adz -> "y"
            adz >= adx -> "z"
            else -> "x"
        }
        return resolved.spec.withDefaultProperties(mapOf("axis" to axis))
    }

    /** Produces lower or upper half properties for [BlockKind.DOOR]. */
    fun orientDoorHalf(
        resolved: ResolvedBlockSpec,
        half: String,
        inferredFacing: String = "south",
    ): BlockSpec = resolved.spec.withDefaultProperties(
        mapOf(
            "facing" to inferredFacing,
            "half" to half,
            "hinge" to "left",
            "open" to "false",
        ),
    )
}
