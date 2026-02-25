package com.rocksmithtab.export

import com.rocksmithtab.data.model.*
import com.rocksmithtab.export.gpif.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Converts a [Score] into a Guitar Pro 6 .gpx file.
 *
 * Port of RocksmithToTabLib/GpxExporter.cs.
 *
 * Pipeline:
 *   Score  →  GpifDocument (data model)  →  GPIF XML (string)  →  GpxContainer (binary)
 */
class GpxExporter {

    fun export(score: Score, output: OutputStream) {
        val gpif = buildGpif(score)
        val xml  = serializeGpif(gpif)
        val container = GpxContainer()
        container.addFile("score.gpif", xml.toByteArray(Charsets.UTF_8))
        container.write(output)
    }

    // ── GPIF document construction ─────────────────────────────────────────

    private fun buildGpif(score: Score): GpifDocument {
        val doc = GpifDocument()

        doc.score.title  = score.title
        doc.score.artist = score.artist
        doc.score.album  = score.album

        // BPM tempo map from first track
        val firstTrack = score.tracks.firstOrNull()
        if (firstTrack != null) {
            for (bar in firstTrack.bars) {
                doc.masterTrack.automations.add(
                    GpifAutomation(type = "Tempo", bar = firstTrack.bars.indexOf(bar),
                                   value = bar.beatsPerMinute.toFloat())
                )
            }
        }

        // Build tracks
        var trackId = 0
        for (track in score.tracks) {
            val gpTrack = buildTrack(track, trackId++, doc)
            doc.tracks.add(gpTrack)
        }

        // Build master bar structure (shared by all tracks)
        if (firstTrack != null) {
            for ((barIdx, bar) in firstTrack.bars.withIndex()) {
                val masterBar = GpifMasterBar(
                    id          = barIdx,
                    numerator   = bar.timeNominator,
                    denominator = bar.timeDenominator
                )
                // Each track contributes one bar per master bar
                for ((tIdx, _) in score.tracks.withIndex()) {
                    val barId = tIdx * firstTrack.bars.size + barIdx
                    masterBar.barIds.add(barId)
                }
                doc.masterBars.add(masterBar)
            }
        }

        return doc
    }

    private fun buildTrack(track: Track, trackId: Int, doc: GpifDocument): GpifTrack {
        val isBass = track.instrument == Track.InstrumentType.BASS
        val midiPitches = IntArray(track.numStrings) { s ->
            val base = intArrayOf(40, 45, 50, 55, 59, 64)
            val offset = track.tuning.getOrElse(s) { 0 }
            val octaveShift = if (isBass) -12 else 0
            base.getOrElse(s) { 40 } + offset + octaveShift
        }

        val gpTrack = GpifTrack(
            id         = trackId,
            name       = track.name,
            shortName  = track.name.take(5),
            numStrings = track.numStrings,
            capo       = track.capo,
            instrument = if (isBass) "Bass" else "Guitar",
            tuning     = GpifTuning(midiPitches = midiPitches.reversedArray())
        )
        gpTrack.color.let { /* use default red */ }

        // Build bar / voice / beat / note objects for this track
        val barsStart = doc.bars.size
        for ((barIdx, bar) in track.bars.withIndex()) {
            val gpBar = GpifBar(id = barsStart + barIdx)
            val voiceId = doc.voices.size
            val voice = GpifVoice(id = voiceId)

            for (chord in bar.chords) {
                val beatId = doc.beats.size
                val beat = buildBeat(chord, track, bar, doc)
                beat.id.let { }
                doc.beats.add(beat.copy())
                voice.beatIds.add(beatId)
            }

            gpBar.voiceIds.add(voiceId)
            doc.voices.add(voice)
            doc.bars.add(gpBar)
            gpTrack.barIds.add(gpBar.id)
        }

        return gpTrack
    }

    private fun buildBeat(chord: Chord, track: Track, bar: Bar, doc: GpifDocument): GpifBeat {
        val beat = GpifBeat(
            id         = doc.beats.size,
            slapped    = chord.slapped,
            popped     = chord.popped,
            brushDown  = chord.brushDirection == Chord.BrushType.DOWN,
            brushUp    = chord.brushDirection == Chord.BrushType.UP,
            chord      = if (chord.chordId >= 0) chord.chordId else -1,
            section    = chord.section
        )

        // Rhythm
        val (noteValue, dots) = GpifNoteValue.fromTicks(chord.duration)
        val rhythm = GpifRhythm(
            id             = doc.rhythms.size,
            noteValue      = noteValue,
            augmentationDot= dots,
            rest           = chord.notes.isEmpty()
        )
        doc.rhythms.add(rhythm)
        beat.rhythmId = rhythm.id

        // Notes
        for ((stringIdx, note) in chord.notes) {
            val gpNote = buildNote(note, stringIdx, track.numStrings, doc)
            doc.notes.add(gpNote)
            beat.noteIds.add(gpNote.id)
        }

        return beat
    }

    private fun buildNote(note: Note, stringIdx: Int, numStrings: Int, doc: GpifDocument): GpifNote {
        val gpNote = GpifNote(
            id             = doc.notes.size,
            string         = numStrings - stringIdx,   // GPIF string 1 = highest pitch
            fret           = note.fret,
            leftFingering  = note.leftFingering,
            accent         = note.accent,
            muted          = note.muted,
            palmMuted      = note.palmMuted,
            harmonic       = note.harmonic || note.pinchHarmonic,
            vibrato        = if (note.vibrato) GpifVibrato.SLIGHT else GpifVibrato.NONE,
            hammer         = note.hopo,
            tap            = note.tapped,
            tieOrigin      = note.linkNext
        )

        // Slide
        gpNote.slide = when (note.slide) {
            Note.SlideType.TO_NEXT      -> GpifSlide(GpifSlide.SlideType.SHIFT, note.slideTarget)
            Note.SlideType.UNPITCH_DOWN -> GpifSlide(GpifSlide.SlideType.OUT_DOWN)
            Note.SlideType.UNPITCH_UP   -> GpifSlide(GpifSlide.SlideType.OUT_UP)
            else -> null
        }

        // Bend values
        for (bv in note.bendValues) {
            gpNote.bendValues.add(GpifBendValue(
                offset = bv.relativePosition * 100f,
                value  = bv.step * 100f
            ))
        }

        return gpNote
    }

    // ── XML serialisation ──────────────────────────────────────────────────

    private fun serializeGpif(gpif: GpifDocument): String {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.newDocument()

        val root = doc.createElement("GPIF")
        doc.appendChild(root)

        root.appendChild(doc.textElement("GPVersion", gpif.gpVersion))

        // Score
        val scoreEl = doc.createElement("Score")
        with(gpif.score) {
            scoreEl.appendChild(doc.textElement("Title", title))
            scoreEl.appendChild(doc.textElement("SubTitle", subTitle))
            scoreEl.appendChild(doc.textElement("Artist", artist))
            scoreEl.appendChild(doc.textElement("Album", album))
            scoreEl.appendChild(doc.textElement("Words", words))
            scoreEl.appendChild(doc.textElement("Music", music))
            scoreEl.appendChild(doc.textElement("WordsAndMusic", wordsAndMusic))
            scoreEl.appendChild(doc.textElement("Copyright", copyright))
            scoreEl.appendChild(doc.textElement("Tabber", tabber))
            scoreEl.appendChild(doc.textElement("Instructions", instructions))
            scoreEl.appendChild(doc.textElement("Notices", notices))
        }
        root.appendChild(scoreEl)

        // MasterTrack
        val masterTrackEl = doc.createElement("MasterTrack")
        val autoEl = doc.createElement("Automations")
        for (auto in gpif.masterTrack.automations) {
            val a = doc.createElement("Automation")
            a.appendChild(doc.textElement("Type", auto.type))
            a.appendChild(doc.textElement("Linear", if (auto.linear) "1" else "0"))
            a.appendChild(doc.textElement("Bar", auto.bar.toString()))
            a.appendChild(doc.textElement("Position", auto.position.toString()))
            a.appendChild(doc.textElement("Visible", if (auto.visible) "1" else "0"))
            a.appendChild(doc.textElement("Value", auto.value.toString()))
            autoEl.appendChild(a)
        }
        masterTrackEl.appendChild(autoEl)
        root.appendChild(masterTrackEl)

        // Tracks
        val tracksEl = doc.createElement("Tracks")
        for (t in gpif.tracks) {
            val tel = doc.createElement("Track")
            tel.setAttribute("id", t.id.toString())
            tel.appendChild(doc.textElement("Name", t.name))
            tel.appendChild(doc.textElement("ShortName", t.shortName))
            val colorEl = doc.createElement("Color")
            colorEl.appendChild(doc.textElement("Red", t.color.r.toString()))
            colorEl.appendChild(doc.textElement("Green", t.color.g.toString()))
            colorEl.appendChild(doc.textElement("Blue", t.color.b.toString()))
            tel.appendChild(colorEl)
            tel.appendChild(doc.textElement("InstrumentRef", t.instrumentRef))
            // Tuning
            val tuningEl = doc.createElement("Tuning")
            tuningEl.setAttribute("midi", t.tuning.midiPitches.joinToString(" "))
            tel.appendChild(tuningEl)
            tel.appendChild(doc.textElement("Capo", t.capo.toString()))
            tracksEl.appendChild(tel)
        }
        root.appendChild(tracksEl)

        // MasterBars
        val masterBarsEl = doc.createElement("MasterBars")
        for (mb in gpif.masterBars) {
            val mbEl = doc.createElement("MasterBar")
            val tsEl = doc.createElement("Time")
            tsEl.textContent = "${mb.numerator}/${mb.denominator}"
            mbEl.appendChild(tsEl)
            val barsEl = doc.createElement("Bars")
            barsEl.textContent = mb.barIds.joinToString(" ")
            mbEl.appendChild(barsEl)
            masterBarsEl.appendChild(mbEl)
        }
        root.appendChild(masterBarsEl)

        // Bars
        val barsContainerEl = doc.createElement("Bars")
        for (b in gpif.bars) {
            val bEl = doc.createElement("Bar")
            bEl.setAttribute("id", b.id.toString())
            val voicesEl = doc.createElement("Voices")
            voicesEl.textContent = b.voiceIds.joinToString(" ")
            bEl.appendChild(voicesEl)
            barsContainerEl.appendChild(bEl)
        }
        root.appendChild(barsContainerEl)

        // Voices
        val voicesContainerEl = doc.createElement("Voices")
        for (v in gpif.voices) {
            val vEl = doc.createElement("Voice")
            vEl.setAttribute("id", v.id.toString())
            val beatsEl = doc.createElement("Beats")
            beatsEl.textContent = v.beatIds.joinToString(" ")
            vEl.appendChild(beatsEl)
            voicesContainerEl.appendChild(vEl)
        }
        root.appendChild(voicesContainerEl)

        // Beats
        val beatsContainerEl = doc.createElement("Beats")
        for (b in gpif.beats) {
            val bEl = doc.createElement("Beat")
            bEl.setAttribute("id", b.id.toString())
            bEl.appendChild(doc.textElement("Rhythm", b.rhythmId.toString()))
            if (b.noteIds.isNotEmpty()) {
                bEl.appendChild(doc.textElement("Notes", b.noteIds.joinToString(" ")))
            }
            if (b.chord >= 0) bEl.appendChild(doc.textElement("Chord", b.chord.toString()))
            beatsContainerEl.appendChild(bEl)
        }
        root.appendChild(beatsContainerEl)

        // Notes
        val notesContainerEl = doc.createElement("Notes")
        for (n in gpif.notes) {
            val nEl = doc.createElement("Note")
            nEl.setAttribute("id", n.id.toString())
            val propsEl = doc.createElement("Properties")
            propsEl.appendChild(doc.gpifProperty("String", "Number", n.string.toString()))
            propsEl.appendChild(doc.gpifProperty("Fret",   "Number", n.fret.toString()))
            if (n.muted)    propsEl.appendChild(doc.gpifProperty("Muted", "Enable", ""))
            if (n.palmMuted) propsEl.appendChild(doc.gpifProperty("PalmMuted", "Enable", ""))
            if (n.harmonic) propsEl.appendChild(doc.gpifProperty("Harmonic", "HType", "Natural"))
            n.slide?.let { slide ->
                val sType = when (slide.type) {
                    GpifSlide.SlideType.SHIFT   -> "ShiftSlide"
                    GpifSlide.SlideType.LEGATO  -> "LegatoSlide"
                    GpifSlide.SlideType.OUT_DOWN -> "SlideOutDown"
                    GpifSlide.SlideType.OUT_UP  -> "SlideOutUp"
                }
                propsEl.appendChild(doc.gpifProperty(sType, "Enable", ""))
            }
            if (n.bendValues.isNotEmpty()) {
                val bendProp = doc.createElement("Property")
                bendProp.setAttribute("name", "Bended")
                val bendEl = doc.createElement("Bended")
                for (bv in n.bendValues) {
                    val ptEl = doc.createElement("Point")
                    ptEl.setAttribute("time", bv.offset.toInt().toString())
                    ptEl.setAttribute("value", bv.value.toInt().toString())
                    bendEl.appendChild(ptEl)
                }
                bendProp.appendChild(bendEl)
                propsEl.appendChild(bendProp)
            }
            nEl.appendChild(propsEl)
            if (n.accent)  nEl.appendChild(doc.textElement("Accent", "8"))
            if (n.hammer)  nEl.appendChild(doc.textElement("HammerOn", "HammerOn"))
            if (n.tap)     nEl.appendChild(doc.textElement("Tapping", "Tap"))
            if (n.vibrato != GpifVibrato.NONE) nEl.appendChild(doc.textElement("Vibrato", n.vibrato.name))
            notesContainerEl.appendChild(nEl)
        }
        root.appendChild(notesContainerEl)

        // Rhythms
        val rhythmsContainerEl = doc.createElement("Rhythms")
        for (r in gpif.rhythms) {
            val rEl = doc.createElement("Rhythm")
            rEl.setAttribute("id", r.id.toString())
            rEl.appendChild(doc.textElement("NoteValue", r.noteValue.xmlValue))
            if (r.augmentationDot > 0) {
                rEl.appendChild(doc.textElement("AugmentationDot", r.augmentationDot.toString()))
            }
            rhythmsContainerEl.appendChild(rEl)
        }
        root.appendChild(rhythmsContainerEl)

        // Serialise DOM → string
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        val bos = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(bos))
        return bos.toString("UTF-8")
    }

    // ── DOM helpers ────────────────────────────────────────────────────────

    private fun Document.textElement(tag: String, text: String): Element {
        val el = createElement(tag)
        el.textContent = text
        return el
    }

    private fun Document.gpifProperty(name: String, childTag: String, value: String): Element {
        val prop = createElement("Property")
        prop.setAttribute("name", name)
        if (childTag.isNotEmpty()) {
            val child = createElement(childTag)
            child.textContent = value
            prop.appendChild(child)
        }
        return prop
    }

    private fun GpifBeat.copy() = GpifBeat(id, rhythmId, noteIds, chord, section, freeText,
                                            tremoloPicking, slapped, popped, brushDown, brushUp)
}
