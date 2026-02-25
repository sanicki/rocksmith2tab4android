package com.rocksmithtab.data.psarc

import com.rocksmithtab.data.model.*
import com.rocksmithtab.data.sng.*
import com.rocksmithtab.conversion.RhythmDetector

/**
 * Converts a parsed [Sng2014] + [Attributes2014] into the intermediate [Track] model.
 *
 * Port of the arrangement-extraction logic in:
 *   - RocksmithToTabLib/PSARCBrowser.cs  (GetArrangement)
 *   - RocksmithToTabLib/Converter.cs     (note/chord mapping)
 *   - RocksmithToolkitLib/XML/Song2014.cs (SongNote2014.Parse, SongChord2014.Parse)
 *
 * Note mask constants match RocksmithToolkitLib/Sng/Constants.cs.
 */
object SngToScore {

    // ── Note mask constants (from Constants.cs) ────────────────────────
    private const val NOTE_MASK_HAMMERON      = 0x00000200u
    private const val NOTE_MASK_PULLOFF       = 0x00000400u
    private const val NOTE_MASK_HARMONIC      = 0x00000020u
    private const val NOTE_MASK_PINCHHARMONIC = 0x00040000u
    private const val NOTE_MASK_PALMMUTE      = 0x00000040u
    private const val NOTE_MASK_MUTE          = 0x00020000u
    private const val NOTE_MASK_TREMOLO       = 0x00002000u
    private const val NOTE_MASK_ACCENT        = 0x00004000u
    private const val NOTE_MASK_PARENT        = 0x00008000u  // linkNext
    private const val NOTE_MASK_VIBRATO       = 0x00000100u
    private const val NOTE_MASK_TAP           = 0x00100000u
    private const val NOTE_MASK_SLAP          = 0x00080000u
    private const val NOTE_MASK_PLUCK         = 0x00040000u
    private const val NOTE_MASK_SLIDE         = 0x00000004u
    private const val NOTE_MASK_SLIDEUNPITCHEDTO = 0x00000800u
    private const val NOTE_MASK_CHORD         = 0x00000002u
    private const val NOTE_MASK_IGNORE        = 0x00010000u

    private val STANDARD_MIDI_NOTES = intArrayOf(40, 45, 50, 55, 59, 64)

    fun buildTrack(sng: Sng2014, attr: Attributes2014): Track {
        val track = Track(
            identifier     = attr.fullName,
            name           = attr.arrangementName,
            numStrings     = sng.metadata.stringCount.coerceAtLeast(4),
            instrument     = if (attr.arrangementType == 3) Track.InstrumentType.BASS
                             else Track.InstrumentType.GUITAR,
            path           = when (attr.arrangementType) {
                0    -> Track.PathType.LEAD
                1, 2 -> Track.PathType.RHYTHM
                3    -> Track.PathType.BASS
                else -> Track.PathType.LEAD
            },
            capo           = sng.metadata.capoFretId.let { if (it == 0xFF) 0 else it },
            tuning         = sng.metadata.tuning.map { it.toInt() }.toIntArray()
                                 .let { t -> IntArray(6) { i -> if (i < t.size) t[i] else 0 } }
        )

        // ── Chord templates ───────────────────────────────────────────────
        for ((i, chord) in sng.chords.chords.withIndex()) {
            val name = chord.name.toNullTerminatedAscii()
            val frets = chord.frets.map { (it.toInt() and 0xFF).let { f -> if (f == 255) -1 else f } }.toIntArray()
            val fingers = chord.fingers.map { (it.toInt() and 0xFF).let { f -> if (f == 255) -1 else f } }.toIntArray()
            track.chordTemplates[i] = ChordTemplate(name = name, frets = frets, fingers = fingers, chordId = i)
        }

        // ── Bars (from BPM section) ───────────────────────────────────────
        buildBars(sng, track)

        // ── Average BPM ───────────────────────────────────────────────────
        if (sng.bpms.bpms.isNotEmpty()) {
            track.averageBeatsPerMinute = estimateAverageBpm(sng)
        }

        // ── Notes from highest-difficulty arrangement ─────────────────────
        val highestArr = sng.arrangements.arrangements.maxByOrNull { it.difficulty }
        if (highestArr != null) {
            populateNotesAndChords(sng, highestArr, track)
        }

        return track
    }

    // ── Build bar grid from BPM events ────────────────────────────────────

    private fun buildBars(sng: Sng2014, track: Track) {
        val bpms = sng.bpms.bpms
        if (bpms.isEmpty()) return

        val songLength = sng.metadata.songLength

        var barStart = 0
        while (barStart < bpms.size) {
            val firstBeat = bpms[barStart]
            // find the last beat before measure changes
            var barEnd = barStart + 1
            while (barEnd < bpms.size && bpms[barEnd].measure == (-1).toShort()) barEnd++

            val startTime = firstBeat.time
            val endTime = if (barEnd < bpms.size) bpms[barEnd].time else songLength

            val bar = Bar(start = startTime, end = endTime)
            for (i in barStart until barEnd) bar.beatTimes.add(bpms[i].time)
            bar.beatTimes.add(endTime)

            val avgBpm = track.averageBeatsPerMinute.takeIf { it > 0f } ?: 120f
            bar.timeNominator = (barEnd - barStart).coerceAtLeast(1)
            bar.guessTimeAndBPM(avgBpm)

            track.bars.add(bar)
            barStart = barEnd
        }
    }

    private fun estimateAverageBpm(sng: Sng2014): Float {
        val bpms = sng.bpms.bpms
        if (bpms.size < 2) return 120f
        val totalTime = bpms.last().time - bpms.first().time
        val beatCount = bpms.size - 1
        return if (totalTime > 0) 60f * beatCount / totalTime else 120f
    }

    // ── Populate notes and chords from arrangement ─────────────────────────

    private fun populateNotesAndChords(
        sng: Sng2014,
        arr: Arrangement2014,
        track: Track
    ) {
        // Group notes by time to reconstruct chords
        val notesByTime = mutableMapOf<Float, MutableList<Notes2014>>()
        for (note in arr.notes.notes) {
            notesByTime.getOrPut(note.time) { mutableListOf() }.add(note)
        }

        for ((time, notesAtTime) in notesByTime.entries.sortedBy { it.key }) {
            val bar = track.bars.firstOrNull { it.containsTime(time) } ?: continue

            // Is this a chord or a single note?
            val firstNote = notesAtTime.first()
            val isChord = (firstNote.noteMask and NOTE_MASK_CHORD.toLong()) != 0L
                || notesAtTime.size > 1
                || firstNote.chordId != -1

            if (isChord) {
                val chord = buildChord(sng, notesAtTime, track, time, bar)
                bar.chords.add(chord)
            } else {
                val note = buildSingleNote(firstNote, track, time, bar)
                val chord = Chord(start = time).also { it.notes[firstNote.stringIndex] = note }
                setChordDuration(chord, bar)
                bar.chords.add(chord)
            }
        }
    }

    private fun buildChord(
        sng: Sng2014,
        notesAtTime: List<Notes2014>,
        track: Track,
        time: Float,
        bar: Bar
    ): Chord {
        val firstNote = notesAtTime.first()
        val chord = Chord(
            chordId   = if (firstNote.chordId != -1) firstNote.chordId else -1,
            start     = time,
            slapped   = (firstNote.noteMask and NOTE_MASK_SLAP.toLong()) != 0L,
            popped    = (firstNote.noteMask and NOTE_MASK_PLUCK.toLong()) != 0L
        )

        // If there are chordNotes with per-string data, use those
        val cnId = firstNote.chordNotesId
        if (cnId != -1 && cnId < sng.chordNotes.chordNotes.size) {
            val cn = sng.chordNotes.chordNotes[cnId]
            val template = if (firstNote.chordId >= 0 && firstNote.chordId < sng.chords.chords.size)
                sng.chords.chords[firstNote.chordId] else null
            for (s in 0..5) {
                val fret = template?.frets?.get(s)?.let { (it.toInt() and 0xFF).let { f -> if (f == 255) -1 else f } } ?: -1
                if (fret < 0 && cn.noteMask[s] == 0L) continue
                val note = Note(
                    start     = time,
                    string    = s,
                    fret      = fret.coerceAtLeast(0),
                    sustain   = firstNote.sustain,
                    palmMuted = (cn.noteMask[s] and NOTE_MASK_PALMMUTE.toLong()) != 0L,
                    muted     = (cn.noteMask[s] and NOTE_MASK_MUTE.toLong()) != 0L,
                    hopo      = (cn.noteMask[s] and (NOTE_MASK_HAMMERON or NOTE_MASK_PULLOFF).toLong()) != 0L,
                    vibrato   = cn.vibrato[s] != 0.toShort(),
                    slide     = when {
                        cn.slideTo[s].toInt() != -1 && cn.slideTo[s].toInt() != 255 -> Note.SlideType.TO_NEXT
                        cn.slideUnpitchTo[s].toInt() != -1 && cn.slideUnpitchTo[s].toInt() != 255 -> Note.SlideType.UNPITCH_DOWN
                        else -> Note.SlideType.NONE
                    },
                    slideTarget = cn.slideTo[s].toInt() and 0xFF
                )
                parseBendData(cn.bendData[s].bendData32, note, time)
                chord.notes[s] = note
            }
        } else {
            // Fallback: use individual note records
            for (n in notesAtTime) {
                chord.notes[n.stringIndex] = buildSingleNote(n, track, time, bar)
            }
        }

        setChordDuration(chord, bar)
        return chord
    }

    private fun buildSingleNote(n: Notes2014, @Suppress("UNUSED_PARAMETER") track: Track, time: Float, @Suppress("UNUSED_PARAMETER") bar: Bar): Note {
        val mask = n.noteMask
        val note = Note(
            start          = time,
            string         = n.stringIndex,
            fret           = n.fretId.toInt() and 0xFF,
            sustain        = n.sustain,
            palmMuted      = (mask and NOTE_MASK_PALMMUTE.toLong()) != 0L,
            muted          = (mask and NOTE_MASK_MUTE.toLong()) != 0L,
            hopo           = (mask and (NOTE_MASK_HAMMERON or NOTE_MASK_PULLOFF).toLong()) != 0L,
            vibrato        = (mask and NOTE_MASK_VIBRATO.toLong()) != 0L,
            linkNext       = (mask and NOTE_MASK_PARENT.toLong()) != 0L,
            accent         = (mask and NOTE_MASK_ACCENT.toLong()) != 0L,
            harmonic       = (mask and NOTE_MASK_HARMONIC.toLong()) != 0L,
            pinchHarmonic  = (mask and NOTE_MASK_PINCHHARMONIC.toLong()) != 0L,
            tremolo        = (mask and NOTE_MASK_TREMOLO.toLong()) != 0L,
            tapped         = n.tap.toInt() != 0 && n.tap.toInt() != 255,
            slapped        = n.slap.toInt() != 0 && n.slap.toInt() != 255,
            popped         = n.pluck.toInt() != 0 && n.pluck.toInt() != 255,
            leftFingering  = if (n.leftHand.toInt() == 255) -1 else n.leftHand.toInt(),
            rightFingering = -1,
            slide          = when {
                n.slideTo.toInt() != 255 && n.slideTo.toInt() != -1      -> Note.SlideType.TO_NEXT
                n.slideUnpitchTo.toInt() != 255 && n.slideUnpitchTo.toInt() != -1 -> Note.SlideType.UNPITCH_DOWN
                else -> Note.SlideType.NONE
            },
            slideTarget    = if (n.slideTo.toInt() != 255) n.slideTo.toInt() else -1
        )
        parseBendData(n.bendData.bendData, note, time)
        return note
    }

    private fun parseBendData(bendData32: Array<BendData32>, note: Note, noteTime: Float) {
        for (bd in bendData32) {
            if (bd.time <= 0f) continue
            note.bendValues.add(Note.BendValue(
                start            = bd.time,
                relativePosition = (bd.time - noteTime).coerceAtLeast(0f),
                step             = bd.step
            ))
        }
    }

    private fun setChordDuration(chord: Chord, bar: Bar) {
        val maxSustain = chord.notes.values.maxOfOrNull { it.sustain } ?: 0f
        chord.end = chord.start + maxSustain.coerceAtLeast(0.01f)
        chord.duration = bar.getDuration(chord.start, chord.end - chord.start).toInt()
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private fun ByteArray.toNullTerminatedAscii(): String {
        val end = indexOfFirst { it == 0.toByte() }.let { if (it < 0) size else it }
        return String(this, 0, end, Charsets.US_ASCII)
    }

    /** Returns the MIDI note number for [string]/[fret] given [tuningOffsets]. */
    fun getMidiNote(tuningOffsets: IntArray, isBass: Boolean, string: Int, fret: Int): Int {
        if (fret == -1) return -1
        val baseNote = STANDARD_MIDI_NOTES.getOrElse(string) { 40 } + tuningOffsets.getOrElse(string) { 0 }
        val octaveShift = if (isBass) -12 else 0
        return baseNote + octaveShift + fret
    }
}
