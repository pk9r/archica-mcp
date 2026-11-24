package com.archica.mcp.litematica

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Minimal pure-Kotlin NBT AST supporting the tags required by `.litematic` schematics. */
sealed interface NbtTag {
    val typeId: Byte

    data class ByteTag(val value: Byte) : NbtTag {
        override val typeId: Byte get() = TAG_BYTE
    }

    data class ShortTag(val value: Short) : NbtTag {
        override val typeId: Byte get() = TAG_SHORT
    }

    data class IntTag(val value: Int) : NbtTag {
        override val typeId: Byte get() = TAG_INT
    }

    data class LongTag(val value: Long) : NbtTag {
        override val typeId: Byte get() = TAG_LONG
    }

    data class FloatTag(val value: Float) : NbtTag {
        override val typeId: Byte get() = TAG_FLOAT
    }

    data class DoubleTag(val value: Double) : NbtTag {
        override val typeId: Byte get() = TAG_DOUBLE
    }

    data class ByteArrayTag(val values: ByteArray) : NbtTag {
        override val typeId: Byte get() = TAG_BYTE_ARRAY

        override fun equals(other: Any?): Boolean = other is ByteArrayTag && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }

    data class StringTag(val value: String) : NbtTag {
        override val typeId: Byte get() = TAG_STRING
    }

    data class ListTag(
        val elementType: Byte,
        val elements: List<NbtTag>,
    ) : NbtTag {
        override val typeId: Byte get() = TAG_LIST
    }

    data class CompoundTag(
        val entries: LinkedHashMap<String, NbtTag> = LinkedHashMap(),
    ) : NbtTag {
        override val typeId: Byte get() = TAG_COMPOUND

        fun put(name: String, tag: NbtTag): CompoundTag {
            entries[name] = tag
            return this
        }

        fun putInt(name: String, value: Int): CompoundTag = put(name, IntTag(value))

        fun putLong(name: String, value: Long): CompoundTag = put(name, LongTag(value))

        fun putString(name: String, value: String): CompoundTag = put(name, StringTag(value))

        fun putLongArray(name: String, values: LongArray): CompoundTag = put(name, LongArrayTag(values))

        fun getCompound(name: String): CompoundTag? = entries[name] as? CompoundTag

        fun getList(name: String): ListTag? = entries[name] as? ListTag

        fun getInt(name: String): Int? = (entries[name] as? IntTag)?.value

        fun getLong(name: String): Long? = (entries[name] as? LongTag)?.value

        fun getString(name: String): String? = (entries[name] as? StringTag)?.value

        fun getLongArray(name: String): LongArray? = (entries[name] as? LongArrayTag)?.values
    }

    data class IntArrayTag(val values: IntArray) : NbtTag {
        override val typeId: Byte get() = TAG_INT_ARRAY

        override fun equals(other: Any?): Boolean = other is IntArrayTag && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }

    data class LongArrayTag(val values: LongArray) : NbtTag {
        override val typeId: Byte get() = TAG_LONG_ARRAY

        override fun equals(other: Any?): Boolean = other is LongArrayTag && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }

    companion object {
        const val TAG_END: Byte = 0
        const val TAG_BYTE: Byte = 1
        const val TAG_SHORT: Byte = 2
        const val TAG_INT: Byte = 3
        const val TAG_LONG: Byte = 4
        const val TAG_FLOAT: Byte = 5
        const val TAG_DOUBLE: Byte = 6
        const val TAG_BYTE_ARRAY: Byte = 7
        const val TAG_STRING: Byte = 8
        const val TAG_LIST: Byte = 9
        const val TAG_COMPOUND: Byte = 10
        const val TAG_INT_ARRAY: Byte = 11
        const val TAG_LONG_ARRAY: Byte = 12
    }
}

/** Big-endian GZIP-compressed NBT binary encoder and decoder. */
object NbtBinaryCodec {
    fun writeGzipCompound(root: NbtTag.CompoundTag, output: OutputStream, rootName: String = "") {
        GZIPOutputStream(BufferedOutputStream(output)).use { gzip ->
            val dataOut = DataOutputStream(gzip)
            dataOut.writeByte(NbtTag.TAG_COMPOUND.toInt())
            writeStringPayload(dataOut, rootName)
            writeCompoundPayload(dataOut, root)
            dataOut.flush()
        }
    }

    fun toGzipBytes(root: NbtTag.CompoundTag, rootName: String = ""): ByteArray {
        val baos = ByteArrayOutputStream()
        writeGzipCompound(root, baos, rootName)
        return baos.toByteArray()
    }

    fun readGzipCompound(input: InputStream): NbtTag.CompoundTag {
        GZIPInputStream(BufferedInputStream(input)).use { gzip ->
            val dataIn = DataInputStream(gzip)
            val rootType = dataIn.readByte()
            require(rootType == NbtTag.TAG_COMPOUND) {
                "Expected root TAG_Compound (10) but found tag type $rootType."
            }
            readStringPayload(dataIn) // root name
            return readCompoundPayload(dataIn)
        }
    }

    fun fromGzipBytes(bytes: ByteArray): NbtTag.CompoundTag =
        readGzipCompound(ByteArrayInputStream(bytes))

    private fun writeTagPayload(out: DataOutputStream, tag: NbtTag) {
        when (tag) {
            is NbtTag.ByteTag -> out.writeByte(tag.value.toInt())
            is NbtTag.ShortTag -> out.writeShort(tag.value.toInt())
            is NbtTag.IntTag -> out.writeInt(tag.value)
            is NbtTag.LongTag -> out.writeLong(tag.value)
            is NbtTag.FloatTag -> out.writeFloat(tag.value)
            is NbtTag.DoubleTag -> out.writeDouble(tag.value)
            is NbtTag.ByteArrayTag -> {
                out.writeInt(tag.values.size)
                out.write(tag.values)
            }
            is NbtTag.StringTag -> writeStringPayload(out, tag.value)
            is NbtTag.ListTag -> {
                out.writeByte(tag.elementType.toInt())
                out.writeInt(tag.elements.size)
                for (element in tag.elements) {
                    require(element.typeId == tag.elementType) {
                        "List element type ${element.typeId} does not match declared type ${tag.elementType}."
                    }
                    writeTagPayload(out, element)
                }
            }
            is NbtTag.CompoundTag -> writeCompoundPayload(out, tag)
            is NbtTag.IntArrayTag -> {
                out.writeInt(tag.values.size)
                for (v in tag.values) out.writeInt(v)
            }
            is NbtTag.LongArrayTag -> {
                out.writeInt(tag.values.size)
                for (v in tag.values) out.writeLong(v)
            }
        }
    }

    private fun writeCompoundPayload(out: DataOutputStream, compound: NbtTag.CompoundTag) {
        for ((name, value) in compound.entries) {
            out.writeByte(value.typeId.toInt())
            writeStringPayload(out, name)
            writeTagPayload(out, value)
        }
        out.writeByte(NbtTag.TAG_END.toInt())
    }

    private fun writeStringPayload(out: DataOutputStream, value: String) {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        require(utf8.size <= 65535) { "NBT string exceeds 65535 bytes." }
        out.writeShort(utf8.size)
        out.write(utf8)
    }

    private fun readTagPayload(input: DataInputStream, typeId: Byte): NbtTag = when (typeId) {
        NbtTag.TAG_BYTE -> NbtTag.ByteTag(input.readByte())
        NbtTag.TAG_SHORT -> NbtTag.ShortTag(input.readShort())
        NbtTag.TAG_INT -> NbtTag.IntTag(input.readInt())
        NbtTag.TAG_LONG -> NbtTag.LongTag(input.readLong())
        NbtTag.TAG_FLOAT -> NbtTag.FloatTag(input.readFloat())
        NbtTag.TAG_DOUBLE -> NbtTag.DoubleTag(input.readDouble())
        NbtTag.TAG_BYTE_ARRAY -> {
            val len = input.readInt()
            val bytes = ByteArray(len)
            input.readFully(bytes)
            NbtTag.ByteArrayTag(bytes)
        }
        NbtTag.TAG_STRING -> NbtTag.StringTag(readStringPayload(input))
        NbtTag.TAG_LIST -> {
            val elementType = input.readByte()
            val count = input.readInt()
            val items = ArrayList<NbtTag>(maxOf(count, 0))
            repeat(count) {
                items.add(readTagPayload(input, elementType))
            }
            NbtTag.ListTag(elementType = elementType, elements = items)
        }
        NbtTag.TAG_COMPOUND -> readCompoundPayload(input)
        NbtTag.TAG_INT_ARRAY -> {
            val len = input.readInt()
            NbtTag.IntArrayTag(IntArray(len) { input.readInt() })
        }
        NbtTag.TAG_LONG_ARRAY -> {
            val len = input.readInt()
            NbtTag.LongArrayTag(LongArray(len) { input.readLong() })
        }
        else -> throw IllegalArgumentException("Unsupported NBT tag type: $typeId")
    }

    private fun readCompoundPayload(input: DataInputStream): NbtTag.CompoundTag {
        val entries = LinkedHashMap<String, NbtTag>()
        while (true) {
            val tagType = try {
                input.readByte()
            } catch (eof: EOFException) {
                throw IllegalStateException("Unexpected EOF while reading TAG_Compound.", eof)
            }
            if (tagType == NbtTag.TAG_END) break
            val name = readStringPayload(input)
            entries[name] = readTagPayload(input, tagType)
        }
        return NbtTag.CompoundTag(entries)
    }

    private fun readStringPayload(input: DataInputStream): String {
        val len = input.readUnsignedShort()
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
