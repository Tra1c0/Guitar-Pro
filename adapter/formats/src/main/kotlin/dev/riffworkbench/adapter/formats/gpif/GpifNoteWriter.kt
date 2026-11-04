package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlWriter
import dev.riffworkbench.model.*

/** One model note -> one GPIF <Note>. Beat-level techniques (tremolo, pickstroke, slap/pop, whammy) are written by the beat. */
internal class NoteWriter(private val et: ExportTrack, private val relations: ExportRelations, private val losses: LossCollector, private val beats: BeatWriter) {
    private fun loss(feature: String, reason: String, id: EntityId) = losses.add(feature, reason, id)

    fun write(note: Note, beat: Beat, register: ((XmlWriter, Int) -> Unit) -> Int): Int? {
        val a = note.articulations
        val marks = a.filterIsInstance<Articulation.Mark>().map { it.kind }.toSet()
        val midi = note.pitch.midiNumber
        var string = note.string
        var fret = note.fret
        var drum: Int? = null
        var ghost = NoteMark.GHOST in marks
        when {
            et.percussion -> {
                val perc = a.filterIsInstance<Articulation.Percussion>().firstOrNull()
                var effective = midi
                var technique: String? = null
                when (perc?.kind) {
                    PercussionTechnique.OPEN_HI_HAT -> effective = 46
                    PercussionTechnique.CLOSED_HI_HAT -> effective = 42
                    PercussionTechnique.PEDAL_HI_HAT -> effective = 44
                    PercussionTechnique.GHOST -> ghost = true
                    PercussionTechnique.RIMSHOT, PercussionTechnique.CROSS_STICK, PercussionTechnique.CHOKE -> technique = GpDrums.techniqueLabel(perc.kind)
                    PercussionTechnique.NORMAL, null -> Unit
                }
                if (effective != midi) loss("drums.hihat", "Hi-Hat-Technik wird als eigene MIDI-Note exportiert", note.id)
                if (perc?.chokeGroup != null) loss("drums.choke", "Choke-Gruppe einer Schlagzeugnote wird nicht exportiert", note.id)
                drum = et.drumIndex(effective, technique)
                string = null; fret = null
            }
            et.stringed && (string == null || fret == null) -> {
                val position = et.tuning.indices.map { it to et.fretFor(it, midi) }.filter { it.second in 0..et.instrument.maxFret }.minByOrNull { it.second }
                if (position == null) { loss("notes.fret", "Note ohne spielbare Saitenposition wird nicht exportiert", note.id); return null }
                loss("notes.fret", "Note ohne Saite erhält beim Export eine berechnete Saitenposition", note.id)
                string = position.first; fret = position.second
            }
            !et.stringed -> { string = null; fret = null }
        }
        if (note.pitch.cents != 0) loss("fx.quartertone", "Cent-Abweichungen einzelner Noten werden nicht exportiert", note.id)
        if (note.pitch.spelling != null) loss("notation.standard", "Enharmonische Schreibweise wird nicht exportiert (Guitar Pro leitet sie neu ab)", note.id)

        var slideFlags = 0
        a.filterIsInstance<Articulation.Slide>().forEach { s ->
            slideFlags = slideFlags or when (s.kind) {
                SlideKind.SHIFT -> NoteReader.SLIDE_SHIFT; SlideKind.LEGATO -> NoteReader.SLIDE_LEGATO
                SlideKind.OUT_DOWN -> NoteReader.SLIDE_OUT_DOWN; SlideKind.OUT_UP -> NoteReader.SLIDE_OUT_UP
                SlideKind.INTO_FROM_BELOW -> NoteReader.SLIDE_IN_BELOW; SlideKind.INTO_FROM_ABOVE -> NoteReader.SLIDE_IN_ABOVE
            }
        }
        if (note.id in relations.legatoSlide) slideFlags = slideFlags or NoteReader.SLIDE_LEGATO
        if (NoteMark.PICK_SCRAPE in marks) slideFlags = slideFlags or NoteReader.SLIDE_PICK_DOWN
        var accent = 0
        if (NoteMark.STACCATO in marks) accent = accent or NoteReader.ACCENT_STACCATO
        if (NoteMark.HEAVY_ACCENT in marks) accent = accent or NoteReader.ACCENT_HEAVY
        if (NoteMark.ACCENT in marks) accent = accent or NoteReader.ACCENT_NORMAL

        val bends = a.filterIsInstance<Articulation.Bend>()
        if (bends.size > 1) loss("fx.bend", "Nur ein Bend pro Note ist in GPIF darstellbar", note.id)
        val bend = bends.firstOrNull()?.let { b ->
            if (b.points.isNotEmpty() && NoteReader.bendKind(b.points.sortedBy { it.position }) != b.kind) loss("fx.bend", "Bend-Typ ergibt sich in GPIF aus der Kurve", note.id)
            beats.curve(b.points, "Bend", note.id)
        }
        val harmonics = a.filterIsInstance<Articulation.Harmonic>()
        if (harmonics.size > 1) loss("fx.harmonic_natural", "Nur ein Flageolett pro Note ist darstellbar", note.id)
        val harmonic = harmonics.firstOrNull()?.let { h ->
            if (h.soundingPitch != null) loss("fx.harmonic_natural", "Explizite Flageolett-Tonhöhe wird nicht exportiert (aus Position abgeleitet)", note.id)
            val type = when (h.kind) {
                HarmonicKind.NATURAL -> "Natural"; HarmonicKind.ARTIFICIAL -> "Artificial"; HarmonicKind.PINCH -> "Pinch"
                HarmonicKind.TAPPED -> "Tap"; HarmonicKind.SEMI -> "Semi"; HarmonicKind.FEEDBACK -> "Feedback"
            }
            val position = h.node?.takeIf { it > Fraction.ZERO && it < Fraction.ONE }?.let { GpifUnits.fretForNode(it) }
                ?: if (h.kind == HarmonicKind.NATURAL && (fret ?: 0) > 0) fret!!.toDouble() else 12.0
            type to GpifUnits.harmonicFretText(position)
        }
        val vibrato = a.filterIsInstance<Articulation.Vibrato>().firstOrNull()?.let { v ->
            val wide = v.depthCents > 45
            val canonical = if (wide) Articulation.Vibrato(depthCents = 60) else Articulation.Vibrato()
            if (v != canonical) loss("fx.vibrato", "Vibrato-Tiefe und -Rate werden auf leicht/weit gerundet", note.id)
            if (wide) "Wide" else "Slight"
        }
        val trill = a.filterIsInstance<Articulation.Trill>().firstOrNull()?.let { t ->
            if (t.rate != WrittenDuration(16)) loss("fx.trill", "Triller-Geschwindigkeit wird in GPIF nicht gespeichert (Sechzehntel)", note.id)
            val s = string
            if (s == null) { loss("fx.trill", "Triller ohne Saite wird nicht exportiert", note.id); null }
            else (et.tuning[s] + et.instrument.capoByString[s] + t.targetFret).takeIf { it in 0..127 }
        }
        val left = note.fingering?.leftHand?.let { listOf("P", "I", "M", "A", "C").getOrNull(it) ?: run { loss("notes.fingering", "Fingersatz wird nicht exportiert", note.id); null } }
        val right = note.fingering?.rightHand?.uppercase()?.takeIf { it in setOf("P", "I", "M", "A", "C") }
        if (note.fingering?.rightHand != null && right == null || note.fingering?.alternative != null) loss("notes.fingering", "Fingersatz-Variante wird nicht exportiert", note.id)
        if (a.count { it is Articulation.Vibrato } > 1 || a.count { it is Articulation.Trill } > 1) loss("fx.vibrato", "Mehrfache Vibrato/Triller-Angaben werden zusammengefasst", note.id)

        val tieOrigin = note.id in relations.tieOrigin
        val tieDestination = note.id in relations.tieDestination
        val hopoOrigin = note.id in relations.hopoOrigin
        val hopoDestination = note.id in relations.hopoDestination
        val finalString = string
        val finalFret = fret
        return register { w, id ->
            w.start("Note", "id" to id)
            if (tieOrigin || tieDestination) w.start("Tie", "origin" to tieOrigin, "destination" to tieDestination).end()
            if (NoteMark.LET_RING in marks) w.start("LetRing").end()
            if (vibrato != null) w.leaf("Vibrato", vibrato)
            if (ghost) w.leaf("AntiAccent", "Normal")
            if (accent != 0) w.leaf("Accent", accent)
            if (trill != null) w.leaf("Trill", trill)
            if (left != null) w.leaf("LeftFingering", left)
            if (right != null) w.leaf("RightFingering", right)
            if (drum != null) w.leaf("InstrumentArticulation", drum)
            w.start("Properties")
            if (finalString != null && finalFret != null) w.prop("String", "String", finalString).prop("Fret", "Fret", finalFret)
            if (drum == null) {
                w.prop("Midi", "Number", midi)
                if (!et.stringed) w.prop("Octave", "Number", midi / 12).prop("Tone", "Step", midi % 12)
            }
            if (NoteMark.PALM_MUTE in marks) w.flag("PalmMuted")
            if (NoteMark.DEAD in marks) w.flag("Muted")
            if (NoteMark.TAPPING in marks) w.flag("Tapped")
            if (slideFlags != 0) w.prop("Slide", "Flags", slideFlags)
            if (hopoOrigin) w.flag("HopoOrigin")
            if (hopoDestination) w.flag("HopoDestination")
            if (bend != null) { w.flag("Bended"); bend.forEach { (name, value) -> w.prop("Bend$name", "Float", GpifNumbers.decimal(value)) } }
            if (harmonic != null) w.prop("HarmonicType", "HType", harmonic.first).prop("HarmonicFret", "HFret", harmonic.second)
            w.end().end()
        }
    }
}
