package com.rocksmithtab.data.psarc

import com.rocksmithtab.data.model.Score
import com.rocksmithtab.data.sng.Sng2014Reader
import java.io.InputStreamReader

class PsarcBrowser {
    fun getScore(psarcPath: String): Score {
        val reader = PsarcReader()
        reader.read(psarcPath)

        val manifestEntry = reader.entries.firstOrNull { 
            val n = it.name.lowercase()
            n.contains("manifests") && n.endsWith(".json") 
        } ?: throw Exception("No manifest found in archive.")

        val json = manifestEntry.dataSource!!.openStream().use { InputStreamReader(it).readText() }
        val manifest = Manifest2014.fromJson(json)
        val attr = manifest.attributes().firstOrNull { it.arrangementType != 4 } ?: manifest.attributes().first()

        val sngName = attr.songXmlPath.split("/").last().replace(".xml", ".sng")
        val sngEntry = reader.entries.firstOrNull { it.name.endsWith(sngName, ignoreCase = true) }
            ?: throw Exception("SNG file not found: $sngName")

        val sng = Sng2014Reader.read(sngEntry.dataSource!!.openStream())
        return SngToScore.buildTrack(sng, attr).let { track ->
            Score(attr.songName, attr.artistName).apply { tracks.add(track) }
        }
    }
}
