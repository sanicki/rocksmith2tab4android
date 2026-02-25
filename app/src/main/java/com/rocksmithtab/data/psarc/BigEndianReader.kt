package com.rocksmithtab.data.psarc

import java.io.InputStream

/**
 * Reads primitive types from a stream in big-endian byte order.
 * Port of RocksmithToolkitLib/PSARC/BigEndianBinaryReader.cs
 *
 * Notable additions vs the C# original:
 *   - readUInt40() reads a 5-byte big-endian unsigned integer (returned as Long)
 *   - readUInt24() reads a 3-byte big-endian unsigned integer (returned as Long)
 */
class BigEndianReader(private val stream: InputStream) : AutoCloseable {

    var position: Long = 0L
        private set

    fun readByte(): Int {
        val b = stream.read()
        if (b == -1) throw java.io.EOFException("Unexpected end of stream")
        position++
        return b and 0xFF
    }

    fun readBytes(count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = stream.read(buf, offset, count - offset)
            if (read == -1) throw java.io.EOFException("Unexpected end of stream at position $position")
            offset += read
            position += read
        }
        return buf
    }

    fun readBoolean(): Boolean = readByte() != 0

    fun readChar(): Char = readByte().toChar()

    fun readInt16(): Short {
        return ((readByte() shl 8) or readByte()).toShort()
    }

    fun readUInt16(): Int {
        // Big-endian: first byte is high, second is low
        return (readByte() shl 8) or readByte()
    }

    fun readInt32(): Int {
        return (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
    }

    fun readUInt32(): Long {
        return (readByte().toLong() shl 24) or (readByte().toLong() shl 16) or
               (readByte().toLong() shl  8) or  readByte().toLong()
    }

    fun readInt64(): Long {
        return (readByte().toLong() shl 56) or (readByte().toLong() shl 48) or
               (readByte().toLong() shl 40) or (readByte().toLong() shl 32) or
               (readByte().toLong() shl 24) or (readByte().toLong() shl 16) or
               (readByte().toLong() shl  8) or  readByte().toLong()
    }

    fun readUInt64(): Long {
        return (readByte().toLong() shl 56) or (readByte().toLong() shl 48) or
               (readByte().toLong() shl 40) or (readByte().toLong() shl 32) or
               (readByte().toLong() shl 24) or (readByte().toLong() shl 16) or
               (readByte().toLong() shl  8) or  readByte().toLong()
    }

    /** Reads a 5-byte big-endian unsigned integer (returned as Long). */
    fun readUInt40(): Long {
        return (readByte().toLong() shl 32) or (readByte().toLong() shl 24) or
               (readByte().toLong() shl 16) or (readByte().toLong() shl  8) or
                readByte().toLong()
    }

    /** Reads a 3-byte big-endian unsigned integer (returned as Long). */
    fun readUInt24(): Long {
        return (readByte().toLong() shl 16) or (readByte().toLong() shl 8) or readByte().toLong()
    }

    fun readSingle(): Float {
        val bits = ((readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte())
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun readDouble(): Double {
        val bits = (readByte().toLong() shl 56) or (readByte().toLong() shl 48) or
                   (readByte().toLong() shl 40) or (readByte().toLong() shl 32) or
                   (readByte().toLong() shl 24) or (readByte().toLong() shl 16) or
                   (readByte().toLong() shl  8) or  readByte().toLong()
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun seek(pos: Long) {
        // Only forward seeking supported on generic InputStreams.
        // For random access, wrap a RandomAccessFile or ByteArrayInputStream instead.
        val delta = pos - position
        if (delta < 0) throw UnsupportedOperationException("Backward seek not supported on this stream")
        if (delta > 0) skip(delta)
    }

    fun skip(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) throw java.io.EOFException("Stream ended during skip")
            remaining -= skipped
            position += skipped
        }
    }

    override fun close() = stream.close()
}
