package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Validates and sequentially rasterizes [PrimitiveOp] lists into a local-space [VoxelGrid].
 */
class BlueprintRasterizer(
    private val catalog: BlockCatalog,
    private val maxVolume: Long = VoxelGrid.MAX_RASTERIZED_VOLUME,
) {
    /** Validates all block references and structural parameters of [op] without mutating any grid. */
    fun validate(op: PrimitiveOp, depth: Int = 0) {
        if (depth > MAX_REPEAT_DEPTH) {
            throw BlueprintException(
                BlueprintErrorCode.INVALID_ARGUMENTS,
                "repeat_pattern nesting depth exceeds maximum of $MAX_REPEAT_DEPTH.",
            )
        }
        when (op) {
            is PrimitiveOp.SetBlock -> {
                catalog.parseAndResolve(op.block)
            }
            is PrimitiveOp.FillBox -> {
                checkOpBoundsCap(BlueprintBounds.normalized(op.from, op.to))
                catalog.parseAndResolve(op.block)
            }
            is PrimitiveOp.HollowBox -> {
                checkOpBoundsCap(BlueprintBounds.normalized(op.from, op.to))
                catalog.parseAndResolve(op.block)
            }
            is PrimitiveOp.Line -> {
                checkOpBoundsCap(BlueprintBounds.normalized(op.from, op.to))
                catalog.parseAndResolve(op.block)
            }
            is PrimitiveOp.Cylinder -> {
                validateCylinderParams(op)
                catalog.parseAndResolve(op.block)
            }
            is PrimitiveOp.Dome -> {
                validateDomeParams(op)
                catalog.parseAndResolve(op.block)
            }
            is PrimitiveOp.GableRoof -> {
                validateGableRoofParams(op)
                catalog.parseAndResolve(op.block)
                op.ridgeBlock?.let { catalog.parseAndResolve(it) }
            }
            is PrimitiveOp.Staircase -> {
                validateStaircaseParams(op)
                catalog.parseAndResolve(op.block)
            }
            is PrimitiveOp.ReplaceBox -> {
                checkOpBoundsCap(BlueprintBounds.normalized(op.from, op.to))
                catalog.parseAndResolve(op.target)
                catalog.parseAndResolve(op.replacement)
            }
            is PrimitiveOp.ClearBox -> {
                checkOpBoundsCap(BlueprintBounds.normalized(op.from, op.to))
            }
            is PrimitiveOp.RepeatPattern -> {
                if (op.count !in 1..MAX_REPEAT_COUNT) {
                    throw BlueprintException(
                        BlueprintErrorCode.INVALID_ARGUMENTS,
                        "repeat_pattern 'count' (${op.count}) must be between 1 and $MAX_REPEAT_COUNT.",
                    )
                }
                validate(op.op, depth + 1)
            }
        }
    }

    /** Rasterizes [ops] sequentially from index 0 to N into a fresh [VoxelGrid]. */
    fun rasterize(ops: List<PrimitiveOp>): VoxelGrid =
        rasterizeStored(ops.mapIndexed { idx, op -> StoredBlueprintOp("op_${idx + 1}", op) })

    /**
     * Rasterizes stored operations sequentially while maintaining final-state voxel-to-phase attribution.
     */
    fun rasterizeStored(ops: List<StoredBlueprintOp>): VoxelGrid {
        val grid = VoxelGrid(maxVolume = maxVolume)
        for (stored in ops) {
            applyOp(grid, stored.op, stored.phaseId, depth = 0)
            grid.checkEnclosingVolumeCap()
        }
        autoConnectConnectables(grid)
        return grid
    }

    private fun autoConnectConnectables(grid: VoxelGrid) {
        val updates = ArrayList<Pair<BlockPos3i, BlockSpec>>()
        for ((pos, spec) in grid.entries()) {
            val resolved = catalog.resolve(spec)
            if (resolved.kind != BlockKind.CONNECTABLE) continue
            val defaults = mapOf(
                "north" to (grid.get(pos.plus(BlockPos3i(0, 0, 1))) != null).toString(),
                "east" to (grid.get(pos.plus(BlockPos3i(1, 0, 0))) != null).toString(),
                "south" to (grid.get(pos.plus(BlockPos3i(0, 0, -1))) != null).toString(),
                "west" to (grid.get(pos.plus(BlockPos3i(-1, 0, 0))) != null).toString(),
            )
            val connected = spec.withDefaultProperties(defaults)
            if (connected != spec) {
                updates.add(pos to connected)
            }
        }
        for ((pos, connected) in updates) {
            grid.replacePreservingPhase(pos, connected)
        }
    }

    private fun applyOp(grid: VoxelGrid, op: PrimitiveOp, phaseId: String?, depth: Int) {
        if (depth > MAX_REPEAT_DEPTH) {
            throw BlueprintException(
                BlueprintErrorCode.INVALID_ARGUMENTS,
                "repeat_pattern nesting depth exceeds maximum of $MAX_REPEAT_DEPTH.",
            )
        }
        when (op) {
            is PrimitiveOp.SetBlock -> applySetBlock(grid, op, phaseId)
            is PrimitiveOp.FillBox -> applyFillBox(grid, op, phaseId)
            is PrimitiveOp.HollowBox -> applyHollowBox(grid, op, phaseId)
            is PrimitiveOp.Line -> applyLine(grid, op, phaseId)
            is PrimitiveOp.Cylinder -> applyCylinder(grid, op, phaseId)
            is PrimitiveOp.Dome -> applyDome(grid, op, phaseId)
            is PrimitiveOp.GableRoof -> applyGableRoof(grid, op, phaseId)
            is PrimitiveOp.Staircase -> applyStaircase(grid, op, phaseId)
            is PrimitiveOp.ReplaceBox -> applyReplaceBox(grid, op, phaseId)
            is PrimitiveOp.ClearBox -> applyClearBox(grid, op)
            is PrimitiveOp.RepeatPattern -> applyRepeatPattern(grid, op, phaseId, depth)
        }
    }

    private fun applySetBlock(grid: VoxelGrid, op: PrimitiveOp.SetBlock, phaseId: String?) {
        val resolved = catalog.parseAndResolve(op.block)
        if (resolved.spec.isAir) {
            grid.remove(op.pos)
            return
        }
        if (resolved.kind == BlockKind.DOOR && !resolved.spec.properties.containsKey("half")) {
            val inferredFacing = inferDoorFacing(grid, op.pos)
            grid.set(op.pos, BlockAutoOrienter.orientDoorHalf(resolved, "lower", inferredFacing), phaseId)
            grid.set(op.pos.plus(BlockPos3i(0, 1, 0)), BlockAutoOrienter.orientDoorHalf(resolved, "upper", inferredFacing), phaseId)
            return
        }
        grid.set(op.pos, resolved.spec, phaseId)
    }

    private fun inferDoorFacing(grid: VoxelGrid, pos: BlockPos3i): String {
        val probes = listOf(
            "north" to BlockPos3i(0, 0, 1),
            "south" to BlockPos3i(0, 0, -1),
            "east" to BlockPos3i(1, 0, 0),
            "west" to BlockPos3i(-1, 0, 0),
        )
        for ((facing, delta) in probes) {
            val frontEmpty = grid.get(pos.plus(delta)) == null
            val backSolid = grid.get(pos.plus(BlockPos3i(-delta.x, 0, -delta.z))) != null
            if (frontEmpty && backSolid) return facing
        }
        for ((facing, delta) in probes) {
            if (grid.get(pos.plus(delta)) == null) return facing
        }
        return "south"
    }

    private fun applyFillBox(grid: VoxelGrid, op: PrimitiveOp.FillBox, phaseId: String?) {
        val bounds = BlueprintBounds.normalized(op.from, op.to)
        grid.checkBoundsCap(bounds)
        val resolved = catalog.parseAndResolve(op.block)
        val oriented = BlockAutoOrienter.orientLog(
            resolved,
            dx = bounds.sizeX - 1,
            dy = bounds.sizeY - 1,
            dz = bounds.sizeZ - 1,
        )
        for (y in bounds.min.y..bounds.max.y) {
            for (z in bounds.min.z..bounds.max.z) {
                for (x in bounds.min.x..bounds.max.x) {
                    grid.set(BlockPos3i(x, y, z), oriented, phaseId)
                }
            }
        }
    }

    private fun applyHollowBox(grid: VoxelGrid, op: PrimitiveOp.HollowBox, phaseId: String?) {
        val bounds = BlueprintBounds.normalized(op.from, op.to)
        grid.checkBoundsCap(bounds)
        val resolved = catalog.parseAndResolve(op.block)
        for (y in bounds.min.y..bounds.max.y) {
            for (z in bounds.min.z..bounds.max.z) {
                for (x in bounds.min.x..bounds.max.x) {
                    val onWall = x == bounds.min.x || x == bounds.max.x || z == bounds.min.z || z == bounds.max.z
                    val onCap = !op.wallsOnly && (y == bounds.min.y || y == bounds.max.y)
                    if (onWall || onCap) {
                        grid.set(BlockPos3i(x, y, z), resolved.spec, phaseId)
                    }
                }
            }
        }
    }

    private fun applyLine(grid: VoxelGrid, op: PrimitiveOp.Line, phaseId: String?) {
        grid.checkBoundsCap(BlueprintBounds.normalized(op.from, op.to))
        val resolved = catalog.parseAndResolve(op.block)
        val dx = op.to.x - op.from.x
        val dy = op.to.y - op.from.y
        val dz = op.to.z - op.from.z
        val oriented = BlockAutoOrienter.orientLog(resolved, dx = dx, dy = dy, dz = dz)

        var x = op.from.x
        var y = op.from.y
        var z = op.from.z
        val adx = abs(dx)
        val ady = abs(dy)
        val adz = abs(dz)
        val sx = if (dx >= 0) 1 else -1
        val sy = if (dy >= 0) 1 else -1
        val sz = if (dz >= 0) 1 else -1

        grid.set(BlockPos3i(x, y, z), oriented, phaseId)
        when {
            adx >= ady && adx >= adz -> {
                var errY = 2 * ady - adx
                var errZ = 2 * adz - adx
                while (x != op.to.x) {
                    x += sx
                    if (errY >= 0) {
                        y += sy
                        errY -= 2 * adx
                    }
                    if (errZ >= 0) {
                        z += sz
                        errZ -= 2 * adx
                    }
                    errY += 2 * ady
                    errZ += 2 * adz
                    grid.set(BlockPos3i(x, y, z), oriented, phaseId)
                }
            }
            ady >= adx && ady >= adz -> {
                var errX = 2 * adx - ady
                var errZ = 2 * adz - ady
                while (y != op.to.y) {
                    y += sy
                    if (errX >= 0) {
                        x += sx
                        errX -= 2 * ady
                    }
                    if (errZ >= 0) {
                        z += sz
                        errZ -= 2 * ady
                    }
                    errX += 2 * adx
                    errZ += 2 * adz
                    grid.set(BlockPos3i(x, y, z), oriented, phaseId)
                }
            }
            else -> {
                var errX = 2 * adx - adz
                var errY = 2 * ady - adz
                while (z != op.to.z) {
                    z += sz
                    if (errX >= 0) {
                        x += sx
                        errX -= 2 * adz
                    }
                    if (errY >= 0) {
                        y += sy
                        errY -= 2 * adz
                    }
                    errX += 2 * adx
                    errY += 2 * ady
                    grid.set(BlockPos3i(x, y, z), oriented, phaseId)
                }
            }
        }
    }

    private fun applyCylinder(grid: VoxelGrid, op: PrimitiveOp.Cylinder, phaseId: String?) {
        val bounds = validateCylinderParams(op)
        grid.checkBoundsCap(bounds)
        val resolved = catalog.parseAndResolve(op.block)
        val threshold = (op.radius + 0.5) * (op.radius + 0.5)

        fun inside(dx: Int, dz: Int): Boolean = dx * dx + dz * dz <= threshold

        for (dx in -op.radius..op.radius) {
            for (dz in -op.radius..op.radius) {
                if (!inside(dx, dz)) continue
                if (op.hollow) {
                    val perimeter = !inside(dx + 1, dz) || !inside(dx - 1, dz) ||
                        !inside(dx, dz + 1) || !inside(dx, dz - 1)
                    if (!perimeter) continue
                }
                for (dy in 0 until op.height) {
                    grid.set(
                        BlockPos3i(op.center.x + dx, op.center.y + dy, op.center.z + dz),
                        resolved.spec,
                        phaseId,
                    )
                }
            }
        }
    }

    private fun applyDome(grid: VoxelGrid, op: PrimitiveOp.Dome, phaseId: String?) {
        val bounds = validateDomeParams(op)
        grid.checkBoundsCap(bounds)
        val resolved = catalog.parseAndResolve(op.block)
        val threshold = (op.radius + 0.5) * (op.radius + 0.5)

        fun insideSphere(dx: Int, dy: Int, dz: Int): Boolean =
            dx * dx + dy * dy + dz * dz <= threshold

        for (dy in 0..op.radius) {
            for (dz in -op.radius..op.radius) {
                for (dx in -op.radius..op.radius) {
                    if (!insideSphere(dx, dy, dz)) continue
                    if (op.hollow) {
                        val shell = !insideSphere(dx + 1, dy, dz) || !insideSphere(dx - 1, dy, dz) ||
                            !insideSphere(dx, dy + 1, dz) || !insideSphere(dx, dy - 1, dz) ||
                            !insideSphere(dx, dy, dz + 1) || !insideSphere(dx, dy, dz - 1)
                        if (!shell) continue
                    }
                    grid.set(
                        BlockPos3i(op.center.x + dx, op.center.y + dy, op.center.z + dz),
                        resolved.spec,
                        phaseId,
                    )
                }
            }
        }
    }

    private fun applyGableRoof(grid: VoxelGrid, op: PrimitiveOp.GableRoof, phaseId: String?) {
        val bounds = validateGableRoofParams(op)
        grid.checkBoundsCap(bounds)
        val slopeResolved = catalog.parseAndResolve(op.block)
        val ridgeSpec = op.ridgeBlock?.let { catalog.parseAndResolve(it).spec } ?: slopeResolved.spec

        val baseY = bounds.min.y
        if (op.ridgeAxis == RidgeAxis.Z) {
            val span = bounds.sizeX
            val slopeSteps = span / 2
            for (step in 0 until slopeSteps) {
                val y = baseY + step
                val leftX = bounds.min.x + step
                val rightX = bounds.max.x - step
                val leftStair = BlockAutoOrienter.orientStairs(slopeResolved, towardX = 1, towardZ = 0)
                val rightStair = BlockAutoOrienter.orientStairs(slopeResolved, towardX = -1, towardZ = 0)
                for (z in bounds.min.z..bounds.max.z) {
                    grid.set(BlockPos3i(leftX, y, z), leftStair, phaseId)
                    grid.set(BlockPos3i(rightX, y, z), rightStair, phaseId)
                }
            }
            if (span % 2 == 1) {
                val peakX = bounds.min.x + slopeSteps
                val peakY = baseY + slopeSteps
                for (z in bounds.min.z..bounds.max.z) {
                    grid.set(BlockPos3i(peakX, peakY, z), ridgeSpec, phaseId)
                }
            }
        } else {
            val span = bounds.sizeZ
            val slopeSteps = span / 2
            for (step in 0 until slopeSteps) {
                val y = baseY + step
                val nearZ = bounds.min.z + step
                val farZ = bounds.max.z - step
                val nearStair = BlockAutoOrienter.orientStairs(slopeResolved, towardX = 0, towardZ = 1)
                val farStair = BlockAutoOrienter.orientStairs(slopeResolved, towardX = 0, towardZ = -1)
                for (x in bounds.min.x..bounds.max.x) {
                    grid.set(BlockPos3i(x, y, nearZ), nearStair, phaseId)
                    grid.set(BlockPos3i(x, y, farZ), farStair, phaseId)
                }
            }
            if (span % 2 == 1) {
                val peakZ = bounds.min.z + slopeSteps
                val peakY = baseY + slopeSteps
                for (x in bounds.min.x..bounds.max.x) {
                    grid.set(BlockPos3i(x, peakY, peakZ), ridgeSpec, phaseId)
                }
            }
        }
    }

    private fun applyStaircase(grid: VoxelGrid, op: PrimitiveOp.Staircase, phaseId: String?) {
        val bounds = validateStaircaseParams(op)
        grid.checkBoundsCap(bounds)
        val resolved = catalog.parseAndResolve(op.block)

        val dx = op.to.x - op.from.x
        val dy = op.to.y - op.from.y
        val dz = op.to.z - op.from.z
        val primaryX = abs(dx) > abs(dz)
        val towardX = if (primaryX && dx != 0) (if (dx > 0) 1 else -1) else 0
        val towardZ = if (!primaryX && dz != 0) (if (dz > 0) 1 else -1) else 0
        val stairSpec = BlockAutoOrienter.orientStairs(resolved, towardX = towardX, towardZ = towardZ)
        val minY = minOf(op.from.y, op.to.y)

        val steps = maxOf(abs(dx), abs(dy), abs(dz))
        for (i in 0..steps) {
            val t = if (steps == 0) 0.0 else i.toDouble() / steps.toDouble()
            val sx = (op.from.x + dx * t).roundToInt()
            val sy = (op.from.y + dy * t).roundToInt()
            val sz = (op.from.z + dz * t).roundToInt()
            for (w in 0 until op.width) {
                val px = if (primaryX) sx else sx + w
                val pz = if (primaryX) sz + w else sz
                grid.set(BlockPos3i(px, sy, pz), stairSpec, phaseId)
                if (op.fillUnder) {
                    for (fillY in minY until sy) {
                        grid.set(BlockPos3i(px, fillY, pz), resolved.spec, phaseId)
                    }
                }
            }
        }
    }

    private fun applyReplaceBox(grid: VoxelGrid, op: PrimitiveOp.ReplaceBox, phaseId: String?) {
        val bounds = BlueprintBounds.normalized(op.from, op.to)
        grid.checkBoundsCap(bounds)
        val targetSpec = catalog.parseAndResolve(op.target).spec
        val replacementSpec = catalog.parseAndResolve(op.replacement).spec

        val matches = grid.entries().entries
            .filter { (pos, voxel) ->
                pos.x in bounds.min.x..bounds.max.x &&
                    pos.y in bounds.min.y..bounds.max.y &&
                    pos.z in bounds.min.z..bounds.max.z &&
                    targetSpec.matches(voxel)
            }
            .map { it.key }

        for (pos in matches) {
            if (replacementSpec.isAir) {
                grid.remove(pos)
            } else if (phaseId != null) {
                grid.set(pos, replacementSpec, phaseId)
            } else {
                grid.replacePreservingPhase(pos, replacementSpec)
            }
        }
    }

    private fun applyClearBox(grid: VoxelGrid, op: PrimitiveOp.ClearBox) {
        val bounds = BlueprintBounds.normalized(op.from, op.to)
        grid.checkBoundsCap(bounds)

        val toRemove = grid.entries().keys.filter { pos ->
            pos.x in bounds.min.x..bounds.max.x &&
                pos.y in bounds.min.y..bounds.max.y &&
                pos.z in bounds.min.z..bounds.max.z
        }
        for (pos in toRemove) {
            grid.remove(pos)
        }
    }

    private fun applyRepeatPattern(grid: VoxelGrid, op: PrimitiveOp.RepeatPattern, phaseId: String?, depth: Int) {
        if (op.count !in 1..MAX_REPEAT_COUNT) {
            throw BlueprintException(
                BlueprintErrorCode.INVALID_ARGUMENTS,
                "repeat_pattern 'count' (${op.count}) must be between 1 and $MAX_REPEAT_COUNT.",
            )
        }
        for (i in 0 until op.count) {
            applyOp(grid, op.op.offset(op.step.times(i)), phaseId, depth + 1)
        }
    }

    private fun validateCylinderParams(op: PrimitiveOp.Cylinder): BlueprintBounds {
        if (op.radius < 0) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "cylinder 'radius' must be >= 0.")
        }
        if (op.height < 1) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "cylinder 'height' must be >= 1.")
        }
        val bounds = BlueprintBounds(
            min = BlockPos3i(op.center.x - op.radius, op.center.y, op.center.z - op.radius),
            max = BlockPos3i(op.center.x + op.radius, op.center.y + op.height - 1, op.center.z + op.radius),
        )
        checkOpBoundsCap(bounds)
        return bounds
    }

    private fun validateDomeParams(op: PrimitiveOp.Dome): BlueprintBounds {
        if (op.radius < 0) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "dome 'radius' must be >= 0.")
        }
        val bounds = BlueprintBounds(
            min = BlockPos3i(op.center.x - op.radius, op.center.y, op.center.z - op.radius),
            max = BlockPos3i(op.center.x + op.radius, op.center.y + op.radius, op.center.z + op.radius),
        )
        checkOpBoundsCap(bounds)
        return bounds
    }

    private fun validateGableRoofParams(op: PrimitiveOp.GableRoof): BlueprintBounds {
        if (op.overhang < 0) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "gable_roof 'overhang' must be >= 0.")
        }
        val raw = BlueprintBounds.normalized(op.from, op.to)
        val minX = raw.min.x - op.overhang
        val maxX = raw.max.x + op.overhang
        val minZ = raw.min.z - op.overhang
        val maxZ = raw.max.z + op.overhang
        val span = if (op.ridgeAxis == RidgeAxis.Z) (maxX - minX + 1) else (maxZ - minZ + 1)
        val peakOffset = if (span % 2 == 1) span / 2 else maxOf(0, span / 2 - 1)
        val bounds = BlueprintBounds(
            min = BlockPos3i(minX, raw.min.y, minZ),
            max = BlockPos3i(maxX, raw.min.y + peakOffset, maxZ),
        )
        checkOpBoundsCap(bounds)
        return bounds
    }

    private fun validateStaircaseParams(op: PrimitiveOp.Staircase): BlueprintBounds {
        if (op.width < 1) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "staircase 'width' must be >= 1.")
        }
        val raw = BlueprintBounds.normalized(op.from, op.to)
        val dx = abs(op.to.x - op.from.x)
        val dz = abs(op.to.z - op.from.z)
        val primaryX = dx > dz
        val bounds = BlueprintBounds(
            min = raw.min,
            max = BlockPos3i(
                x = if (primaryX) raw.max.x else raw.max.x + op.width - 1,
                y = raw.max.y,
                z = if (primaryX) raw.max.z + op.width - 1 else raw.max.z,
            ),
        )
        checkOpBoundsCap(bounds)
        return bounds
    }

    private fun checkOpBoundsCap(bounds: BlueprintBounds) {
        if (bounds.volumeLong() > maxVolume) {
            throw BlueprintException(
                BlueprintErrorCode.VOLUME_TOO_LARGE,
                "Operation enclosing volume ${bounds.sizeX}x${bounds.sizeY}x${bounds.sizeZ} " +
                    "(${bounds.volumeLong()} blocks) exceeds cap of $maxVolume blocks.",
            )
        }
    }

    companion object {
        const val MAX_REPEAT_DEPTH = 4
        const val MAX_REPEAT_COUNT = 256
    }
}
