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

/**
 * Reads Rocksmith 2014 .psarc archives with lazy (on-demand) decompression.
 *
 * Port of RocksmithToTabLib/PSARC.cs, which was itself a modified version of
 * RocksmithToolkitLib/PSARC/PSARC.cs.
 *
 * Key differences from the C# version:
 *   - Uses Android/JVM APIs (javax.crypto, java.util.zip.Inflater) instead of
 *     RijndaelEncryptor and zlib.net.
 *   - Decompression is triggered by Entry.openStream(), never eagerly.
 *   - RandomAccessFile is used for block reads so the TOC stream and data
 *     stream can be independent (the C# original reuses a single BigEndianBinaryReader
 *     anchored to the original file stream).
 */
class PsarcReader {

    // ── AES key for encrypted PSARC TOCs (RS2014 PC) ──────────────────────
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

    private val MAGIC_NUMBER = 0x50534152L   // "PSAR"
    private val COMPRESSION_ZLIB = 0x7A6C6962L  // "zlib"
    private val ARCHIVE_FLAG_ENCRYPTED = 4

    val entries: MutableList<Entry> = mutableListOf()

    /**
     * Opens and parses the PSARC at [filePath].
     * The file handle is kept open; call [close] when done.
     */
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
        val versionNumber  = headerStream.readUInt32()
        val compression    = headerStream.readUInt32()
        val totalTocSize   = headerStream.readUInt32()
        val tocEntrySize   = headerStream.readUInt32()
        val numFiles       = headerStream.readUInt32()
        val blockSize      = headerStream.readUInt32()
        val archiveFlags   = headerStream.readUInt32()

        if (magicNumber != MAGIC_NUMBER) throw IllegalArgumentException("Not a valid PSARC file")
        if (compression != COMPRESSION_ZLIB) throw IllegalArgumentException("Unsupported PSARC compression: $compression")

        // ── Read TOC (possibly encrypted) ────────────────────────────────
        val tocData = ByteArray((totalTocSize - 32).toInt())
        raf.readFully(tocData)

        val tocBytes = if (archiveFlags == ARCHIVE_FLAG_ENCRYPTED.toLong()) {
            decryptPsarcToc(tocData, (totalTocSize - 32).toInt())
        } else {
            tocData
        }

        val tocStream = BigEndianReader(ByteArrayInputStream(tocBytes))

        // ── Determine bytes-per-zLength entry ────────────────────────────
        var b = 1
        var num = 256L
        while (num < blockSize) {
            num *= 256
            b++
        }

        // ── Parse file entries ────────────────────────────────────────────
        for (i in 0 until numFiles.toInt()) {
            entries.add(Entry(
                id     = i,
                md5    = tocStream.readBytes(16),
                zIndex = tocStream.readUInt32(),
                length = tocStream.readUInt40(),
                offset = tocStream.readUInt40()
            ))
        }

        // ── Parse zLengths table ──────────────────────────────────────────
        val decMax = if (archiveFlags == ARCHIVE_FLAG_ENCRYPTED.toLong()) 32L else 0L
        val zLengthCount = ((totalTocSize - (tocStream.position + decMax)) / b).toInt()
        val zLengths = LongArray(zLengthCount)
        for (i in 0 until zLengthCount) {
            zLengths[i] = when (b) {
                2 -> tocStream.readUInt16().toLong()
                3 -> tocStream.readUInt24()
                4 -> tocStream.readUInt32()
                else -> throw IllegalStateException("Unexpected zLength byte width: $b")
            }
        }

        // ── Attach data pointers ──────────────────────────────────────────
        for (entry in entries) {
            entry.dataSource = Entry.DataSource(entry, raf, zLengths, blockSize.toInt())
        }

        // ── Read name table ───────────────────────────────────────────────
        readNames()
    }

    private fun readNames() {
        val nameEntry = entries.firstOrNull() ?: return
        nameEntry.name = "NamesBlock.bin"

        val data = nameEntry.dataSource!!.openStream().readBytes()
        val nameBlock = String(data, Charsets.US_ASCII)
        val names = nameBlock.split('\n')

        // entries[0] is the names block itself; subsequent entries map 1:1 to lines
        for (i in 1 until entries.size) {
            entries[i].name = if (i - 1 < names.size) names[i - 1] else ""
        }

        // Remove the name-block entry so entries[] are the actual files
        entries.removeAt(0)
    }

    // ── AES-CFB decryption of PSARC TOC ──────────────────────────────────
    private fun decryptPsarcToc(encrypted: ByteArray, length: Int): ByteArray {
        val key = SecretKeySpec(PSARC_KEY, "AES")
        val iv  = IvParameterSpec(ByteArray(16))   // zero IV for CFB
        val cipher = Cipher.getInstance("AES/CFB8/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)

        val decrypted = cipher.doFinal(encrypted)
        // Trim to the stated TOC size minus the 32-byte file header
        return decrypted.copyOf(minOf(length, decrypted.size))
    }

    // ── Inner classes ─────────────────────────────────────────────────────

    class Entry(
        val id: Int,
        val md5: ByteArray,
        val zIndex: Long,
        val length: Long,
        val offset: Long,
        var name: String = ""
    ) {
        var dataSource: DataSource? = null

        override fun toString() = name

        fun updateNameMd5(): ByteArray {
            val digest = MessageDigest.getInstance("MD5")
            return digest.digest(name.toByteArray(Charsets.US_ASCII))
        }

        /**
         * Lazy decompressor.
         * Port of Entry.DataPointer.OpenStream() in PSARC.cs.
         *
         * Each zlib-compressed block is identified by a non-zero zLength.
         * A zLength of 0 means the block is a full uncompressed blockSize chunk.
         * A block starting with bytes 0x78 0x9C (zlib magic) is inflated;
         * otherwise it is stored raw.
         */
        class DataSource(
            private val entry: Entry,
            private val raf: RandomAccessFile,
            private val zLengths: LongArray,
            private val blockSize: Int
        ) {
            fun openStream(): InputStream {
                val output = ByteArrayOutputStream()
                if (entry.length == 0L) return ByteArrayInputStream(ByteArray(0))

            synchronized(raf) {
                raf.seek(entry.offset)
                var blockIdx = entry.zIndex.toInt()
                
                while (output.size() < entry.length) {
                    // 1. Prevent out-of-bounds crash if chunks don't add up perfectly
                    if (blockIdx >= zLengths.size) {
                        break 
                    }
            
                    val zLen = zLengths[blockIdx].toInt()
                    if (zLen == 0) {
                        // 2. Uncompressed block: Only read what's left to avoid overshooting
                        val remaining = entry.length - output.size()
                        val readSize = minOf(blockSize.toLong(), remaining).toInt()
                        
                        val buf = ByteArray(readSize)
                        raf.readFully(buf)
                        output.write(buf)
                    } else {
                        val compressed = ByteArray(zLen)
                        raf.readFully(compressed)
                        
                        val isZlib = (compressed[0].toInt() and 0xFF) == 0x78
                        if (isZlib) {
                            val inflated = InflaterInputStream(
                                ByteArrayInputStream(compressed)
                            ).readBytes()
                            output.write(inflated)
                        } else {
                            output.write(compressed)
                        }
                    }
                    blockIdx++
                }
            }

                return ByteArrayInputStream(output.toByteArray(), 0, entry.length.toInt())
            }
        }
    }
}
