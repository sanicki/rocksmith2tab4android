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
}
