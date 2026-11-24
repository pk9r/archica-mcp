package com.archica.mcp.litematica

/**
 * Pure-Kotlin bit-packed 64-bit array matching Litematica's `LitematicaBitArray` layout,
 * where multi-bit palette indices are packed continuously and may straddle 64-bit `long` boundaries.
 */
class LitematicaBitArray(
    val bitsPerEntry: Int,
    val arraySize: Long,
    backingArray: LongArray? = null,
) {
    private val maxEntryValue: Long
    val backingLongArray: LongArray

    init {
        require(bitsPerEntry in 1..32) { "bitsPerEntry must be in 1..32, got $bitsPerEntry." }
        require(arraySize >= 0L) { "arraySize must be >= 0, got $arraySize." }
        maxEntryValue = (1L shl bitsPerEntry) - 1L
        val requiredLongs = ((arraySize * bitsPerEntry + 63L) / 64L).toInt()
        if (backingArray != null) {
            require(backingArray.size >= requiredLongs) {
                "Backing LongArray length ${backingArray.size} is smaller than required $requiredLongs."
            }
            backingLongArray = backingArray
        } else {
            backingLongArray = LongArray(requiredLongs)
        }
    }

    fun setAt(index: Long, value: Int) {
        require(index in 0 until arraySize) { "Index $index out of bounds for size $arraySize." }
        val startOffset = index * bitsPerEntry
        val startArrIndex = (startOffset ushr 6).toInt()
        val endArrIndex = (((index + 1L) * bitsPerEntry - 1L) ushr 6).toInt()
        val startBitOffset = (startOffset and 63L).toInt()

        val maskedValue = value.toLong() and maxEntryValue
        backingLongArray[startArrIndex] =
            (backingLongArray[startArrIndex] and (maxEntryValue shl startBitOffset).inv()) or
                (maskedValue shl startBitOffset)

        if (startArrIndex != endArrIndex) {
            val endBitOffset = 64 - startBitOffset
            val newEndBits = bitsPerEntry - endBitOffset
            backingLongArray[endArrIndex] =
                ((backingLongArray[endArrIndex] ushr newEndBits) shl newEndBits) or
                    (maskedValue ushr endBitOffset)
        }
    }

    fun getAt(index: Long): Int {
        require(index in 0 until arraySize) { "Index $index out of bounds for size $arraySize." }
        val startOffset = index * bitsPerEntry
        val startArrIndex = (startOffset ushr 6).toInt()
        val endArrIndex = (((index + 1L) * bitsPerEntry - 1L) ushr 6).toInt()
        val startBitOffset = (startOffset and 63L).toInt()

        return if (startArrIndex == endArrIndex) {
            ((backingLongArray[startArrIndex] ushr startBitOffset) and maxEntryValue).toInt()
        } else {
            val endBitOffset = 64 - startBitOffset
            (((backingLongArray[startArrIndex] ushr startBitOffset) or
                (backingLongArray[endArrIndex] shl endBitOffset)) and maxEntryValue).toInt()
        }
    }

    companion object {
        /** Computes the Litematica bit width (`>= 2`) needed to address [paletteSize] entries. */
        fun bitsForPaletteSize(paletteSize: Int): Int {
            if (paletteSize <= 4) return 2
            val minBits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1)
            return maxOf(2, minBits)
        }
    }
}
