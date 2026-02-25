package com.rocksmithtab.export.gpif

/**
 * Data model for the Guitar Pro Interchange Format (GPIF) XML document.
 *
 * Port of RocksmithToTabLib/Gpif.cs.
 *
 * The GPIF document structure:
 *   <GPIF>
 *     <GPVersion>...</GPVersion>
 *     <Score>...</Score>
 *     <MasterTrack>...</MasterTrack>
 *     <Tracks>...</Tracks>
 *     <MasterBars>...</MasterBars>
 *     <Bars>...</Bars>
 *     <Voices>...</Voices>
 *     <Beats>...</Beats>
 *     <Notes>...</Notes>
 *     <Rhythms>...</Rhythms>
 *   </GPIF>
 */
data class GpifDocument(
    val gpVersion: String = "6.1.1r0",
    val score: GpifScore = GpifScore(),
    val masterTrack: GpifMasterTrack = GpifMasterTrack(),
    val tracks: MutableList<GpifTrack> = mutableListOf(),
    val masterBars: MutableList<GpifMasterBar> = mutableListOf(),
    val bars: MutableList<GpifBar> = mutableListOf(),
    val voices: MutableList<GpifVoice> = mutableListOf(),
    val beats: MutableList<GpifBeat> = mutableListOf(),
    val notes: MutableList<GpifNote> = mutableListOf(),
    val rhythms: MutableList<GpifRhythm> = mutableListOf()
)

data class GpifScore(
    var title: String = "",
    var subTitle: String = "",
    var artist: String = "",
    var album: String = "",
    var words: String = "",
    var music: String = "",
    var wordsAndMusic: String = "",
    var copyright: String = "",
    var tabber: String = "",
    var instructions: String = "",
    var notices: String = ""
)

data class GpifMasterTrack(
    val automations: MutableList<GpifAutomation> = mutableListOf(),
    val repitchMap: MutableList<GpifRepitchEntry> = mutableListOf()
)

data class GpifAutomation(
    val type: String = "Tempo",
    val linear: Boolean = false,
    val bar: Int = 0,
    val position: Float = 0f,
    val visible: Boolean = true,
    val value: Float = 120f
)

data class GpifRepitchEntry(
    val bar: Int = 0,
    val value: Float = 1f
)

data class GpifTrack(
    val id: Int = 0,
    var name: String = "",
    var shortName: String = "",
    val color: GpifColor = GpifColor(),
    val instrument: String = "Guitar",
    val instrumentRef: String = "Grand Staff",
    var numStrings: Int = 6,
    val tuning: GpifTuning = GpifTuning(),
    val capo: Int = 0,
    val barIds: MutableList<Int> = mutableListOf()
)

data class GpifColor(val r: Int = 255, val g: Int = 0, val b: Int = 0)

data class GpifTuning(
    val label: String = "",
    val midiPitches: IntArray = intArrayOf(64, 59, 55, 50, 45, 40)  // standard guitar E-A-D-G-B-e (high to low)
)

data class GpifMasterBar(
    val id: Int = 0,
    var numerator: Int = 4,
    var denominator: Int = 4,
    var doublebar: Boolean = false,
    val barIds: MutableList<Int> = mutableListOf()
)

data class GpifBar(
    val id: Int = 0,
    val voiceIds: MutableList<Int> = mutableListOf()
)

data class GpifVoice(
    val id: Int = 0,
    val beatIds: MutableList<Int> = mutableListOf()
)

data class GpifBeat(
    val id: Int = 0,
    var rhythmId: Int = 0,
    var noteIds: MutableList<Int> = mutableListOf(),
    var chord: Int = -1,    // chord template index, or -1
    var section: String? = null,
    var freeText: String? = null,
    // Techniques applied at beat level
    var tremoloPicking: Boolean = false,
    var slapped: Boolean = false,
    var popped: Boolean = false,
    var brushDown: Boolean = false,
    var brushUp: Boolean = false
)

data class GpifNote(
    val id: Int = 0,
    var string: Int = 1,    // 1-indexed in GPIF (string 1 = highest)
    var fret: Int = 0,
    var leftFingering: Int = -1,
    var rightFingering: Int = -1,
    // Sustain / ties
    var tieDestination: Boolean = false,
    var tieOrigin: Boolean = false,
    // Techniques
    var accent: Boolean = false,
    var ghostNote: Boolean = false,
    var muted: Boolean = false,
    var palmMuted: Boolean = false,
    var harmonic: Boolean = false,
    var harmonicFret: Float = -1f,
    var vibrato: GpifVibrato = GpifVibrato.NONE,
    var slide: GpifSlide? = null,
    var hammer: Boolean = false,
    var legato: Boolean = false,
    var tap: Boolean = false,
    var bendValues: MutableList<GpifBendValue> = mutableListOf()
)

enum class GpifVibrato { NONE, SLIGHT, WIDE }

data class GpifSlide(
    val type: SlideType = SlideType.SHIFT,
    val targetFret: Int = -1
) {
    enum class SlideType { SHIFT, LEGATO, OUT_DOWN, OUT_UP }
}

data class GpifBendValue(
    val offset: Float = 0f,
    val value: Float = 0f
)

data class GpifRhythm(
    val id: Int = 0,
    var noteValue: GpifNoteValue = GpifNoteValue.QUARTER,
    var augmentationDot: Int = 0,
    var tuplet: GpifTuplet? = null,
    var rest: Boolean = false
)

/**
 * GP note values.
 * The numeric value is the divisor relative to a whole note.
 */
enum class GpifNoteValue(val xmlValue: String) {
    WHOLE("Whole"),
    HALF("Half"),
    QUARTER("Quarter"),
    EIGHTH("Eighth"),
    SIXTEENTH("16th"),
    THIRTY_SECOND("32nd"),
    SIXTY_FOURTH("64th");

    companion object {
        /**
         * Convert a tick duration (48 = quarter note) to a [GpifNoteValue].
         * Dotted values are not returned here — caller handles augmentation.
         */
        fun fromTicks(ticks: Int): Pair<GpifNoteValue, Int> {
            return when {
                ticks >= 192 -> Pair(WHOLE, 0)
                ticks >= 144 -> Pair(HALF, 1)    // dotted half
                ticks >= 96  -> Pair(HALF, 0)
                ticks >= 72  -> Pair(QUARTER, 1) // dotted quarter
                ticks >= 48  -> Pair(QUARTER, 0)
                ticks >= 36  -> Pair(EIGHTH, 1)  // dotted eighth
                ticks >= 24  -> Pair(EIGHTH, 0)
                ticks >= 18  -> Pair(SIXTEENTH, 1)
                ticks >= 12  -> Pair(SIXTEENTH, 0)
                ticks >= 8   -> Pair(THIRTY_SECOND, 0)
                else         -> Pair(SIXTY_FOURTH, 0)
            }
        }
    }
}

data class GpifTuplet(val enter: Int = 3, val closeAt: Int = 2)
