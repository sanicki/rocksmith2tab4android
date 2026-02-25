package com.rocksmithtab.data.model

/**
 * Intermediate representation of a score.
 * Port of RocksmithToTabLib/Score.cs
 */
data class Score(
    var title: String = "",
    var artist: String = "",
    var artistSort: String = "",
    var album: String = "",
    var year: String = "",
    var tabber: String = "",
    var comments: List<String> = emptyList(),
    val tracks: MutableList<Track> = mutableListOf()
) {
    fun sortTracks() {
        tracks.sort()
    }
}

data class Track(
    var identifier: String = "",
    var name: String = "",
    var color: IntArray = intArrayOf(255, 0, 0),
    var difficultyLevel: Int = 0,
    var instrument: InstrumentType = InstrumentType.GUITAR,
    var numStrings: Int = 6,
    var path: PathType = PathType.LEAD,
    var bonus: Boolean = false,
    var tuning: IntArray = IntArray(6),
    var capo: Int = 0,
    val bars: MutableList<Bar> = mutableListOf(),
    val chordTemplates: MutableMap<Int, ChordTemplate> = mutableMapOf(),
    var averageBeatsPerMinute: Float = 120f
) : Comparable<Track> {

    enum class InstrumentType { GUITAR, BASS, VOCALS }
    enum class PathType { LEAD, RHYTHM, BASS }

    override fun compareTo(other: Track): Int {
        return if (path != other.path) {
            path.compareTo(other.path)
        } else if (bonus != other.bonus) {
            bonus.compareTo(other.bonus)
        } else {
            name.compareTo(other.name)
        }
    }
}

data class ChordTemplate(
    var name: String = "",
    var frets: IntArray = IntArray(6),
    var fingers: IntArray = IntArray(6),
    var chordId: Int = -1
)

data class Bar(
    var beatsPerMinute: Int = 0,
    var timeDenominator: Int = 4,
    var timeNominator: Int = 4,
    val chords: MutableList<Chord> = mutableListOf(),
    var start: Float = 0f,
    var end: Float = 0f,
    val beatTimes: MutableList<Float> = mutableListOf()
) {
    fun containsTime(time: Float) = start <= time && time < end

    /**
     * Approximates the given absolute time length as a note value
     * (multiples of 1/48 of a quarter note).
     * Port of Bar.GetDuration
     */
    fun getDuration(start: Float, length: Float): Float {
        var duration = 0f
        for (i in 0 until beatTimes.size - 1) {
            if (start >= beatTimes[i + 1]) continue
            if (start + length < beatTimes[i]) break

            val beatLength = beatTimes[i + 1] - beatTimes[i]
            val noteStart = maxOf(start, beatTimes[i])
            val noteEnd = minOf(start + length, beatTimes[i + 1])
            val beatDuration = (noteEnd - noteStart) / beatLength * 4f / timeDenominator
            duration += beatDuration
        }
        return duration * 48f
    }

    fun getDurationLength(start: Float, duration: Int): Float {
        val quarterNoteLength = (end - start) / timeNominator * timeDenominator / 4f
        return duration / 48f * quarterNoteLength
    }

    fun getBeatDuration() = if (timeDenominator == 8) 24 else 48

    fun getBarDuration() = getBeatDuration() * timeNominator

    /**
     * Guesses TimeDenominator and BeatsPerMinute from start/end/timeNominator.
     * Port of Bar.GuessTimeAndBPM
     */
    fun guessTimeAndBPM(averageBPM: Float) {
        val length = end - start
        val avgTimePerBeat = length / timeNominator
        timeDenominator = if (
            Math.abs(averageBPM - 60.0 / avgTimePerBeat) <
            Math.abs(averageBPM - 30.0 / avgTimePerBeat)
        ) 4 else 8
        beatsPerMinute = Math.round(4.0 / timeDenominator * 60.0 / avgTimePerBeat).toInt()
    }
}

data class Chord(
    var chordId: Int = -1,
    var duration: Int = 0,
    val notes: MutableMap<Int, Note> = mutableMapOf(),
    var tremolo: Boolean = false,
    var slapped: Boolean = false,
    var popped: Boolean = false,
    var section: String? = null,
    var brushDirection: BrushType = BrushType.NONE,
    var start: Float = 0f,
    var end: Float = 0f
) {
    enum class BrushType { NONE, DOWN, UP }
}

data class Note(
    var start: Float = 0f,
    var string: Int = 0,
    var fret: Int = 0,
    var palmMuted: Boolean = false,
    var muted: Boolean = false,
    var hopo: Boolean = false,
    var vibrato: Boolean = false,
    var linkNext: Boolean = false,
    var accent: Boolean = false,
    var harmonic: Boolean = false,
    var pinchHarmonic: Boolean = false,
    var tremolo: Boolean = false,
    var tapped: Boolean = false,
    var slapped: Boolean = false,
    var popped: Boolean = false,
    var leftFingering: Int = -1,
    var rightFingering: Int = -1,
    var sustain: Float = 0f,
    var slide: SlideType = SlideType.NONE,
    var slideTarget: Int = -1,
    val bendValues: MutableList<BendValue> = mutableListOf(),
    var _extended: Boolean = false   // internal: note created for sustain extension
) {
    enum class SlideType { NONE, TO_NEXT, UNPITCH_DOWN, UNPITCH_UP }

    data class BendValue(
        var start: Float = 0f,
        var relativePosition: Float = 0f,
        var step: Float = 0f
    )
}
