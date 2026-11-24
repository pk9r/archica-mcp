package com.archica.mcp.litematica

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NbtAndBitArrayTest {
    @Test
    fun `bit array packs and unpacks cleanly across 64-bit boundaries for 2, 3, and 4 bits`() {
        for (bits in listOf(2, 3, 4)) {
            val paletteSize = 1 shl bits
            val count = 137L
            val array = LitematicaBitArray(bits, count)
            for (i in 0 until count) {
                array.setAt(i, ((i * 7 + 3) % paletteSize).toInt())
            }
            for (i in 0 until count) {
                assertEquals(((i * 7 + 3) % paletteSize).toInt(), array.getAt(i), "Mismatch at index $i for bits=$bits")
            }
        }
    }

    @Test
    fun `bitsForPaletteSize enforces minimum 2 bits and exact powers of two`() {
        assertEquals(2, LitematicaBitArray.bitsForPaletteSize(1))
        assertEquals(2, LitematicaBitArray.bitsForPaletteSize(4))
        assertEquals(3, LitematicaBitArray.bitsForPaletteSize(5))
        assertEquals(3, LitematicaBitArray.bitsForPaletteSize(8))
        assertEquals(4, LitematicaBitArray.bitsForPaletteSize(9))
    }

    @Test
    fun `gzip nbt binary codec round-trips compound, list, long array, and primitives`() {
        val longValues = longArrayOf(0x123456789ABCDEF0L, -1L, 42L)
        val root = NbtTag.CompoundTag()
            .putInt("Version", 7)
            .putInt("SubVersion", 1)
            .putInt("MinecraftDataVersion", 4775)
            .put(
                "Metadata",
                NbtTag.CompoundTag()
                    .putString("Name", "oak_cottage")
                    .putLong("TimeCreated", 1700000000000L),
            )
            .put(
                "Palette",
                NbtTag.ListTag(
                    NbtTag.TAG_COMPOUND,
                    listOf(
                        NbtTag.CompoundTag().putString("Name", "minecraft:air"),
                        NbtTag.CompoundTag()
                            .putString("Name", "minecraft:oak_stairs")
                            .put("Properties", NbtTag.CompoundTag().putString("facing", "east")),
                    ),
                ),
            )
            .putLongArray("BlockStates", longValues)
            .put("EmptyList", NbtTag.ListTag(NbtTag.TAG_COMPOUND, emptyList()))

        val gzipped = NbtBinaryCodec.toGzipBytes(root)
        val decoded = NbtBinaryCodec.fromGzipBytes(gzipped)

        assertEquals(7, decoded.getInt("Version"))
        assertEquals(1, decoded.getInt("SubVersion"))
        assertEquals(4775, decoded.getInt("MinecraftDataVersion"))
        assertEquals("oak_cottage", decoded.getCompound("Metadata")?.getString("Name"))
        assertEquals(1700000000000L, decoded.getCompound("Metadata")?.getLong("TimeCreated"))
        assertContentEquals(longValues, decoded.getLongArray("BlockStates"))
        val palette = decoded.getList("Palette")!!
        assertEquals(2, palette.elements.size)
        val stairs = palette.elements[1] as NbtTag.CompoundTag
        assertEquals("minecraft:oak_stairs", stairs.getString("Name"))
        assertEquals("east", stairs.getCompound("Properties")?.getString("facing"))
        assertEquals(0, decoded.getList("EmptyList")?.elements?.size)
    }
}
