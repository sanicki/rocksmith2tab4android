package com.rocksmithtab.conversion

import com.rocksmithtab.data.model.Score
import com.rocksmithtab.data.psarc.PsarcBrowser
import com.rocksmithtab.export.GpxExporter
import java.io.File
import java.io.FileOutputStream

/**
 * Top-level conversion pipeline: PSARC → GPX.
 *
 * Port of RocksmithToTabLib/Converter.cs.
 *
 * Usage:
 *   Converter.convert("/sdcard/song.psarc", "/sdcard/song.gpx")
 *
 * The converter can optionally be called with a [ProgressCallback] to report
 * progress on the calling thread; it performs no threading itself.
 */
object Converter {

    fun interface ProgressCallback {
        fun onProgress(message: String, percent: Int)
    }

    data class ConversionResult(
        val outputPath: String,
        val score: Score,
        val warnings: List<String> = emptyList()
    )

    /**
     * Converts a .psarc file to a .gpx file at [outputPath].
     *
     * @param inputPath   Absolute path to the source .psarc file.
     * @param outputPath  Absolute path for the output .gpx file (will be created/overwritten).
     * @param progress    Optional callback for progress reporting.
     */
    fun convert(
        inputPath: String,
        outputPath: String,
        progress: ProgressCallback? = null
    ): ConversionResult {
        val warnings = mutableListOf<String>()

        // ── 1. Parse PSARC ────────────────────────────────────────────────
        progress?.onProgress("Reading PSARC…", 10)
        val score = PsarcBrowser().getScore(inputPath)

        if (score.tracks.isEmpty()) {
            warnings.add("No instrument tracks found in $inputPath")
        }

        // ── 2. Detect rhythm ─────────────────────────────────────────────
        progress?.onProgress("Detecting rhythm…", 50)
        for (track in score.tracks) {
            RhythmDetector.detect(track)
        }

        // ── 3. Export GPX ─────────────────────────────────────────────────
        progress?.onProgress("Exporting GPX…", 80)
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            GpxExporter().export(score, out)
        }

        progress?.onProgress("Done", 100)
        return ConversionResult(outputPath, score, warnings)
    }

    /**
     * Convenience overload: auto-generates output path next to the input file.
     */
    fun convert(
        inputPath: String,
        progress: ProgressCallback? = null
    ): ConversionResult {
        val outPath = inputPath.replaceAfterLast('.', "gpx")
        return convert(inputPath, outPath, progress)
    }
}
