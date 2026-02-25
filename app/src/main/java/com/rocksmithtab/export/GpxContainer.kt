package com.rocksmithtab.export

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
