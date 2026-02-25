package com.rocksmithtab.data.psarc

import org.json.JSONObject

/**
 * Typed model for the RS2014 JSON manifest files found inside .psarc archives.
 *
 * Ports the read side of:
 *   - RocksmithToolkitLib/DLCPackage/Manifest2014/Manifest2014.cs
 *   - RocksmithToolkitLib/DLCPackage/Manifest2014/Attributes2014.cs
 *     (only the fields consumed by PSARCBrowser / Converter)
 *
 * Newtonsoft.Json → org.json (no additional dependency needed on Android).
 */
data class Manifest2014(
    val entries: Map<String, Map<String, Attributes2014>>,
    val modelName: String = "",
    val iterationVersion: Int = 2,
    val insertRoot: String = ""
) {
    companion object {
        fun fromJson(json: String): Manifest2014 {
            val root = JSONObject(json)

            val modelName        = root.optString("ModelName", "")
            val iterationVersion = root.optInt("IterationVersion", 2)
            val insertRoot       = root.optString("InsertRoot", "")

            val outerMap = mutableMapOf<String, Map<String, Attributes2014>>()
            val entriesObj = root.optJSONObject("Entries") ?: return Manifest2014(
                outerMap, modelName, iterationVersion, insertRoot
            )

            val outerKeys = entriesObj.keys()
            while (outerKeys.hasNext()) {
                val outerKey = outerKeys.next()
                val innerObj = entriesObj.optJSONObject(outerKey) ?: continue
                val innerMap = mutableMapOf<String, Attributes2014>()
                val innerKeys = innerObj.keys()
                while (innerKeys.hasNext()) {
                    val innerKey = innerKeys.next()
                    val attrObj = innerObj.optJSONObject(innerKey) ?: continue
                    innerMap[innerKey] = Attributes2014.fromJson(attrObj)
                }
                outerMap[outerKey] = innerMap
            }

            return Manifest2014(outerMap, modelName, iterationVersion, insertRoot)
        }
    }

    /** Flatten entries into a single list of [Attributes2014]. */
    fun attributes(): List<Attributes2014> =
        entries.values.flatMap { it.values }
}

/**
 * Flat attribute bag for a single arrangement, populated from the manifest JSON.
 * Only includes fields used by the conversion pipeline.
 */
data class Attributes2014(
    // ── Song identity ─────────────────────────────────────────────────────
    val songName: String = "",
    val songNameSort: String = "",
    val artistName: String = "",
    val artistNameSort: String = "",
    val albumName: String = "",
    val albumNameSort: String = "",
    val songYear: Int = 0,
    val songLength: Double = 0.0,
    val songAverageTempo: Float = 0f,
    val songOffset: Double = 0.0,
    val centOffset: Double = 0.0,

    // ── Arrangement identity ──────────────────────────────────────────────
    val arrangementName: String = "",
    val arrangementType: Int = 0,
    val arrangementSort: Int = 0,
    val songPartition: Int = 0,
    val fullName: String = "",

    // ── Tuning ────────────────────────────────────────────────────────────
    val capoFret: Int = 0,
    val tuning: TuningStrings = TuningStrings(),

    // ── Asset paths ───────────────────────────────────────────────────────
    val songXml: String = "",         // URN pointing at the arrangement XML asset
    val songAsset: String = "",       // URN pointing at the .sng asset
    val albumArt: String = "",

    // ── Tone ──────────────────────────────────────────────────────────────
    val tone_Base: String = "",
    val tone_A: String = "",
    val tone_B: String = "",
    val tone_C: String = "",
    val tone_D: String = "",

    // ── Misc ──────────────────────────────────────────────────────────────
    val lastConversionDateTime: String = "",
    val persistentId: String = ""
) {
    companion object {
        fun fromJson(obj: JSONObject): Attributes2014 {
            val tuningObj = obj.optJSONObject("Tuning")
            val tuning = if (tuningObj != null) TuningStrings.fromJson(tuningObj) else TuningStrings()

            return Attributes2014(
                songName            = obj.optString("SongName"),
                songNameSort        = obj.optString("SongNameSort"),
                artistName          = obj.optString("ArtistName"),
                artistNameSort      = obj.optString("ArtistNameSort"),
                albumName           = obj.optString("AlbumName"),
                albumNameSort       = obj.optString("AlbumNameSort"),
                songYear            = obj.optInt("SongYear"),
                songLength          = obj.optDouble("SongLength"),
                songAverageTempo    = obj.optDouble("SongAverageTempo").toFloat(),
                songOffset          = obj.optDouble("SongOffset"),
                centOffset          = obj.optDouble("CentOffset"),
                arrangementName     = obj.optString("ArrangementName"),
                arrangementType     = obj.optInt("ArrangementType"),
                arrangementSort     = obj.optInt("ArrangementSort"),
                songPartition       = obj.optInt("SongPartition"),
                fullName            = obj.optString("FullName"),
                capoFret            = obj.optInt("CapoFret"),
                tuning              = tuning,
                songXml             = obj.optString("SongXml"),
                songAsset           = obj.optString("SongAsset"),
                albumArt            = obj.optString("AlbumArt"),
                tone_Base           = obj.optString("Tone_Base"),
                tone_A              = obj.optString("Tone_A"),
                tone_B              = obj.optString("Tone_B"),
                tone_C              = obj.optString("Tone_C"),
                tone_D              = obj.optString("Tone_D"),
                lastConversionDateTime = obj.optString("LastConversionDateTime"),
                persistentId        = obj.optString("PersistentID")
            )
        }
    }
}

data class TuningStrings(
    val string0: Int = 0,
    val string1: Int = 0,
    val string2: Int = 0,
    val string3: Int = 0,
    val string4: Int = 0,
    val string5: Int = 0
) {
    fun toArray(): IntArray = intArrayOf(string0, string1, string2, string3, string4, string5)

    companion object {
        fun fromJson(obj: JSONObject) = TuningStrings(
            string0 = obj.optInt("String0"),
            string1 = obj.optInt("String1"),
            string2 = obj.optInt("String2"),
            string3 = obj.optInt("String3"),
            string4 = obj.optInt("String4"),
            string5 = obj.optInt("String5")
        )
    }
}
