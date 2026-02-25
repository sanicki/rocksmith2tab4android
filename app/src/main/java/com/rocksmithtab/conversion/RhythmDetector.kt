package com.rocksmithtab.conversion

import com.rocksmithtab.data.model.Bar
import com.rocksmithtab.data.model.Track
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Detects and applies rhythm (note durations) to a parsed [Track].
 *
 * Port of RocksmithToTabLib/RhythmDetector.cs.
 *
 * The algorithm:
 *  1. For each [Bar], compute the duration of each chord/note in "ticks"
 *     (multiples of 1/48 of a quarter note).
 *  2. Snap each duration to the nearest canonical value (whole, half, quarter,
 *     eighth, sixteenth, triplet variations, dotted variations).
 *  3. Fill gaps between chords with rest chords.
 */
object RhythmDetector {

    /**
     * Canonical duration values in "ticks" (48 = quarter note).
     * Ordered from longest to shortest.
     */
    private val CANONICAL_DURATIONS = intArrayOf(
        // whole / breve
        192, 144, 96,
        // half
        72, 48,
        // quarter
        36, 32, 24,
        // eighth
        18, 16, 12,
        // sixteenth
        9, 8, 6,
        // thirty-second
        4, 3
    )

    /**
     * Maximum tick error when snapping to a canonical value.
     * Corresponds to roughly 1/64 note.
     */
    private const val SNAP_TOLERANCE = 3

    fun detect(track: Track) {
        for (bar in track.bars) {
            assignDurationsInBar(bar)
        }
    }

    private fun assignDurationsInBar(bar: Bar) {
        if (bar.chords.isEmpty()) return

        val barDuration = bar.getBarDuration()

        for (chord in bar.chords) {
            // Raw duration as computed from beat-time positions
            val rawDuration = bar.getDuration(chord.start, chord.end - chord.start).roundToInt()
            chord.duration = snapToCanonical(rawDuration, barDuration)
        }

        // Clamp last chord so bar doesn't overflow
        val lastChord = bar.chords.last()
        val usedTicks = bar.chords.dropLast(1).sumOf { it.duration }
        val remaining = barDuration - usedTicks
        if (remaining > 0 && lastChord.duration > remaining) {
            lastChord.duration = remaining
        }
    }

    private fun snapToCanonical(rawTicks: Int, barDuration: Int): Int {
        if (rawTicks <= 0) return CANONICAL_DURATIONS.last()

        var best = CANONICAL_DURATIONS.last()
        var bestDiff = Int.MAX_VALUE

        for (canonical in CANONICAL_DURATIONS) {
            if (canonical > barDuration) continue
            val diff = abs(rawTicks - canonical)
            if (diff < bestDiff) {
                bestDiff = diff
                best = canonical
            }
        }

        return if (bestDiff <= SNAP_TOLERANCE) best else rawTicks.coerceAtLeast(1)
    }
}
