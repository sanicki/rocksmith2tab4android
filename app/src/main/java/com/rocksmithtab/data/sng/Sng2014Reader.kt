package com.rocksmithtab.data.sng

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.InflaterInputStream

/**
 * Reads Rocksmith 2014 binary .sng files into a typed [Sng2014] object.
 *
 * Ports of:
 *   - RocksmithToolkitLib/Sng/Sng2014HSL.cs  (data structures)
 *   - RocksmithToolkitLib/Sng/Sng2014File.cs  (file reader / decryptor)
 *
 * SNG files are encrypted with AES-CFB128 (re-initialized per 16-byte block with
 * counter-incremented IV) and then zlib-compressed.
 */
object Sng2014Reader {

    // ── Decryption keys ───────────────────────────────────────────────────
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

    // ── Public API ────────────────────────────────────────────────────────

    fun read(stream: InputStream, platform: Platform = Platform.PC): Sng2014 {
        val raw = stream.readBytes()
        val decrypted = decrypt(raw, platform)
        val decompressed = decompress(decrypted)
        return parse(decompressed)
    }

    // ── Decrypt ───────────────────────────────────────────────────────────
    //
    // SNG encryption: AES-256 in CFB-128 mode, re-initialized for each 16-byte
    // block with a counter-incremented IV.  This mirrors C#
    // RijndaelEncryptor.DecryptSngData which creates a new ICryptoTransform per
    // block and increments rij.IV between blocks.
    //
    // CRITICAL: must use "AES/CFB/NoPadding" (= CFB-128, 16-byte feedback segment),
    // NOT "AES/CFB8/NoPadding" (= CFB-8, 1-byte feedback segment).  CFB8 produces
    // completely wrong plaintext and is the root cause of the 12.9 KB empty output.

    private fun decrypt(data: ByteArray, platform: Platform): ByteArray {
        // SNG header (little-endian):
        //   4 bytes  magic low byte = 0x4A
        //   4 bytes  platform flags
        //  16 bytes  IV
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int and 0xFF
        if (magic != 0x4A) {
            // No encryption header — return raw bytes (already decrypted or unencrypted)
            return data
        }
        buf.int  // platform flags — skip
        val iv = ByteArray(16).also { buf.get(it) }

        val key = if (platform == Platform.MAC) SNG_KEY_MAC else SNG_KEY_PC
        val keySpec = SecretKeySpec(key, "AES")

        val payload = data.copyOfRange(24, data.size)
        val output  = ByteArrayOutputStream(payload.size)

        // Decrypt 16 bytes at a time.  Each block uses a fresh CFB-128 cipher
        // initialised with the current IV, then IV is incremented as a big-endian
        // counter before the next block.  This is identical to what the original
        // C# RijndaelEncryptor does.
        val currentIv = iv.copyOf()
        var offset = 0
        while (offset < payload.size) {
            val chunkSize = minOf(16, payload.size - offset)
            val block = if (chunkSize == 16) {
                payload.copyOfRange(offset, offset + 16)
            } else {
                // Last partial block: pad to 16 for the cipher, write only chunkSize bytes
                payload.copyOfRange(offset, offset + chunkSize) + ByteArray(16 - chunkSize)
            }

            // ── FIX: use CFB (= CFB-128) not CFB8 ────────────────────────────
            val cipher = Cipher.getInstance("AES/CFB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(currentIv))
            val decBlock = cipher.doFinal(block)
            output.write(decBlock, 0, chunkSize)

            // Increment IV as big-endian counter
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

    // ── Decompress (zlib) ─────────────────────────────────────────────────
    //
    // After decryption the data begins with an 8-byte header:
    //   4 bytes  uncompressed length (LE u32) — used to pre-allocate; Inflater ignores it
    //   4 bytes  compressed length  (LE u32)

    private fun decompress(data: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val uncompressedLen = buf.int   // informational; not used to limit reading
        val compressedLen   = buf.int
        // Guard against corrupt lengths
        val safeCompLen = compressedLen.coerceIn(0, data.size - 8)
        val compressed = data.copyOfRange(8, 8 + safeCompLen)
        return InflaterInputStream(ByteArrayInputStream(compressed)).readBytes()
    }

    // ── Parse binary structure ─────────────────────────────────────────────
    // Matches Sng.read() order in Sng2014HSL.cs exactly.

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

    // ── Section readers ────────────────────────────────────────────────────

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
                      IntArray(6) { r.readInt32() }, r.readBytes(32))
        })
    }

    private fun readBendData32(r: LittleEndianReader) =
        BendData32(r.readFloat(), r.readFloat(), r.readInt16(), r.readByte(), r.readByte())

    private fun readBendData(r: LittleEndianReader): BendData {
        val data = Array(32) { readBendData32(r) }
        val usedCount = r.readInt32()
        return BendData(data, usedCount)
    }

    private fun readChordNotesSection(r: LittleEndianReader): ChordNotesSection {
        val count = r.readInt32()
        return ChordNotesSection(count, Array(count) {
            ChordNotes(
                noteMask       = LongArray(6) { r.readUInt32() },
                bendData       = Array(6) { readBendData(r) },
                slideTo        = r.readBytes(6),
                slideUnpitchTo = r.readBytes(6),
                vibrato        = ShortArray(6) { r.readInt16() }
            )
        })
    }

    private fun readVocalSection(r: LittleEndianReader): VocalSection {
        val count = r.readInt32()
        return VocalSection(count, Array(count) {
            Vocal(r.readFloat(), r.readInt32(), r.readFloat(), r.readBytes(48))
        })
    }

    private fun readSymbolsHeaderSection(r: LittleEndianReader): SymbolsHeaderSection {
        val count = r.readInt32()
        return SymbolsHeaderSection(count, Array(count) {
            SymbolsHeader(r.readInt32(), r.readInt32(), r.readInt32(), r.readInt32(),
                          r.readInt32(), r.readInt32(), r.readInt32(), r.readInt32())
        })
    }

    private fun readSymbolsTextureSection(r: LittleEndianReader): SymbolsTextureSection {
        val count = r.readInt32()
        return SymbolsTextureSection(count, Array(count) {
            SymbolsTexture(r.readBytes(128), r.readInt32(), r.readInt32(), r.readInt32(), r.readInt32())
        })
    }

    private fun readRect(r: LittleEndianReader) = Rect(r.readFloat(), r.readFloat(), r.readFloat(), r.readFloat())

    private fun readSymbolDefinitionSection(r: LittleEndianReader): SymbolDefinitionSection {
        val count = r.readInt32()
        return SymbolDefinitionSection(count, Array(count) {
            SymbolDefinition(r.readBytes(12), readRect(r), readRect(r))
        })
    }

    private fun readPhraseIterationSection(r: LittleEndianReader): PhraseIterationSection {
        val count = r.readInt32()
        return PhraseIterationSection(count, Array(count) {
            PhraseIteration2014(r.readInt32(), r.readFloat(), r.readFloat(),
                                IntArray(3) { r.readInt32() })
        })
    }

    private fun readPhraseExtraInfoSection(r: LittleEndianReader): PhraseExtraInfoSection {
        val count = r.readInt32()
        return PhraseExtraInfoSection(count, Array(count) {
            PhraseExtraInfo(r.readInt32(), r.readInt32(), r.readInt32(),
                            r.readByte(), r.readInt16(), r.readByte())
        })
    }

    private fun readNLinkedDifficultySection(r: LittleEndianReader): NLinkedDifficultySection {
        val count = r.readInt32()
        return NLinkedDifficultySection(count, Array(count) {
            val levelBreak  = r.readInt32()
            val phraseCount = r.readInt32()
            NLinkedDifficulty(levelBreak, phraseCount, IntArray(phraseCount) { r.readInt32() })
        })
    }

    private fun readActionSection(r: LittleEndianReader): ActionSection {
        val count = r.readInt32()
        return ActionSection(count, Array(count) { Action(r.readFloat(), r.readBytes(256)) })
    }

    private fun readEventSection(r: LittleEndianReader): EventSection {
        val count = r.readInt32()
        return EventSection(count, Array(count) { Event2014(r.readFloat(), r.readBytes(256)) })
    }

    private fun readToneSection(r: LittleEndianReader): ToneSection {
        val count = r.readInt32()
        return ToneSection(count, Array(count) { Tone2014Sng(r.readFloat(), r.readInt32()) })
    }

    private fun readDnaSection(r: LittleEndianReader): DnaSection {
        val count = r.readInt32()
        return DnaSection(count, Array(count) { Dna(r.readFloat(), r.readInt32()) })
    }

    private fun readSectionSection(r: LittleEndianReader): SectionSection {
        val count = r.readInt32()
        return SectionSection(count, Array(count) {
            Section2014(r.readBytes(32), r.readInt32(), r.readFloat(), r.readFloat(),
                        r.readInt32(), r.readInt32(), r.readBytes(36))
        })
    }

    private fun readBendDataSection(r: LittleEndianReader): BendDataSection {
        val count = r.readInt32()
        return BendDataSection(count, Array(count) { readBendData32(r) })
    }

    private fun readNotes(r: LittleEndianReader): Notes2014 {
        return Notes2014(
            noteMask         = r.readUInt32(),
            noteFlags        = r.readUInt32(),
            hash             = r.readUInt32(),
            time             = r.readFloat(),
            stringIndex      = r.readByte(),
            fretId           = r.readByte(),
            anchorFretId     = r.readByte(),
            anchorWidth      = r.readByte(),
            chordId          = r.readInt32(),
            chordNotesId     = r.readInt32(),
            phraseId         = r.readInt32(),
            phraseIterationId= r.readInt32(),
            fingerPrintId    = ShortArray(2) { r.readInt16() },
            nextIterNote     = r.readInt16(),
            prevIterNote     = r.readInt16(),
            parentPrevNote   = r.readInt16(),
            slideTo          = r.readByte(),
            slideUnpitchTo   = r.readByte(),
            leftHand         = r.readByte(),
            tap              = r.readByte(),
            pickDirection    = r.readByte(),
            slap             = r.readByte(),
            pluck            = r.readByte(),
            vibrato          = r.readInt16(),
            sustain          = r.readFloat(),
            maxBend          = r.readFloat(),
            bendData         = readBendDataSection(r)
        )
    }

    private fun readNotesSection(r: LittleEndianReader): NotesSection {
        val count = r.readInt32()
        return NotesSection(count, Array(count) { readNotes(r) })
    }

    private fun readAnchorSection(r: LittleEndianReader): AnchorSection {
        val count = r.readInt32()
        return AnchorSection(count, Array(count) {
            Anchor2014(r.readFloat(), r.readFloat(), r.readFloat(), r.readFloat(),
                       r.readByte(), r.readBytes(3), r.readInt32(), r.readInt32())
        })
    }

    private fun readAnchorExtensionSection(r: LittleEndianReader): AnchorExtensionSection {
        val count = r.readInt32()
        return AnchorExtensionSection(count, Array(count) {
            AnchorExtension(r.readFloat(), r.readByte(), r.readInt32(), r.readInt16(), r.readByte())
        })
    }

    private fun readFingerprintSection(r: LittleEndianReader): FingerprintSection {
        val count = r.readInt32()
        return FingerprintSection(count, Array(count) {
            Fingerprint(r.readInt32(), r.readFloat(), r.readFloat(), r.readFloat(), r.readFloat())
        })
    }

    private fun readArrangementSection(r: LittleEndianReader): ArrangementSection {
        val count = r.readInt32()
        return ArrangementSection(count, Array(count) {
            val difficulty         = r.readInt32()
            val anchors            = readAnchorSection(r)
            val anchorExtensions   = readAnchorExtensionSection(r)
            val fingerprints1      = readFingerprintSection(r)
            val fingerprints2      = readFingerprintSection(r)
            val notes              = readNotesSection(r)
            val phraseCount        = r.readInt32()
            val avgNotes           = FloatArray(phraseCount) { r.readFloat() }
            val piCount1           = r.readInt32()
            val notesInIt1         = IntArray(piCount1) { r.readInt32() }
            val piCount2           = r.readInt32()
            val notesInIt2         = IntArray(piCount2) { r.readInt32() }
            Arrangement2014(difficulty, anchors, anchorExtensions, fingerprints1, fingerprints2,
                            notes, phraseCount, avgNotes, piCount1, notesInIt1, piCount2, notesInIt2)
        })
    }

    private fun readMetadata(r: LittleEndianReader): Metadata2014 {
        val maxScore          = r.readDouble()
        val maxNotesAndChords = r.readDouble()
        val maxNotesReal      = r.readDouble()
        val pointsPerNote     = r.readDouble()
        val firstBeatLength   = r.readFloat()
        val startTime         = r.readFloat()
        val capoFretId        = r.readByte()
        val lastConvDate      = r.readBytes(32)
        val part              = r.readInt16()
        val songLength        = r.readFloat()
        val stringCount       = r.readInt32()
        val tuning            = ShortArray(stringCount) { r.readInt16() }
        val unk11             = r.readFloat()
        val unk12             = r.readFloat()
        val maxDifficulty     = r.readInt32()
        return Metadata2014(maxScore, maxNotesAndChords, maxNotesReal, pointsPerNote,
                            firstBeatLength, startTime, capoFretId, lastConvDate,
                            part, songLength, stringCount, tuning, unk11, unk12, maxDifficulty)
    }
}

// ── Little-endian reader (SNG data is little-endian after decryption) ─────────

internal class LittleEndianReader(private val stream: java.io.InputStream) {
    fun readByte(): Int = stream.read().also { if (it == -1) throw java.io.EOFException() }
    fun readBytes(n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = stream.read(b, off, n - off); if (r == -1) throw java.io.EOFException(); off += r }
        return b
    }
    fun readInt16(): Short  = ByteBuffer.wrap(readBytes(2)).order(ByteOrder.LITTLE_ENDIAN).short
    fun readInt32(): Int    = ByteBuffer.wrap(readBytes(4)).order(ByteOrder.LITTLE_ENDIAN).int
    fun readUInt32(): Long  = readInt32().toLong() and 0xFFFFFFFFL
    fun readInt64(): Long   = ByteBuffer.wrap(readBytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
    fun readFloat(): Float  = ByteBuffer.wrap(readBytes(4)).order(ByteOrder.LITTLE_ENDIAN).float
    fun readDouble(): Double= ByteBuffer.wrap(readBytes(8)).order(ByteOrder.LITTLE_ENDIAN).double
}

// ── Data classes mirroring Sng2014HSL.cs structs ─────────────────────────────

data class Sng2014(
    var bpms: BpmSection                       = BpmSection(0, emptyArray()),
    var phrases: PhraseSection                 = PhraseSection(0, emptyArray()),
    var chords: ChordSection                   = ChordSection(0, emptyArray()),
    var chordNotes: ChordNotesSection          = ChordNotesSection(0, emptyArray()),
    var vocals: VocalSection                   = VocalSection(0, emptyArray()),
    var symbolsHeader: SymbolsHeaderSection    = SymbolsHeaderSection(0, emptyArray()),
    var symbolsTexture: SymbolsTextureSection  = SymbolsTextureSection(0, emptyArray()),
    var symbolsDefinition: SymbolDefinitionSection = SymbolDefinitionSection(0, emptyArray()),
    var phraseIterations: PhraseIterationSection   = PhraseIterationSection(0, emptyArray()),
    var phraseExtraInfo: PhraseExtraInfoSection     = PhraseExtraInfoSection(0, emptyArray()),
    var nld: NLinkedDifficultySection          = NLinkedDifficultySection(0, emptyArray()),
    var actions: ActionSection                 = ActionSection(0, emptyArray()),
    var events: EventSection                   = EventSection(0, emptyArray()),
    var tones: ToneSection                     = ToneSection(0, emptyArray()),
    var dnas: DnaSection                       = DnaSection(0, emptyArray()),
    var sections: SectionSection               = SectionSection(0, emptyArray()),
    var arrangements: ArrangementSection       = ArrangementSection(0, emptyArray()),
    var metadata: Metadata2014                 = Metadata2014()
)

data class Bpm(val time: Float, val measure: Short, val beat: Short, val phraseIteration: Int, val mask: Int)
data class BpmSection(val count: Int, val bpms: Array<Bpm>)

data class Phrase(val solo: Int, val disparity: Int, val ignore: Int, val padding: Int,
                  val maxDifficulty: Int, val phraseIterationLinks: Int, val name: ByteArray)
data class PhraseSection(val count: Int, val phrases: Array<Phrase>)

data class Chord2014(val mask: Long, val frets: ByteArray, val fingers: ByteArray,
                     val notes: IntArray, val name: ByteArray)
data class ChordSection(val count: Int, val chords: Array<Chord2014>)

data class BendData32(val time: Float, val step: Float, val unk3: Short, val unk4: Int, val unk5: Int)
data class BendData(val bendData32: Array<BendData32>, val usedCount: Int)
data class BendDataSection(val count: Int, val bendData: Array<BendData32>)

data class ChordNotes(val noteMask: LongArray, val bendData: Array<BendData>,
                      val slideTo: ByteArray, val slideUnpitchTo: ByteArray, val vibrato: ShortArray)
data class ChordNotesSection(val count: Int, val chordNotes: Array<ChordNotes>)

data class Vocal(val time: Float, val note: Int, val length: Float, val lyric: ByteArray)
data class VocalSection(val count: Int, val vocals: Array<Vocal>)

data class SymbolsHeader(val unk1: Int, val unk2: Int, val unk3: Int, val unk4: Int,
                         val unk5: Int, val unk6: Int, val unk7: Int, val unk8: Int)
data class SymbolsHeaderSection(val count: Int, val symbolsHeader: Array<SymbolsHeader>)

data class SymbolsTexture(val font: ByteArray, val fontPathLength: Int, val unk1: Int,
                          val width: Int, val height: Int)
data class SymbolsTextureSection(val count: Int, val symbolsTextures: Array<SymbolsTexture>)

data class Rect(val yMin: Float, val xMin: Float, val yMax: Float, val xMax: Float)
data class SymbolDefinition(val text: ByteArray, val rectOuter: Rect, val rectInner: Rect)
data class SymbolDefinitionSection(val count: Int, val symbolDefinitions: Array<SymbolDefinition>)

data class PhraseIteration2014(val phraseId: Int, val startTime: Float,
                                val nextPhraseTime: Float, val difficulty: IntArray)
data class PhraseIterationSection(val count: Int, val phraseIterations: Array<PhraseIteration2014>)

data class PhraseExtraInfo(val phraseId: Int, val difficulty: Int, val empty: Int,
                           val levelJump: Int, val redundant: Short, val padding: Int)
data class PhraseExtraInfoSection(val count: Int, val phraseExtraInfo: Array<PhraseExtraInfo>)

data class NLinkedDifficulty(val levelBreak: Int, val phraseCount: Int, val nldPhrase: IntArray)
data class NLinkedDifficultySection(val count: Int, val nLinkedDifficulties: Array<NLinkedDifficulty>)

data class Action(val time: Float, val actionName: ByteArray)
data class ActionSection(val count: Int, val actions: Array<Action>)

data class Event2014(val time: Float, val eventName: ByteArray)
data class EventSection(val count: Int, val events: Array<Event2014>)

data class Tone2014Sng(val time: Float, val toneId: Int)
data class ToneSection(val count: Int, val tones: Array<Tone2014Sng>)

data class Dna(val time: Float, val dnaId: Int)
data class DnaSection(val count: Int, val dnas: Array<Dna>)

data class Section2014(val name: ByteArray, val number: Int, val startTime: Float,
                       val endTime: Float, val startPhraseIterationId: Int,
                       val endPhraseIterationId: Int, val stringMask: ByteArray)
data class SectionSection(val count: Int, val sections: Array<Section2014>)

data class Anchor2014(val startBeatTime: Float, val endBeatTime: Float,
                      val unk3FirstNoteTime: Float, val unk4LastNoteTime: Float,
                      val fretId: Int, val padding: ByteArray,
                      val width: Int, val phraseIterationId: Int)
data class AnchorSection(val count: Int, val anchors: Array<Anchor2014>)

data class AnchorExtension(val beatTime: Float, val fretId: Int, val unk2: Int,
                           val unk3: Short, val unk4: Int)
data class AnchorExtensionSection(val count: Int, val anchorExtensions: Array<AnchorExtension>)

data class Fingerprint(val chordId: Int, val startTime: Float, val endTime: Float,
                       val unk3FirstNoteTime: Float, val unk4LastNoteTime: Float)
data class FingerprintSection(val count: Int, val fingerprints: Array<Fingerprint>)

data class Notes2014(
    val noteMask: Long, val noteFlags: Long, val hash: Long,
    val time: Float, val stringIndex: Int, val fretId: Int,
    val anchorFretId: Int, val anchorWidth: Int,
    val chordId: Int, val chordNotesId: Int, val phraseId: Int, val phraseIterationId: Int,
    val fingerPrintId: ShortArray, val nextIterNote: Short, val prevIterNote: Short,
    val parentPrevNote: Short, val slideTo: Int, val slideUnpitchTo: Int,
    val leftHand: Int, val tap: Int, val pickDirection: Int, val slap: Int, val pluck: Int,
    val vibrato: Short, val sustain: Float, val maxBend: Float, val bendData: BendDataSection
)
data class NotesSection(val count: Int, val notes: Array<Notes2014>)

data class Arrangement2014(
    val difficulty: Int, val anchors: AnchorSection,
    val anchorExtensions: AnchorExtensionSection,
    val fingerprints1: FingerprintSection, val fingerprints2: FingerprintSection,
    val notes: NotesSection, val phraseCount: Int,
    val averageNotesPerIteration: FloatArray,
    val phraseIterationCount1: Int, val notesInIteration1: IntArray,
    val phraseIterationCount2: Int, val notesInIteration2: IntArray
)
data class ArrangementSection(val count: Int, val arrangements: Array<Arrangement2014>)

data class Metadata2014(
    val maxScore: Double = 0.0, val maxNotesAndChords: Double = 0.0,
    val maxNotesAndChordsReal: Double = 0.0, val pointsPerNote: Double = 0.0,
    val firstBeatLength: Float = 0f, val startTime: Float = 0f,
    val capoFretId: Int = 0, val lastConversionDateTime: ByteArray = ByteArray(32),
    val part: Short = 0, val songLength: Float = 0f,
    val stringCount: Int = 0, val tuning: ShortArray = ShortArray(0),
    val unk11FirstNoteTime: Float = 0f, val unk12FirstNoteTime: Float = 0f,
    val maxDifficulty: Int = 0
)
