package com.rocksmithtab

import com.rocksmithtab.conversion.RhythmDetector
import com.rocksmithtab.data.model.Bar
import com.rocksmithtab.data.model.Chord
import com.rocksmithtab.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the core conversion logic.
 *
 * These tests run on the JVM without a device or emulator (`./gradlew test`).
 *
 * ── Adding tests ─────────────────────────────────────────────────────────────
 * Place tests for each class alongside the package it tests:
 *
 *   src/test/java/com/rocksmithtab/
 *     conversion/   ← RhythmDetectorTest, ConverterTest
 *     data/psarc/   ← PsarcReaderTest (use small hand-crafted binary fixtures)
 *     data/sng/     ← Sng2014ReaderTest
 *     export/       ← GpxExporterTest (verify XML structure of output)
 *
 * ── Fixture PSARC files ───────────────────────────────────────────────────────
 * Place any binary test fixtures in:
 *   src/test/resources/fixtures/
 * and load them with:
 *   val bytes = javaClass.classLoader!!.getResourceAsStream("fixtures/sample.psarc")
 * ─────────────────────────────────────────────────────────────────────────────
 */
class RhythmDetectorTest {

    /**
     * A bar with one chord spanning the entire 4/4 bar should produce a
     * positive bar duration from Bar.getBarDuration().
     */
    @Test
    fun barDurationIsPositive() {
        // 120 BPM, 4/4 → bar duration = 192 ticks
        val bar = Bar(
            start          = 0f,
            end            = 2.0f,   // 4 beats × 0.5 s @ 120 BPM
            beatsPerMinute = 120,
            timeNominator  = 4,
            timeDenominator = 4
        )
        assertTrue("bar duration should be positive", bar.getBarDuration() > 0)
    }

    /**
     * RhythmDetector.snapTicks() should clamp to the nearest canonical tick value
     * within the tolerance window (6 ticks).
     */
    @Test
    fun snapClampsToNearestCanonical() {
        // 50 ticks is 2 ticks away from 48 (quarter note) → should snap to 48
        val snapped = RhythmDetector.snapTicks(50)
        assertEquals(48, snapped)
    }

    @Test
    fun snapDoesNotMoveIfAlreadyCanonical() {
        assertEquals(96,  RhythmDetector.snapTicks(96))   // half note
        assertEquals(48,  RhythmDetector.snapTicks(48))   // quarter note
        assertEquals(24,  RhythmDetector.snapTicks(24))   // eighth note
        assertEquals(192, RhythmDetector.snapTicks(192))  // whole note
    }

    @Test
    fun snapHandlesValuesBeyondTolerance() {
        // 70 ticks: nearest canonical is 72 (dotted quarter, diff=2) → snap
        assertEquals(72, RhythmDetector.snapTicks(70))
        // 100 ticks: nearest canonical is 96 (half, diff=4) → snap
        assertEquals(96, RhythmDetector.snapTicks(100))
    }

    @Test
    fun detectFillsAllChordDurations() {
        val bar = Bar(
            start          = 0f,
            end            = 2.0f,
            beatsPerMinute = 120,
            timeNominator  = 4,
            timeDenominator = 4
        )
        // Add beat times so Bar.getDuration() works (not used by RhythmDetector directly,
        // but duration must already be set before detect() is called)
        bar.beatTimes.addAll(listOf(0f, 0.5f, 1.0f, 1.5f, 2.0f))

        // Pre-populate chords with raw durations (as SngToScore would produce)
        bar.chords.add(Chord(start = 0f, end = 0.5f, duration = 24))   // eighth note
        bar.chords.add(Chord(start = 0.5f, end = 1.0f, duration = 24))
        bar.chords.add(Chord(start = 1.0f, end = 2.0f, duration = 96)) // half note (2 beats)

        val track = Track().also { it.bars.add(bar) }
        RhythmDetector.detect(track)

        // All chords should have non-zero durations after detect()
        for (chord in bar.chords) {
            assertTrue("duration should be > 0, was ${chord.duration}", chord.duration > 0)
        }
        // Bar should not overflow
        val total = bar.chords.sumOf { it.duration }
        assertTrue("total ticks $total should not exceed bar duration ${bar.getBarDuration()}",
            total <= bar.getBarDuration())
    }
}
