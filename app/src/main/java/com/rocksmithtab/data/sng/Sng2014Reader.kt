package com.rocksmithtab.data.sng

import com.rocksmithtab.utils.AppLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.zip.InflaterInputStream

/**
 * Reads Rocksmith 2014 binary .sng files into a typed [Sng2014] object.
 */
object Sng2014Reader {

    private val SNG_KEY_PC = byteArrayOf(
        0xCB.toByte(), 0x64.toByte(), 0x8D.toByte(), 0xF3.toByte(),
        0xD1.toByte(), 0x2A.toByte(), 0x16.toByte(), 0xBF.toByte(),
        0x71.toByte(), 0x70.toByte(), 0x14.toByte(), 0x14.toByte(),
        0xE6.toByte(), 0x96.toByte(), 0x19.toByte(), 0xEC.toByte(),
        0x17.toByte(), 0x1C.toByte(), 0xCA.toByte(), 0x5D.toByte(),
        0x2A.toByte(), 0x14.toByte(), 0x2E.toByte(), 0x3E.toByte(),
        0x59.toByte(), 0xDE.toByte(), 0x7A.toByte(), 0xDD.toByte(),
        0xA1.toByte(), 0x8A.toByte(), 0x3A.toByte(), 0x30.toByte()
    )

    private val SNG_KEY_MAC = byteArrayOf(
        0x98.toByte(), 0x21.toByte(), 0x33.toByte(), 0x0E.toByte(),
        0x34.toByte(), 0xB9.toByte(), 0x1F.toByte(), 0x70.toByte(),
        0xD0.toByte(), 0xA4.toByte(), 0x8C.toByte(), 0xBD.toByte(),
        0x62.toByte(), 0x59.toByte(), 0x93.toByte(), 0x12.toByte(),
        0x69.toByte(), 0x70.toByte(), 0xCE.toByte(), 0xA0.toByte(),
        0x91.toByte(), 0x92.toByte(), 0xC0.toByte(), 0xE6.toByte(),
        0xCD.toByte(), 0xA6.toByte(), 0x76.toByte(), 0xCC.toByte(),
        0x98.toByte(), 0x38.toByte(), 0x28.toByte(), 0x9D.toByte()
    )

    enum class Platform { PC, MAC }

    fun read(stream: InputStream, platform: Platform = Platform.PC): Sng2014 {
        AppLogger.d("Sng2014Reader", "Reading stream...")
        val raw = stream.readBytes()
        AppLogger.d("Sng2014Reader", "Raw bytes length: ${raw.size}")
        
        val decrypted = decrypt(raw, platform)
        AppLogger.d("Sng2014Reader", "Decrypted length: ${decrypted.size}")
        
        val decompressed = decompress(decrypted)
        AppLogger.d("Sng2014Reader", "Decompressed length: ${decompressed.size}")
        
        return parse(decompressed)
    }

    private fun decrypt(data: ByteArray, platform: Platform): ByteArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int and 0xFF
        AppLogger.d("Sng2014Reader", "Decryption magic byte: $magic (expected 74 for 0x4A)")
        
        if (magic != 0x4A) {
            AppLogger.w("Sng2014Reader", "Magic byte mismatch! Returning raw data.")
            return data
        }
        buf.int  // skip platform flags
        val iv = ByteArray(16).also { buf.get(it) }

        val key = if (platform == Platform.MAC) SNG_KEY_MAC else SNG_KEY_PC
        val keySpec = SecretKeySpec(key, "AES")

        val payload = data.copyOfRange(24, data.size)
        val output  = ByteArrayOutputStream(payload.size)

        val currentIv = iv.copyOf()
        val encryptedIv = ByteArray(16)
        
        // Manual CFB-128 via AES-ECB to bypass Android Crypto provider bugs
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        var offset = 0
        while (offset < payload.size) {
            val chunkSize = minOf(16, payload.size - offset)
            
            // 1. AES-ECB encrypt the current IV
            cipher.doFinal(currentIv, 0, 16, encryptedIv, 0)
            
            // 2. XOR the encrypted IV with the ciphertext block
            val decBlock = ByteArray(chunkSize)
            for (i in 0 until chunkSize) {
                decBlock[i] = (payload[offset + i].toInt() xor encryptedIv[i].toInt()).toByte()
            }
            output.write(decBlock)

            // 3. Increment IV as big-endian counter
            var j = currentIv.size - 1
            var carry = true
            while (j >= 0 && carry) {
                val sum = (currentIv[j].toInt() and 0xFF) + 1
                currentIv[j] = (sum and 0xFF).toByte()
                carry = sum > 0xFF
                j--
            }
            offset += 16
        }

        return output.toByteArray()
    }

    private fun decompress(data: ByteArray): ByteArray {
        if (data.size < 8) {
             AppLogger.e("Sng2014Reader", "Data too small to decompress (${data.size} bytes)")
             return data
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val uncompressedLen = buf.int   
        val compressedLen   = buf.int
        AppLogger.d("Sng2014Reader", "Header uncompressedLen=$uncompressedLen, compressedLen=$compressedLen")
        
        val safeCompLen = compressedLen.coerceIn(0, data.size - 8)
        AppLogger.d("Sng2014Reader", "Safe compressed len: $safeCompLen")
        val compressed = data.copyOfRange(8, 8 + safeCompLen)
        
        return try {
            InflaterInputStream(ByteArrayInputStream(compressed)).readBytes()
        } catch (e: Exception) {
            AppLogger.e("Sng2014Reader", "InflaterInputStream failed: ${e.message}", e)
            throw e
        }
    }

    private fun parse(data: ByteArray): Sng2014 {
        val r = LittleEndianReader(ByteArrayInputStream(data))
        val sng = Sng2014()
        sng.bpms               = readBpmSection(r)
        sng.phrases            = readPhraseSection(r)
        sng.chords             = readChordSection(r)
        sng.chordNotes         = readChordNotesSection(r)
        sng.vocals             = readVocalSection(r)
        sng.symbolsHeader      = readSymbolsHeaderSection(r)
        sng.symbolsTexture     = readSymbolsTextureSection(r)
        sng.symbolsDefinition  = readSymbolDefinitionSection(r)
        sng.phraseIterations   = readPhraseIterationSection(r)
        sng.phraseExtraInfo    = readPhraseExtraInfoSection(r)
        sng.nld                = readNLinkedDifficultySection(r)
        sng.actions            = readActionSection(r)
        sng.events             = readEventSection(r)
        sng.tones              = readToneSection(r)
        sng.dnas               = readDnaSection(r)
        sng.sections           = readSectionSection(r)
        sng.arrangements       = readArrangementSection(r)
        sng.metadata           = readMetadata(r)
        return sng
    }

    // --- Note: The rest of the Sng2014Reader section readers (readBpmSection, etc.) remain completely identical to the original Sng2014Reader file ---
    
    private fun readBpmSection(r: LittleEndianReader): BpmSection {
        val count = r.readInt32()
        return BpmSection(count, Array(count) {
            Bpm(r.readFloat(), r.readInt16(), r.readInt16(), r.readInt32(), r.readInt32())
        })
    }

    private fun readPhraseSection(r: LittleEndianReader): PhraseSection {
        val count = r.readInt32()
        return PhraseSection(count, Array(count) {
            Phrase(r.readByte(), r.readByte(), r.readByte(), r.readByte(),
                   r.readInt32(), r.readInt32(), r.readBytes(32))
        })
    }

    private fun readChordSection(r: LittleEndianReader): ChordSection {
        val count = r.readInt32()
        return ChordSection(count, Array(count) {
            Chord2014(r.readUInt32(), r.readBytes(6), r.readBytes(6),
                      IntArray(6) { r.readInt32() }, r.readBytes(6))
        })
    }

    private fun readChordNotesSection(r: LittleEndianReader): ChordNotesSection {
        val count = r.readInt32()
        return ChordNotesSection(count, Array(count) {
            ChordNotes(IntArray(6) { r.readInt32() }, r.readBytes(6),
                       r.readBytes(6), r.readBytes(6), r.readBytes(6),
                       r.readBytes(6), r.readBytes(6))
        })
    }

    private fun readVocalSection(r: LittleEndianReader): VocalSection {
        val count = r.readInt32()
        return VocalSection(count, Array(count) {
            Vocal(r.readFloat(), r.readFloat(), r.readBytes(48))
        })
    }

    private fun readSymbolsHeaderSection(r: LittleEndianReader): SymbolsHeaderSection {
        val count = r.readInt32()
        return SymbolsHeaderSection(count, Array(count) {
            SymbolsHeader(r.readInt32(), r.readInt32(), r.readInt32(), r.readInt32())
        })
    }

    private fun readSymbolsTextureSection(r: LittleEndianReader): SymbolsTextureSection {
        val count = r.readInt32()
        return SymbolsTextureSection(count, Array(count) {
            SymbolsTexture(r.readBytes(128), r.readInt32(), r.readInt32(), r.readInt32(), r.readInt32())
        })
    }

    private fun readSymbolDefinitionSection(r: LittleEndianReader): SymbolDefinitionSection {
        val count = r.readInt32()
        return SymbolDefinitionSection(count, Array(count) {
            SymbolDefinition(r.readBytes(12), r.readBytes(32), r.readFloat(), r.readFloat(),
                             r.readFloat(), r.readFloat(), r.readFloat(), r.readFloat(),
                             r.readFloat(), r.readFloat())
        })
    }

    private fun readPhraseIterationSection(r: LittleEndianReader): PhraseIterationSection {
        val count = r.readInt32()
        return PhraseIterationSection(count, Array(count) {
            PhraseIteration(r.readInt32(), r.readFloat(), r.readFloat(), r.readInt32() * 3)
        })
    }

    private fun readPhraseExtraInfoSection(r: LittleEndianReader): PhraseExtraInfoSection {
        val count = r.readInt32()
        return PhraseExtraInfoSection(count, Array(count) {
            PhraseExtraInfo(r.readInt32(), r.readFloat(), r.readInt16(), r.readByte(), r.readByte())
        })
    }

    private fun readNLinkedDifficultySection(r: LittleEndianReader): NLinkedDifficultySection {
        val count = r.readInt32()
        return NLinkedDifficultySection(count, Array(count) {
            NLinkedDifficulty(r.readInt32(), r.readInt32(), r.readInt32())
        })
    }

    private fun readActionSection(r: LittleEndianReader): ActionSection {
        val count = r.readInt32()
        return ActionSection(count, Array(count) {
            Action(r.readFloat(), r.readBytes(256))
        })
    }

    private fun readEventSection(r: LittleEndianReader): EventSection {
        val count = r.readInt32()
        return EventSection(count, Array(count) {
            Event(r.readFloat(), r.readBytes(256))
        })
    }

    private fun readToneSection(r: LittleEndianReader): ToneSection {
        val count = r.readInt32()
        return ToneSection(count, Array(count) {
            Tone(r.readFloat(), r.readInt32())
        })
    }

    private fun readDnaSection(r: LittleEndianReader): DnaSection {
        val count = r.readInt32()
        return DnaSection(count, Array(count) {
            Dna(r.readFloat(), r.readInt32())
        })
    }

    private fun readSectionSection(r: LittleEndianReader): SectionSection {
        val count = r.readInt32()
        return SectionSection(count, Array(count) {
            Section(r.readBytes(32), r.readInt32(), r.readInt32(), r.readFloat(),
                    r.readInt16(), r.readInt16())
        })
    }

    private fun readArrangementSection(r: LittleEndianReader): ArrangementSection {
        val count = r.readInt32()
        return ArrangementSection(count, Array(count) { readArrangement(r) })
    }

    private fun readArrangement(r: LittleEndianReader): Arrangement {
        val difficulty = r.readInt32()
        val anchorsCount = r.readInt32()
        val anchors = Array(anchorsCount) { Anchor(r.readFloat(), r.readFloat(), r.readBytes(4), r.readInt32()) }
        val notesCount = r.readInt32()
        val notes = Array(notesCount) {
            Note2014(r.readUInt32(), r.readFloat(), r.readFloat(), r.readInt32(), r.readInt32(),
                     r.readInt32(), r.readFloat(), r.readFloat(), r.readInt16(), r.readByte(),
                     r.readByte(), r.readInt16(), r.readByte(), r.readByte(), r.readInt16(),
                     r.readByte(), r.readByte(), r.readInt16(), r.readByte(), r.readByte(), r.readInt16())
        }
        val chordsCount = r.readInt32()
        val chords = Array(chordsCount) { Chord2014(r.readUInt32(), r.readBytes(6), r.readBytes(6), IntArray(6) { r.readInt32() }, r.readBytes(6)) }
        return Arrangement(difficulty, anchorsCount, anchors, notesCount, notes, chordsCount, chords)
    }

    private fun readMetadata(r: LittleEndianReader): Metadata {
        return Metadata(
            r.readDouble(), r.readInt16(), r.readInt16(), r.readInt16(), r.readInt16(),
            r.readDouble(), r.readInt16(), r.readInt16(), r.readInt16(), r.readInt16(),
            r.readFloat(), r.readInt32(), ShortArray(r.readInt32()) { r.readInt16() },
            r.readFloat(), r.readFloat(), r.readInt32(), r.readInt32(), r.readInt32()
        )
    }
}
