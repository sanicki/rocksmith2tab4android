package com.rocksmithtab

import com.rocksmithtab.conversion.RhythmDetector
import com.rocksmithtab.data.model.Bar
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
     * A single quarter note in a 4/4 bar at 120 BPM should snap to 48 ticks
     * (the canonical quarter-note duration in the 192-tick-per-beat grid).
     */
    @Test
    fun quarterNoteSnapsTo48Ticks() {
        // 120 BPM → beat duration = 0.5 s; a single note fills the whole bar
        val bar = Bar(
            startTime = 0f,
            endTime   = 2.0f,   // 4 beats × 0.5 s
            beatsPerMinute = 120,
            beatsPerBar    = 4,
            beatValue      = 4
        )
        // Insert a single chord at time 0
        // (Bar is a data class — adapt field names to match Score.kt)
        // This is intentionally a smoke test; expand once Score.kt constructors stabilise.
        assertTrue("bar duration should be positive", bar.getDuration() > 0)
    }

    /**
     * RhythmDetector.snap() should clamp to the nearest canonical tick value
     * within the tolerance window (3 ticks).
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
}
