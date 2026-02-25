package com.rocksmithtab.data.psarc

import android.util.Log
import com.rocksmithtab.data.model.*
import com.rocksmithtab.data.sng.Sng2014Reader
import java.io.RandomAccessFile

/**
 * High-level PSARC orchestrator.
 *
 * Port of RocksmithToTabLib/PSARCBrowser.cs.
 *
 * Responsibilities:
 *  1. Open and parse the PSARC file
 *  2. Locate and parse JSON manifest(s) → [Attributes2014] per arrangement
 *  3. For each arrangement, decrypt/decompress the .sng → [Sng2014]
 *  4. Delegate to [SngToScore] to turn Sng2014 + Attributes2014 → [Score]
 *
 * Usage:
 *   val browser = PsarcBrowser()
 *   val score = browser.getScore("/sdcard/song.psarc")
 */
class PsarcBrowser {

    companion object {
        private const val TAG = "PsarcBrowser"
        private const val MANIFEST_DIR = "manifests/songs_dlc"
        private const val SNG_DIR = "songs/bin/generic"
    }

    /**
     * Opens [filePath], parses all arrangements, and returns a fully-populated [Score].
     * Throws on unrecoverable errors; partial data is tolerated where possible.
     */
    fun getScore(filePath: String): Score {
        val raf = RandomAccessFile(filePath, "r")
        val psarc = PsarcReader()
        psarc.read(raf)

        // ── 1. Find and parse manifests ──────────────────────────────────
        val allAttributes = mutableListOf<Attributes2014>()
        for (entry in psarc.entries) {
            val name = entry.name.lowercase()
            if (name.startsWith(MANIFEST_DIR) && name.endsWith(".json")) {
                try {
                    val json = entry.dataSource!!.openStream().readBytes().toString(Charsets.UTF_8)
                    val manifest = Manifest2014.fromJson(json)
                    allAttributes.addAll(manifest.attributes())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse manifest ${entry.name}: ${e.message}")
                }
            }
        }

        if (allAttributes.isEmpty()) {
            throw IllegalStateException("No manifest data found in $filePath")
        }

        // ── 2. Build Score metadata from first non-vocal arrangement ──────
        val primaryAttr = allAttributes.firstOrNull { it.arrangementType != ArrangementType.VOCALS }
            ?: allAttributes.first()
        val score = Score(
            title       = primaryAttr.songName,
            artist      = primaryAttr.artistName,
            artistSort  = primaryAttr.artistNameSort,
            album       = primaryAttr.albumName,
            year        = if (primaryAttr.songYear > 0) primaryAttr.songYear.toString() else ""
        )

        // ── 3. Convert each instrument arrangement ───────────────────────
        for (attr in allAttributes) {
            if (attr.arrangementType == ArrangementType.VOCALS) continue
            if (attr.arrangementType == ArrangementType.SHOW_LIGHTS) continue

            try {
                val track = getTrack(psarc, attr)
                if (track != null) score.tracks.add(track)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to convert arrangement ${attr.arrangementName}: ${e.message}")
            }
        }

        score.sortTracks()
        return score
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun getTrack(psarc: PsarcReader, attr: Attributes2014): Track? {
        // Derive the SNG entry name from the SongAsset URN
        // URN format: urn:application:musicgamesong:<name>_<arrangement>
        val sngName = deriveSngEntryName(attr) ?: return null

        val sngEntry = psarc.entries.firstOrNull {
            it.name.lowercase().endsWith("/$sngName") ||
            it.name.lowercase().endsWith("/$sngName.sng")
        }

        if (sngEntry == null) {
            Log.w(TAG, "SNG entry not found for arrangement: ${attr.arrangementName} (looked for $sngName)")
            return null
        }

        val sng = Sng2014Reader.read(sngEntry.dataSource!!.openStream())
        return SngToScore.buildTrack(sng, attr)
    }

    private fun deriveSngEntryName(attr: Attributes2014): String? {
        // SongAsset URN: "urn:application:musicgamesong:<songname>_<arrname>"
        // Entry path:    "songs/bin/generic/<songname>_<arrname>.sng"
        val urn = attr.songAsset
        if (urn.isBlank()) return null
        val lastColon = urn.lastIndexOf(':')
        return if (lastColon >= 0) urn.substring(lastColon + 1) else urn
    }

    // ── ArrangementType constants (mirrors C# ArrangementName enum) ───────
    private object ArrangementType {
        const val VOCALS     = 4
        const val SHOW_LIGHTS = 5
    }
}
