package com.rocksmithtab.data.psarc

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val buf = ByteArray(2)
        buf[1] = readByte().toByte()
        buf[0] = readByte().toByte()
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    fun readInt32(): Int {
        return (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
    }

    fun readUInt32(): Long {
        val buf = ByteArray(4)
        for (i in 3 downTo 0) buf[i] = readByte().toByte()
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    fun readInt64(): Long {
        val buf = ByteArray(8)
        for (i in 7 downTo 0) buf[i] = readByte().toByte()
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long
    }

    fun readUInt64(): Long {
        val buf = ByteArray(8)
        for (i in 7 downTo 0) buf[i] = readByte().toByte()
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long
    }

    /** Reads a 5-byte big-endian unsigned integer (returned as Long). */
    fun readUInt40(): Long {
        val buf = ByteArray(8)
        for (i in 4 downTo 0) buf[i] = readByte().toByte()
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long
    }

    /** Reads a 3-byte big-endian unsigned integer (returned as Long). */
    fun readUInt24(): Long {
        val buf = ByteArray(4)
        for (i in 2 downTo 0) buf[i] = readByte().toByte()
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    fun readSingle(): Float {
        val buf = ByteArray(4)
        for (i in 3 downTo 0) buf[i] = readByte().toByte()
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).float
    }

    fun readDouble(): Double {
        val buf = ByteArray(8)
        for (i in 7 downTo 0) buf[i] = readByte().toByte()
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).double
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
