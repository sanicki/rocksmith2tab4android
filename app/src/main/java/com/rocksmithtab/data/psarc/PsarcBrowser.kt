package com.rocksmithtab.data.psarc

import android.util.Log
import com.rocksmithtab.data.model.*
import com.rocksmithtab.data.sng.Sng2014Reader
import java.io.RandomAccessFile

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
            val name = entry.name.lowercase()
            // FIX: Be more flexible with manifest paths for custom DLC support
            if (name.contains("manifests/") && name.endsWith(".json")) {
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
            throw IllegalStateException("No manifest data found in archive.")
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
                Log.w(TAG, "Failed to convert arrangement: ${e.message}")
            }
        }

        score.sortTracks()
        return score
    }

    private fun getTrack(psarc: PsarcReader, attr: Attributes2014): Track? {
        val sngName = deriveSngEntryName(attr) ?: return null
        val sngEntry = psarc.entries.firstOrNull {
            it.name.lowercase().endsWith("/$sngName") ||
            it.name.lowercase().endsWith("/$sngName.sng")
        } ?: return null

        val sng = Sng2014Reader.read(sngEntry.dataSource!!.openStream())
        return SngToScore.buildTrack(sng, attr)
    }

    private fun deriveSngEntryName(attr: Attributes2014): String? {
        val urn = attr.songAsset
        if (urn.isBlank()) return null
        val lastColon = urn.lastIndexOf(':')
        return if (lastColon >= 0) urn.substring(lastColon + 1) else urn
    }

    private object ArrangementType {
        const val VOCALS     = 4
        const val SHOW_LIGHTS = 5
    }
}
