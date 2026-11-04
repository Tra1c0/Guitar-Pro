package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlElement
import dev.riffworkbench.model.*

internal object NoteReader {
    private val NOTE_CHILDREN = setOf("Properties", "Tie", "LetRing", "Vibrato", "AntiAccent", "Accent", "Trill", "InstrumentArticulation", "LeftFingering", "RightFingering")
    private val NOTE_PROPERTIES = setOf("String", "Fret", "Midi", "Tone", "Octave", "ConcertPitch", "TransposedPitch", "PalmMuted", "Muted", "Slide", "HopoOrigin", "HopoDestination",
        "Tapped", "LeftHandTapped", "Bended", "BendOriginValue", "BendOriginOffset", "BendMiddleValue", "BendMiddleOffset1", "BendMiddleOffset2", "BendDestinationValue",
        "BendDestinationOffset", "HarmonicType", "HarmonicFret", "Element", "Variation")

    /** Slide flag bits (GPIF Slide/Flags). */
    const val SLIDE_SHIFT = 1
    const val SLIDE_LEGATO = 2
    const val SLIDE_OUT_DOWN = 4
    const val SLIDE_OUT_UP = 8
    const val SLIDE_IN_BELOW = 16
    const val SLIDE_IN_ABOVE = 32
    const val SLIDE_PICK_DOWN = 64
    const val SLIDE_PICK_UP = 128

    /** Accent bits (GPIF Accent): 1 staccato, 2 staccatissimo, 4 heavy accent (marcato), 8 accent, 16 tenuto. */
    const val ACCENT_STACCATO = 1
    const val ACCENT_HEAVY = 4
    const val ACCENT_NORMAL = 8

    fun vibrato(strength: String): Articulation.Vibrato = if (strength == "Wide") Articulation.Vibrato(depthCents = 60) else Articulation.Vibrato()

    fun read(el: XmlElement, track: TrackPlan, ids: IdSource, losses: LossCollector): NoteDraft? {
        val p = el.props()
        for (c in el.elements) if (c.name !in NOTE_CHILDREN) losses.add(if (c.name == "Ornament") "rhythm.ornaments" else "import.unknown_fields", "Noten-Element '${c.name}' wird nicht übernommen")
        for (name in p.keys) if (name !in NOTE_PROPERTIES) losses.add(if (name == "ShowStringNumber") "layout.stylesheet" else "import.unknown_fields", "Noteneigenschaft '$name' wird nicht übernommen")
        fun int(name: String, child: String) = GpifNumbers.parseIntOrNull(p[name]?.textOf(child))
        val fileMidi = int("Midi", "Number")
        val toneMidi = int("Octave", "Number")?.let { o -> int("Tone", "Step")?.let { t -> o * 12 + t } }
        var string: Int? = null
        var fret: Int? = null
        var midi: Int? = null
        var technique: PercussionTechnique? = null
        when {
            track.percussion -> {
                val articulation = GpifNumbers.parseIntOrNull(el.textOf("InstrumentArticulation"))?.let { track.drumArticulations.getOrNull(it) }
                midi = articulation?.midi?.takeIf { it in 0..127 } ?: fileMidi ?: toneMidi
                technique = articulation?.technique
                if (midi == null) {
                    losses.add("drums.entry", if (p["Element"] != null) "GP6-Schlagzeugnote (Element/Variation) ohne MIDI-Zuordnung wird verworfen" else "Schlagzeugnote ohne MIDI-Zuordnung wird verworfen")
                    return null
                }
            }
            track.stringed && p["String"] != null -> {
                string = int("String", "String")
                fret = int("Fret", "Fret")
                if (string == null || string !in track.tuning.indices) { losses.add("notes.fret", "Note auf nicht vorhandener Saite wird verworfen"); return null }
                if (fret != null && fret !in 0..48) { losses.add("notes.fret", "Bund außerhalb 0–48 wird verworfen"); return null }
            }
            else -> {
                midi = fileMidi ?: toneMidi
                if (midi == null) { losses.add("notes.fret", "Note ohne Tonhöhe wird verworfen"); return null }
            }
        }
        if (midi != null && midi !in 0..127) { losses.add("notes.fret", "Tonhöhe außerhalb des MIDI-Bereichs wird verworfen"); return null }
        val note = NoteDraft(ids.next(), string, fret, midi, fileMidi)
        if (string != null && fret != null) note.midi = track.pitch(string, fret)

        if (p["PalmMuted"] != null) note.marks += NoteMark.PALM_MUTE
        if (p["Muted"] != null) note.marks += NoteMark.DEAD
        if (el.child("AntiAccent") != null) note.marks += NoteMark.GHOST
        val accent = GpifNumbers.parseIntOrNull(el.textOf("Accent")) ?: 0
        if (accent and ACCENT_NORMAL != 0) note.marks += NoteMark.ACCENT
        if (accent and ACCENT_HEAVY != 0) note.marks += NoteMark.HEAVY_ACCENT
        if (el.child("LetRing") != null) note.marks += NoteMark.LET_RING
        if (accent and ACCENT_STACCATO != 0) note.marks += NoteMark.STACCATO
        if (accent and (2 or 16) != 0) losses.add("fx.staccato", "Staccatissimo/Tenuto werden nicht unterstützt")
        if (accent and 0x1F.inv() != 0) losses.add("fx.accent", "Unbekannte Akzentart wird nicht übernommen")
        if (p["Tapped"] != null) note.marks += NoteMark.TAPPING
        if (p["LeftHandTapped"] != null) { note.marks += NoteMark.TAPPING; losses.add("fx.tapping", "Linkshand-Tapping wird als Tapping übernommen") }

        val slide = int("Slide", "Flags") ?: 0
        note.slideFlags = slide
        if (slide and SLIDE_IN_BELOW != 0) note.articulations += Articulation.Slide(SlideKind.INTO_FROM_BELOW)
        if (slide and SLIDE_IN_ABOVE != 0) note.articulations += Articulation.Slide(SlideKind.INTO_FROM_ABOVE)
        if (slide and SLIDE_OUT_DOWN != 0) note.articulations += Articulation.Slide(SlideKind.OUT_DOWN)
        if (slide and SLIDE_OUT_UP != 0) note.articulations += Articulation.Slide(SlideKind.OUT_UP)
        if (slide and (SLIDE_PICK_DOWN or SLIDE_PICK_UP) != 0) {
            note.marks += NoteMark.PICK_SCRAPE
            if (slide and SLIDE_PICK_UP != 0) losses.add("fx.pick_scrape", "Richtung des Pick-Slides (aufwärts) wird nicht gespeichert")
        }
        if (slide and 0xFF.inv() != 0) losses.add("fx.slide", "Unbekannte Slide-Variante wird nicht übernommen")
        note.hopoOrigin = p["HopoOrigin"] != null
        val tie = el.child("Tie")
        note.tieOrigin = tie?.attr("origin") == "true"
        note.tieDestination = tie?.attr("destination") == "true"

        if (p["Bended"] != null || p["BendDestinationValue"] != null) BarReader.curve(p, "Bend")?.let { points ->
            if (points.any { it.cents != 0 }) note.articulations += Articulation.Bend(points, bendKind(points))
        }
        harmonic(p, losses)?.let { note.articulations += it }
        el.textOf("Vibrato")?.trim()?.let { if (it == "Slight" || it == "Wide") note.articulations += vibrato(it) else losses.add("fx.vibrato", "Unbekannte Vibrato-Art $it") }
        note.trillMidi = GpifNumbers.parseIntOrNull(el.textOf("Trill"))?.takeIf { it in 0..127 }
        if (technique != null) note.articulations += Articulation.Percussion(technique)

        val left = el.textOf("LeftFingering")?.trim()
        val right = el.textOf("RightFingering")?.trim()
        if (!left.isNullOrEmpty() || !right.isNullOrEmpty()) {
            val leftFinger = when (left) { "P" -> 0; "I" -> 1; "M" -> 2; "A" -> 3; "C" -> 4; null, "" -> null; else -> { losses.add("notes.fingering", "Fingersatz $left wird nicht übernommen"); null } }
            val rightFinger = right?.takeIf { it in setOf("P", "I", "M", "A", "C") }?.lowercase()
            if (right != null && right.isNotEmpty() && rightFinger == null) losses.add("notes.fingering", "Fingersatz $right wird nicht übernommen")
            if (leftFinger != null || rightFinger != null) note.fingering = Fingering(leftFinger, rightFinger)
        }
        p["ConcertPitch"]?.child("Pitch")?.let { pitch ->
            val step = pitch.textOf("Step")?.trim().orEmpty()
            val accidental = pitch.textOf("Accidental")?.trim().orEmpty()
            if (step.length == 1 && step[0] in 'A'..'G' && accidental in setOf("", "#", "b", "##", "bb", "x")) note.spelling = step + accidental.replace("x", "##")
        }
        return note
    }

    /** BEND rises, PREBEND starts bent, RELEASE comes back down after rising, PREBEND_RELEASE comes down from a prebend. */
    fun bendKind(points: List<PitchCurvePoint>): BendKind {
        val first = points.first().cents
        val max = points.maxOf { it.cents }
        val last = points.last().cents
        return when {
            first > 0 && last < max -> BendKind.PREBEND_RELEASE
            first > 0 -> BendKind.PREBEND
            max > 0 && last < max -> BendKind.RELEASE
            else -> BendKind.BEND
        }
    }

    private fun harmonic(p: Map<String, XmlElement>, losses: LossCollector): Articulation.Harmonic? {
        val type = p["HarmonicType"]?.textOf("HType")?.trim() ?: return null
        val kind = when (type) {
            "Natural" -> HarmonicKind.NATURAL; "Artificial" -> HarmonicKind.ARTIFICIAL; "Pinch" -> HarmonicKind.PINCH
            "Tap" -> HarmonicKind.TAPPED; "Semi" -> HarmonicKind.SEMI; "Feedback" -> HarmonicKind.FEEDBACK
            "NoHarmonic", "" -> return null
            else -> { losses.add("fx.harmonic_natural", "Unbekannte Flageolett-Art $type"); return null }
        }
        val fret = GpifNumbers.parseDoubleOrNull(p["HarmonicFret"]?.textOf("HFret"))
        val node = fret?.let { GpifUnits.nodeForFret(it) }
        if (fret != null && node == null) losses.add("fx.harmonic_natural", "Flageolett-Position $fret liegt nicht auf einem Obertonknoten und wird nicht gespeichert")
        return Articulation.Harmonic(kind, node)
    }
}
