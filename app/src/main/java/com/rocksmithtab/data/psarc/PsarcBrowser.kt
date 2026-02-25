package com.rocksmithtab.data.psarc

import com.rocksmithtab.data.model.*
import com.rocksmithtab.data.sng.Sng2014Reader
import com.rocksmithtab.utils.AppLogger
import java.io.RandomAccessFile

/**
 * High-level PSARC orchestrator.
 */
class PsarcBrowser {

    companion object {
        private const val TAG = "PsarcBrowser"
    }

    fun getScore(filePath: String): Score {
        val raf = RandomAccessFile(filePath, "r")
        val psarc = PsarcReader()
        psarc.read(raf)

        val allAttributes = mutableListOf<Attributes2014>()
        for (entry in psarc.entries) {
            val name = entry.name.replace('\\', '/').lowercase().trim()
            val isManifest = name.contains("manifests/") && name.endsWith(".json")
            AppLogger.d(TAG, "Entry: '${entry.name}' -> Normalized: '$name' isManifest=$isManifest")
            
            if (isManifest) {
                try {
                    val json = entry.dataSource!!.openStream().readBytes().toString(Charsets.UTF_8)
                    val manifest = Manifest2014.fromJson(json)
                    val attrs = manifest.attributes()
                    AppLogger.d(TAG, "  → parsed ${attrs.size} attribute(s) from ${entry.name}")
                    allAttributes.addAll(attrs)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to parse manifest ${entry.name}: ${e.message}")
                }
            }
        }

        if (allAttributes.isEmpty()) {
            val entryNames = psarc.entries.take(20).joinToString("\n  ") { "'${it.name}'" }
            AppLogger.e(TAG, "No manifest data found. Entry names seen:\n  $entryNames")
            throw IllegalStateException("No manifest data found in $filePath")
        }

        val primaryAttr = allAttributes.firstOrNull { it.arrangementType != ArrangementType.VOCALS }
            ?: allAttributes.first()
        val score = Score(
            title       = primaryAttr.songName,
            artist      = primaryAttr.artistName,
            artistSort  = primaryAttr.artistNameSort,
            album       = primaryAttr.albumName,
            year        = if (primaryAttr.songYear > 0) primaryAttr.songYear.toString() else ""
        )

        for (attr in allAttributes) {
            if (attr.arrangementType == ArrangementType.VOCALS) continue
            if (attr.arrangementType == ArrangementType.SHOW_LIGHTS) continue

            try {
                val track = getTrack(psarc, attr)
                if (track != null) score.tracks.add(track)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to convert arrangement ${attr.arrangementName}: ${e.message}")
            }
        }

        score.sortTracks()
        return score
    }

    private fun getTrack(psarc: PsarcReader, attr: Attributes2014): Track? {
        val sngName = deriveSngEntryName(attr)?.lowercase()
        if (sngName == null) {
            AppLogger.w(TAG, "deriveSngEntryName returned null for ${attr.arrangementName}")
            return null
        }
        AppLogger.d(TAG, "Looking for SNG entry matching: $sngName")

        val sngEntry = psarc.entries.firstOrNull {
            val normName = it.name.replace('\\', '/').lowercase()
            normName.endsWith("/$sngName") || normName.endsWith("/$sngName.sng")
        }

        if (sngEntry == null) {
            AppLogger.w(TAG, "SNG entry NOT FOUND for arrangement: ${attr.arrangementName}")
            return null
        }

        AppLogger.d(TAG, "Found SNG entry: ${sngEntry.name}, length: ${sngEntry.length}. Reading SNG...")
        return try {
            val sng = Sng2014Reader.read(sngEntry.dataSource!!.openStream())
            AppLogger.d(TAG, "Successfully parsed SNG data for ${attr.arrangementName}. Building track...")
            SngToScore.buildTrack(sng, attr)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception reading SNG for ${attr.arrangementName}: ${e.javaClass.simpleName} - ${e.message}", e)
            null
        }
    }

    private fun deriveSngEntryName(attr: Attributes2014): String? {
        // Fallback to SongXml if SongAsset is omitted (very common in PC custom DLC)
        val urn = attr.songAsset.ifBlank { attr.songXml }
        if (urn.isBlank()) return null
        
        val lastColon = urn.lastIndexOf(':')
        val baseName = if (lastColon >= 0) urn.substring(lastColon + 1) else urn
        return baseName.removeSuffix(".xml")
    }

    private object ArrangementType {
        const val VOCALS     = 4
        const val SHOW_LIGHTS = 5
    }
}
