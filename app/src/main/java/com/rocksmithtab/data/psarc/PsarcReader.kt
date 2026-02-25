package com.rocksmithtab.data.psarc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PsarcReader {
    private val PSARC_KEY = byteArrayOf(
        0xC5.toByte(), 0x3D.toByte(), 0xB2.toByte(), 0x38.toByte(),
        0x70.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xF7.toByte(),
        0x1C.toByte(), 0xAE.toByte(), 0x64.toByte(), 0x06.toByte(),
        0x1F.toByte(), 0xDD.toByte(), 0x0E.toByte(), 0x11.toByte(),
        0x57.toByte(), 0x30.toByte(), 0x9D.toByte(), 0xC8.toByte(),
        0x52.toByte(), 0x04.toByte(), 0xD4.toByte(), 0xC5.toByte(),
        0xBF.toByte(), 0xDF.toByte(), 0x25.toByte(), 0x09.toByte(),
        0x0D.toByte(), 0xF2.toByte(), 0x57.toByte(), 0x2C.toByte()
    )

    private val MAGIC_NUMBER = 0x50534152L
    private val COMPRESSION_ZLIB = 0x7A6C6962L
    private val ARCHIVE_FLAG_ENCRYPTED = 4

    val entries: MutableList<Entry> = mutableListOf()

    fun read(filePath: String) {
        val raf = RandomAccessFile(filePath, "r")
        read(raf)
    }

    fun read(raf: RandomAccessFile) {
        entries.clear()
        val headerBytes = ByteArray(32)
        raf.readFully(headerBytes)
        val headerStream = BigEndianReader(ByteArrayInputStream(headerBytes))

        val magicNumber    = headerStream.readUInt32()
        headerStream.readUInt32() // version
        val compression    = headerStream.readUInt32()
        val totalTocSize   = headerStream.readUInt32()
        val tocEntrySize   = headerStream.readUInt32()
        val numFiles       = headerStream.readUInt32()
        val blockSize      = headerStream.readUInt32()
        val archiveFlags   = headerStream.readUInt32()

        if (magicNumber != MAGIC_NUMBER) throw IllegalArgumentException("Not a valid PSARC file")
        if (compression != COMPRESSION_ZLIB) throw IllegalArgumentException("Unsupported compression")

        val tocData = ByteArray((totalTocSize - 32).toInt())
        raf.readFully(tocData)

        val tocBytes = if (archiveFlags == ARCHIVE_FLAG_ENCRYPTED.toLong()) {
            decryptPsarcToc(tocData, (totalTocSize - 32).toInt())
        } else {
            tocData
        }

        val tocStream = BigEndianReader(ByteArrayInputStream(tocBytes))
        var b = 1
        var num = 256L
        while (num < blockSize) { num *= 256; b++ }

        for (i in 0 until numFiles.toInt()) {
            val startPos = tocStream.position
            entries.add(Entry(
                id     = i,
                md5    = tocStream.readBytes(16),
                zIndex = tocStream.readUInt32(),
                length = tocStream.readUInt40(),
                offset = tocStream.readUInt40()
            ))
            // FIX: Skip padding bytes to stay aligned with tocEntrySize
            val bytesRead = tocStream.position - startPos
            if (bytesRead < tocEntrySize) {
                tocStream.readBytes((tocEntrySize - bytesRead).toInt())
            }
        }

        val decMax = if (archiveFlags == ARCHIVE_FLAG_ENCRYPTED.toLong()) 32L else 0L
        val zLengthCount = ((totalTocSize - (tocStream.position + decMax)) / b).toInt()
        val zLengths = LongArray(zLengthCount)
        for (i in 0 until zLengthCount) {
            zLengths[i] = when (b) {
                2 -> tocStream.readUInt16().toLong()
                3 -> tocStream.readUInt24()
                4 -> tocStream.readUInt32()
                else -> throw IllegalStateException("Unexpected zLength width")
            }
        }

        for (entry in entries) {
            entry.dataSource = Entry.DataSource(entry, raf, zLengths, blockSize.toInt())
        }
        readNames()
    }

    private fun readNames() {
        val nameEntry = entries.firstOrNull() ?: return
        try {
            val data = nameEntry.dataSource!!.openStream().readBytes()
            val nameBlock = String(data, Charsets.US_ASCII)
            val names = nameBlock.split('\n')
            for (i in 1 until entries.size) {
                entries[i].name = if (i - 1 < names.size) names[i - 1] else ""
            }
        } catch (e: Exception) { e.printStackTrace() }
        if (entries.isNotEmpty()) entries.removeAt(0)
    }

    private fun decryptPsarcToc(encrypted: ByteArray, length: Int): ByteArray {
        val key = SecretKeySpec(PSARC_KEY, "AES")
        val iv  = IvParameterSpec(ByteArray(16))
        val cipher = Cipher.getInstance("AES/CFB8/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        return cipher.doFinal(encrypted).copyOf(length)
    }

    class Entry(val id: Int, val md5: ByteArray, val zIndex: Long, val length: Long, val offset: Long, var name: String = "") {
        var dataSource: DataSource? = null
        class DataSource(private val entry: Entry, private val raf: RandomAccessFile, private val zLengths: LongArray, private val blockSize: Int) {
            fun openStream(): InputStream {
                val output = ByteArrayOutputStream()
                if (entry.length == 0L) return ByteArrayInputStream(ByteArray(0))
                synchronized(raf) {
                    raf.seek(entry.offset)
                    var blockIdx = entry.zIndex.toInt()
                    while (output.size() < entry.length) {
                        if (blockIdx < 0 || blockIdx >= zLengths.size) break
                        val zLen = zLengths[blockIdx].toInt()
                        if (zLen == 0) {
                            val readSize = minOf(blockSize, (entry.length - output.size()).toInt())
                            val buf = ByteArray(readSize); raf.readFully(buf); output.write(buf)
                        } else {
                            val compressed = ByteArray(zLen); raf.readFully(compressed)
                            if ((compressed[0].toInt() and 0xFF) == 0x78) {
                                output.write(InflaterInputStream(ByteArrayInputStream(compressed)).readBytes())
                            } else { output.write(compressed) }
                        }
                        blockIdx++
                    }
                }
                return ByteArrayInputStream(output.toByteArray(), 0, entry.length.toInt())
            }
        }
    }
}
