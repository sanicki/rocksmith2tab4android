package com.rocksmithtab.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Builds a Guitar Pro 6 .gpx binary container.
 *
 * Port of RocksmithToTabLib/GpxContainer.cs.
 *
 * A .gpx file is a BCFS container holding a single virtual file "score.gpif"
 * which contains the zlib-compressed GPIF XML.
 *
 * The container is made up of 4096-byte (0x1000) sectors. Each sector starts
 * with a 4-byte magic tag:
 *
 *   Sector 0  — "BCFS" header
 *   Sector 1  — "BCFE" file-entry index
 *   Sector 2+ — "imrf" data blocks (one per 4088 bytes of compressed content)
 *
 * All integers are little-endian.
 *
 * BCFS sector layout (after magic):
 *   u32  version  = 0x00000200
 *   repeated until end of sector:
 *     u32  offset         (byte offset of file data from start of container)
 *     u32  uncompressed   (= 0, field unused by GP6)
 *     u32  compressed_size
 *     u32  flags          (= 0)
 *
 * BCFE sector layout (after magic):
 *   repeated until end of sector:
 *     char[128]  null-terminated filename, zero-padded
 *     u32        file_index (0-based index into BCFS entries)
 *
 * imrf sector layout (after magic):
 *   raw compressed bytes, up to 4088 bytes per sector
 */
class GpxContainer {

    companion object {
        private const val SECTOR_SIZE   = 0x1000     // 4096 bytes per sector
        private const val MAGIC_SIZE    = 4
        // Usable payload bytes per sector (sector size minus 4-byte magic tag)
        private const val PAYLOAD_SIZE  = SECTOR_SIZE - MAGIC_SIZE
        // BCFS entry size: offset(4) + uncompressed(4) + size(4) + flags(4)
        private const val BCFS_ENTRY_SIZE = 16
        // BCFE entry size: name(128) + index(4)
        private const val BCFE_ENTRY_SIZE = 132
    }

    /**
     * Write the GPX container for a single GPIF XML string to [output].
     *
     * @param gpifXml  The GPIF XML content (UTF-8 string).
     */
    fun write(gpifXml: String, output: OutputStream) {
        val xmlBytes    = gpifXml.toByteArray(Charsets.UTF_8)
        val compressed  = compress(xmlBytes)
        val fileName    = "score.gpif"

        // ── Calculate layout ─────────────────────────────────────────────
        // Data starts at sector 2 (sectors 0=BCFS, 1=BCFE)
        val dataStartOffset = 2 * SECTOR_SIZE
        val numDataSectors  = (compressed.size + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE

        // ── Sector 0: BCFS ────────────────────────────────────────────────
        val bcfs = ByteArray(SECTOR_SIZE)
        val bw0  = ByteBuffer.wrap(bcfs).order(ByteOrder.LITTLE_ENDIAN)
        bw0.put("BCFS".toByteArray(Charsets.US_ASCII))
        bw0.putInt(0x00000200)                // version
        // Single file entry
        bw0.putInt(dataStartOffset)           // byte offset of file data
        bw0.putInt(xmlBytes.size)             // uncompressed size (informational)
        bw0.putInt(compressed.size)           // compressed size
        bw0.putInt(0)                         // flags
        // Rest of sector is already zero

        // ── Sector 1: BCFE ────────────────────────────────────────────────
        val bcfe = ByteArray(SECTOR_SIZE)
        val bw1  = ByteBuffer.wrap(bcfe).order(ByteOrder.LITTLE_ENDIAN)
        bw1.put("BCFE".toByteArray(Charsets.US_ASCII))
        // File entry: 128-byte name + 4-byte index
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val namePadded = ByteArray(128)
        nameBytes.copyInto(namePadded, 0, 0, minOf(nameBytes.size, 127))
        bw1.put(namePadded)
        bw1.putInt(0)                         // index into BCFS entries
        // Rest is zero

        // ── Sectors 2+: imrf data ─────────────────────────────────────────
        output.write(bcfs)
        output.write(bcfe)

        var dataOffset = 0
        repeat(numDataSectors) { sectorIdx ->
            val sector  = ByteArray(SECTOR_SIZE)
            val bwD     = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
            bwD.put("imrf".toByteArray(Charsets.US_ASCII))
            val chunkLen = minOf(PAYLOAD_SIZE, compressed.size - dataOffset)
            bwD.put(compressed, dataOffset, chunkLen)
            dataOffset += chunkLen
            output.write(sector)
        }

        output.flush()
    }

    // ── Compression ───────────────────────────────────────────────────────

    private fun compress(data: ByteArray): ByteArray {
        val bos      = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false) // zlib wrapper (not raw)
        val dos      = DeflaterOutputStream(bos, deflater)
        dos.write(data)
        dos.finish()
        deflater.end()
        return bos.toByteArray()
    }
}package com.rocksmithtab.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Builds a Guitar Pro 6 .gpx binary container.
 *
 * Port of RocksmithToTabLib/GpxContainer.cs.
 *
 * A .gpx file is a BCFS container holding a single virtual file "score.gpif"
 * which contains the zlib-compressed GPIF XML.
 *
 * The container is made up of 4096-byte (0x1000) sectors. Each sector starts
 * with a 4-byte magic tag:
 *
 *   Sector 0  — "BCFS" header
 *   Sector 1  — "BCFE" file-entry index
 *   Sector 2+ — "imrf" data blocks (one per 4088 bytes of compressed content)
 *
 * All integers are little-endian.
 *
 * BCFS sector layout (after magic):
 *   u32  version  = 0x00000200
 *   repeated until end of sector:
 *     u32  offset         (byte offset of file data from start of container)
 *     u32  uncompressed   (= 0, field unused by GP6)
 *     u32  compressed_size
 *     u32  flags          (= 0)
 *
 * BCFE sector layout (after magic):
 *   repeated until end of sector:
 *     char[128]  null-terminated filename, zero-padded
 *     u32        file_index (0-based index into BCFS entries)
 *
 * imrf sector layout (after magic):
 *   raw compressed bytes, up to 4088 bytes per sector
 */
class GpxContainer {

    companion object {
        private const val SECTOR_SIZE   = 0x1000     // 4096 bytes per sector
        private const val MAGIC_SIZE    = 4
        // Usable payload bytes per sector (sector size minus 4-byte magic tag)
        private const val PAYLOAD_SIZE  = SECTOR_SIZE - MAGIC_SIZE
        // BCFS entry size: offset(4) + uncompressed(4) + size(4) + flags(4)
        private const val BCFS_ENTRY_SIZE = 16
        // BCFE entry size: name(128) + index(4)
        private const val BCFE_ENTRY_SIZE = 132
    }

    /**
     * Write the GPX container for a single GPIF XML string to [output].
     *
     * @param gpifXml  The GPIF XML content (UTF-8 string).
     */
    fun write(gpifXml: String, output: OutputStream) {
        val xmlBytes    = gpifXml.toByteArray(Charsets.UTF_8)
        val compressed  = compress(xmlBytes)
        val fileName    = "score.gpif"

        // ── Calculate layout ─────────────────────────────────────────────
        // Data starts at sector 2 (sectors 0=BCFS, 1=BCFE)
        val dataStartOffset = 2 * SECTOR_SIZE
        val numDataSectors  = (compressed.size + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE

        // ── Sector 0: BCFS ────────────────────────────────────────────────
        val bcfs = ByteArray(SECTOR_SIZE)
        val bw0  = ByteBuffer.wrap(bcfs).order(ByteOrder.LITTLE_ENDIAN)
        bw0.put("BCFS".toByteArray(Charsets.US_ASCII))
        bw0.putInt(0x00000200)                // version
        // Single file entry
        bw0.putInt(dataStartOffset)           // byte offset of file data
        bw0.putInt(xmlBytes.size)             // uncompressed size (informational)
        bw0.putInt(compressed.size)           // compressed size
        bw0.putInt(0)                         // flags
        // Rest of sector is already zero

        // ── Sector 1: BCFE ────────────────────────────────────────────────
        val bcfe = ByteArray(SECTOR_SIZE)
        val bw1  = ByteBuffer.wrap(bcfe).order(ByteOrder.LITTLE_ENDIAN)
        bw1.put("BCFE".toByteArray(Charsets.US_ASCII))
        // File entry: 128-byte name + 4-byte index
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val namePadded = ByteArray(128)
        nameBytes.copyInto(namePadded, 0, 0, minOf(nameBytes.size, 127))
        bw1.put(namePadded)
        bw1.putInt(0)                         // index into BCFS entries
        // Rest is zero

        // ── Sectors 2+: imrf data ─────────────────────────────────────────
        output.write(bcfs)
        output.write(bcfe)

        var dataOffset = 0
        repeat(numDataSectors) { sectorIdx ->
            val sector  = ByteArray(SECTOR_SIZE)
            val bwD     = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
            bwD.put("imrf".toByteArray(Charsets.US_ASCII))
            val chunkLen = minOf(PAYLOAD_SIZE, compressed.size - dataOffset)
            bwD.put(compressed, dataOffset, chunkLen)
            dataOffset += chunkLen
            output.write(sector)
        }

        output.flush()
    }

    // ── Compression ───────────────────────────────────────────────────────

    private fun compress(data: ByteArray): ByteArray {
        val bos      = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false) // zlib wrapper (not raw)
        val dos      = DeflaterOutputStream(bos, deflater)
        dos.write(data)
        dos.finish()
        deflater.end()
        return bos.toByteArray()
    }
}package com.rocksmithtab.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream

/**
 * Builds a Guitar Pro .gpx binary container.
 *
 * Port of RocksmithToTabLib/GpxContainer.cs.
 *
 * A .gpx file is a container of named "blocks", each identified by a 4-byte
 * magic string. The relevant blocks are:
 *   - "BCFS" : container header
 *   - "BCFE" : index of file entries
 *   - "imrf" : XML content block (compressed GPIF)
 *
 * Block layout (little-endian):
 *   4  bytes  : magic
 *   4  bytes  : total block length (excluding magic + this length field)
 *   N  bytes  : payload
 */
class GpxContainer {

    companion object {
        private const val MAGIC_BCFS = "BCFS"
        private const val MAGIC_BCFE = "BCFE"
        private const val MAGIC_IMRF = "imrf"
        private const val BLOCK_SIZE = 0x1000   // 4096 bytes per block
        private const val HEADER_SIZE = 8       // magic (4) + length (4)
    }

    private val files = mutableListOf<GpxFile>()

    fun addFile(filename: String, content: ByteArray) {
        files.add(GpxFile(filename, content))
    }

    fun write(output: OutputStream) {
        // Compress GPIF XML content (deflate, level 9)
        val compressed = compress(getGpifContent())
        files.clear()
        addFile("score.gpif", compressed)

        val bcfsBlock = buildBcfsBlock()
        val bcfeBlock = buildBcfeBlock()
        val imrfBlock = buildImrfBlock()

        output.write(bcfsBlock)
        output.write(bcfeBlock)
        output.write(imrfBlock)
        output.flush()
    }

    private fun getGpifContent(): ByteArray {
        return files.firstOrNull()?.content ?: ByteArray(0)
    }

    // ── Block builders ────────────────────────────────────────────────────

    private fun buildBcfsBlock(): ByteArray {
        val payload = ByteArrayOutputStream()
        // BCFS version (1)
        payload.writeInt32LE(1)
        // Placeholder for file count
        payload.writeInt32LE(files.size)
        // Each file entry: 4 bytes offset + 4 bytes size
        var offset = HEADER_SIZE + 4 + 4 + files.size * 8
        for (file in files) {
            payload.writeInt32LE(offset)
            payload.writeInt32LE(file.content.size)
            offset += file.content.size
        }
        return wrapBlock(MAGIC_BCFS, payload.toByteArray())
    }

    private fun buildBcfeBlock(): ByteArray {
        val payload = ByteArrayOutputStream()
        // Number of entries
        payload.writeInt32LE(files.size)
        for (file in files) {
            // Null-terminated filename padded to 127 bytes
            val nameBytes = file.name.toByteArray(Charsets.UTF_8)
            val namePadded = ByteArray(127)
            nameBytes.copyInto(namePadded, 0, 0, minOf(nameBytes.size, 126))
            payload.write(namePadded)
            payload.write(0)  // null terminator
        }
        return wrapBlock(MAGIC_BCFE, payload.toByteArray())
    }

    private fun buildImrfBlock(): ByteArray {
        val payload = ByteArrayOutputStream()
        for (file in files) {
            payload.write(file.content)
        }
        return wrapBlock(MAGIC_IMRF, payload.toByteArray())
    }

    private fun wrapBlock(magic: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(magic.toByteArray(Charsets.US_ASCII))
        out.writeInt32LE(payload.size)
        out.write(payload)
        // Pad to BLOCK_SIZE boundary
        val totalSize = HEADER_SIZE + payload.size
        val paddingNeeded = (BLOCK_SIZE - (totalSize % BLOCK_SIZE)) % BLOCK_SIZE
        if (paddingNeeded > 0) out.write(ByteArray(paddingNeeded))
        return out.toByteArray()
    }

    private fun compress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val deflater = DeflaterOutputStream(bos, java.util.zip.Deflater(9))
        deflater.write(data)
        deflater.finish()
        return bos.toByteArray()
    }

    // ── Extension helpers ─────────────────────────────────────────────────

    private fun ByteArrayOutputStream.writeInt32LE(value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        write(buf.array())
    }

    // ── Data classes ──────────────────────────────────────────────────────

    private data class GpxFile(val name: String, val content: ByteArray)
}
