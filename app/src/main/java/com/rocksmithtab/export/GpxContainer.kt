package com.rocksmithtab.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream

/**
 * Builds a Guitar Pro .gpx binary container.
 *
 * Fixed to use absolute block-based offsets to prevent file corruption.
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
        payload.writeInt32LE(1)
        payload.writeInt32LE(files.size)
        
        // Corrected for block padding: (BCFS + BCFE) + imrf header
        var offset = (2 * BLOCK_SIZE) + HEADER_SIZE
        for (file in files) {
            payload.writeInt32LE(offset)
            payload.writeInt32LE(file.content.size)
            offset += file.content.size
        }
        return wrapBlock(MAGIC_BCFS, payload.toByteArray())
    }

    private fun buildBcfeBlock(): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.writeInt32LE(files.size)
        for (file in files) {
            val nameBytes = file.name.toByteArray(Charsets.UTF_8)
            val namePadded = ByteArray(127)
            nameBytes.copyInto(namePadded, 0, 0, minOf(nameBytes.size, 126))
            payload.write(namePadded)
            payload.write(0) 
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

    private fun ByteArrayOutputStream.writeInt32LE(value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        write(buf.array())
    }

    private data class GpxFile(val name: String, val content: ByteArray)
}
