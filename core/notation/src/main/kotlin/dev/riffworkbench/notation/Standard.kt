package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Standard notation on a five-line staff: clefs, key signatures, noteheads, accidentals, stems, beams, rests, ties. */
internal object Standard {
    enum class Clef { TREBLE_8VB, BASS_8VB, TREBLE, BASS, PERCUSSION }
    private val sharpSteps = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B (letter index C=0)
    private val flatSteps = intArrayOf(6, 2, 5, 1, 4, 0, 3)  // B E A D G C F

    fun clefFor(track: Track): Clef = when {
        track.instrument.kind == InstrumentKind.PERCUSSION -> Clef.PERCUSSION
        track.instrument.kind == InstrumentKind.BASS -> Clef.BASS_8VB
        track.instrument.kind == InstrumentKind.GUITAR || track.instrument.tuning.isNotEmpty() -> Clef.TREBLE_8VB
        else -> {
            val pitches = track.staves.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }.map { it.pitch.midiNumber }
            if (pitches.isNotEmpty() && pitches.average() < 57) Clef.BASS else Clef.TREBLE
        }
    }
    /** Diatonic step (C0 = 0) of the bottom staff line. */
    private fun bottomStep(c: Clef) = when (c) { Clef.TREBLE, Clef.TREBLE_8VB, Clef.PERCUSSION -> 4 * 7 + 2; Clef.BASS, Clef.BASS_8VB -> 2 * 7 + 4 }
    private fun writtenOffset(c: Clef) = if (c == Clef.TREBLE_8VB || c == Clef.BASS_8VB) 12 else 0

    /** Spelling: letter index (C=0..B=6), alteration and octave for a MIDI pitch in a key. */
    data class Spelled(val letter: Int, val alter: Int, val octave: Int) { val step get() = octave * 7 + letter }
    fun spell(midi: Int, key: KeySignature): Spelled {
        val pc = ((midi % 12) + 12) % 12
        val octave = midi.floorDiv(12) - 1
        val sharps = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6) to intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        val flats = intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6) to intArrayOf(0, -1, 0, -1, 0, 0, -1, 0, -1, 0, -1, 0)
        val (letters, alters) = if (key.fifths < 0) flats else sharps
        var letter = letters[pc]; var alter = alters[pc]; var oct = octave
        // Prefer diatonic spellings of the key (e.g. E# in C# major, Cb in Gb major are rare; keep it simple but key-aware).
        if (key.fifths >= 6 && pc == 5) { letter = 2; alter = 1 } // F as E#
        if (key.fifths <= -6 && pc == 11) { letter = 0; alter = -1; oct += 1 } // B as Cb
        return Spelled(letter, alter, oct)
    }
    fun keyAlter(letter: Int, key: KeySignature): Int = when {
        key.fifths > 0 && sharpSteps.take(key.fifths).contains(letter) -> 1
        key.fifths < 0 && flatSteps.take(-key.fifths).contains(letter) -> -1
        else -> 0
    }

    // Percussion positions (bottom line E4 = step 30) and notehead style per GM note.
    private fun drumStep(midi: Int): Pair<Int, Boolean> = when (midi) {
        35, 36 -> 29 to false; 38, 40, 37 -> 35 to (midi == 37); 39 -> 35 to true; 41 -> 31 to false; 43 -> 32 to false; 45 -> 33 to false; 47 -> 34 to false; 48 -> 36 to false; 50 -> 37 to false
        42, 46 -> 39 to true; 44 -> 26 to true; 49, 57 -> 40 to true; 51, 59 -> 38 to true; 53 -> 38 to false; 52, 55 -> 41 to true; 56 -> 37 to true; else -> 35 to true
    }

    /** Lowest/highest staff position (half-spaces above the bottom line) in [bars], including stems. */
    fun range(b: ScoreLayouter.Builder, bars: List<Bar>): Pair<Int, Int> {
        val clef = b.clef
        var lo = 0; var hi = 8
        for (bar in bars) for (v in bar.voices) for (beat in v.beats) for (n in beat.notes) {
            val pos = if (clef == Clef.PERCUSSION) drumStep(n.pitch.midiNumber).first - bottomStep(clef) else spell(n.pitch.midiNumber + writtenOffset(clef), bar.let { KeySignature() }).step - bottomStep(clef)
            // Low notes carry stems up, high notes stems down: reserve stem room only on the outer side.
            lo = minOf(lo, if (pos >= 4) pos - 7 else pos - 2); hi = maxOf(hi, if (pos < 4) pos + 7 else pos + 2)
        }
        return lo.coerceAtLeast(-18) to hi.coerceAtMost(26)
    }

    fun clef(b: ScoreLayouter.Builder, x: Float, staffTop: Float) {
        val sp = b.styleOf().staffSpace
        val c = b.clef
        when (c) {
            Clef.PERCUSSION -> { b.add(RectGlyph(x + sp * 0.6f, staffTop + sp, sp * 0.45f, sp * 2, Ink.TEXT)); b.add(RectGlyph(x + sp * 1.4f, staffTop + sp, sp * 0.45f, sp * 2, Ink.TEXT)) }
            Clef.TREBLE, Clef.TREBLE_8VB -> {
                val ox = x + sp * 1.3f; val oy = staffTop + sp * 3
                fun px(v: Float) = ox + v * sp
                fun py(v: Float) = oy + v * sp
                b.add(PathGlyph.Builder().move(px(0.35f), py(0.15f)).cubic(px(0f), py(0.3f), px(-0.55f), py(-0.1f), px(-0.3f), py(-0.75f))
                    .cubic(px(0f), py(-1.3f), px(1f), py(-1.2f), px(1.05f), py(-0.4f)).cubic(px(1.1f), py(0.5f), px(0.2f), py(1.05f), px(-0.5f), py(0.9f))
                    .cubic(px(-1.3f), py(0.7f), px(-1.4f), py(-0.5f), px(-0.7f), py(-1.4f)).cubic(px(0f), py(-2.3f), px(0.9f), py(-3f), px(0.85f), py(-4f))
                    .cubic(px(0.8f), py(-4.8f), px(0.2f), py(-4.9f), px(0.05f), py(-4.2f)).cubic(px(-0.15f), py(-3.3f), px(0.2f), py(-1f), px(0.45f), py(1.2f))
                    .cubic(px(0.55f), py(2f), px(0.2f), py(2.5f), px(-0.3f), py(2.3f)).build(Ink.TEXT, false, sp * 0.22f))
                if (c == Clef.TREBLE_8VB) b.add(TextGlyph(px(0.3f), py(3.6f), "8", sp * 1.1f, Ink.TEXT, bold = true))
            }
            Clef.BASS, Clef.BASS_8VB -> {
                val ox = x + sp * 0.6f; val oy = staffTop + sp
                fun px(v: Float) = ox + v * sp
                fun py(v: Float) = oy + v * sp
                b.add(OvalGlyph(px(0.35f), py(0f), sp * 0.28f, sp * 0.28f, 0f, Ink.TEXT, true))
                b.add(PathGlyph.Builder().move(px(0.15f), py(0f)).cubic(px(0.2f), py(-1.1f), px(2.2f), py(-1.1f), px(2.1f), py(0.3f))
                    .cubic(px(2f), py(1.5f), px(1f), py(2.4f), px(-0.1f), py(3f)).build(Ink.TEXT, false, sp * 0.24f))
                b.add(OvalGlyph(px(2.7f), py(-0.45f), sp * 0.16f, sp * 0.16f, 0f, Ink.TEXT, true)); b.add(OvalGlyph(px(2.7f), py(0.5f), sp * 0.16f, sp * 0.16f, 0f, Ink.TEXT, true))
                if (c == Clef.BASS_8VB) b.add(TextGlyph(px(0.8f), py(4.3f), "8", sp * 1.1f, Ink.TEXT, bold = true))
            }
        }
    }

    fun key(b: ScoreLayouter.Builder, key: KeySignature, x: Float, staffTop: Float) {
        val sp = b.styleOf().staffSpace
        val c = b.clef
        if (c == Clef.PERCUSSION || key.fifths == 0) return
        val bottom = staffTop + sp * 4
        // Positions (half-spaces above the bottom line) of the accidental symbols for treble; bass is a third lower.
        val sharpPos = intArrayOf(8, 5, 9, 6, 3, 7, 4); val flatPos = intArrayOf(4, 7, 3, 6, 2, 5, 1)
        val shift = if (c == Clef.BASS || c == Clef.BASS_8VB) -2 else 0
        for (k in 0 until abs(key.fifths)) {
            val pos = (if (key.fifths > 0) sharpPos[k] else flatPos[k]) + shift
            accidental(b, x + 4f + k * sp * 1.1f, bottom - pos * sp / 2, if (key.fifths > 0) 1 else -1, Ink.TEXT)
        }
    }

    fun accidental(b: ScoreLayouter.Builder, x: Float, y: Float, alter: Int, ink: Ink) {
        val sp = b.styleOf().staffSpace
        when (alter) {
            1 -> {
                b.add(LineGlyph(x - sp * 0.2f, y - sp * 1.1f, x - sp * 0.2f, y + sp * 1.2f, 1f, ink)); b.add(LineGlyph(x + sp * 0.25f, y - sp * 1.2f, x + sp * 0.25f, y + sp * 1.1f, 1f, ink))
                b.add(LineGlyph(x - sp * 0.5f, y - sp * 0.25f, x + sp * 0.55f, y - sp * 0.45f, sp * 0.22f, ink)); b.add(LineGlyph(x - sp * 0.5f, y + sp * 0.45f, x + sp * 0.55f, y + sp * 0.25f, sp * 0.22f, ink))
            }
            -1 -> {
                b.add(LineGlyph(x - sp * 0.3f, y - sp * 1.6f, x - sp * 0.3f, y + sp * 0.5f, 1.1f, ink))
                b.add(PathGlyph.Builder().move(x - sp * 0.3f, y).cubic(x + sp * 0.2f, y - sp * 0.6f, x + sp * 0.75f, y - sp * 0.1f, x - sp * 0.3f, y + sp * 0.5f).build(ink, false, 1.2f))
            }
            0 -> {
                b.add(LineGlyph(x - sp * 0.25f, y - sp * 1.1f, x - sp * 0.25f, y + sp * 0.5f, 1f, ink)); b.add(LineGlyph(x + sp * 0.25f, y - sp * 0.5f, x + sp * 0.25f, y + sp * 1.1f, 1f, ink))
                b.add(LineGlyph(x - sp * 0.25f, y - sp * 0.3f, x + sp * 0.25f, y - sp * 0.45f, sp * 0.2f, ink)); b.add(LineGlyph(x - sp * 0.25f, y + sp * 0.45f, x + sp * 0.25f, y + sp * 0.3f, sp * 0.2f, ink))
            }
            2 -> b.add(TextGlyph(x, y + sp * 0.4f, "×", sp * 1.6f, ink, bold = true))
            -2 -> { accidental(b, x - sp * 0.45f, y, -1, ink); accidental(b, x + sp * 0.35f, y, -1, ink) }
        }
    }

    private class Head(val note: Note, val pos: Int, val alter: Int?, val x: Float, val y: Float, val cross: Boolean)

    fun voice(b: ScoreLayouter.Builder, sys: SystemBox, s: ScoreLayouter.MeasureSpec, v: Voice, ink: Ink, multiVoice: Boolean, xFor: (Fraction) -> Float) {
        val sp = b.styleOf().staffSpace
        val clef = b.clef
        val bottom = sys.staffBottom
        val key = s.measure.key
        val altered = HashMap<Int, Int>() // step -> alteration in effect within the measure
        val beats = v.beats.sortedBy { it.offset }
        class Stemmed(val beat: Beat, val x: Float, val up: Boolean, val tipY: Float, val heads: List<Head>)
        val stemmed = ArrayList<Stemmed>()
        for (beat in beats) {
            val x = xFor(beat.offset)
            if (beat.notes.isEmpty()) {
                if (beat.explicitRest || true) rest(b, x, sys.staffTop + sp * 2, beat.duration, ink, if (multiVoice) (if (v.index % 2 == 0) -sp * 1.5f else sp * 1.5f) else 0f)
                continue
            }
            val heads = beat.notes.map { n ->
                if (clef == Clef.PERCUSSION) {
                    val (step, cross) = drumStep(n.pitch.midiNumber)
                    val pos = step - bottomStep(clef)
                    Head(n, pos, null, x, bottom - pos * sp / 2, cross || Articulation.Mark(NoteMark.DEAD) in n.articulations)
                } else {
                    val sp2 = spell(n.pitch.midiNumber + writtenOffset(clef), key)
                    val pos = sp2.step - bottomStep(clef)
                    val inEffect = altered[sp2.step] ?: keyAlter(sp2.letter, key)
                    val show = if (sp2.alter != inEffect) sp2.alter else null
                    altered[sp2.step] = sp2.alter
                    Head(n, pos, show, x, bottom - pos * sp / 2, Articulation.Mark(NoteMark.DEAD) in n.articulations)
                }
            }.sortedBy { it.pos }
            val d = beat.duration
            val up = if (multiVoice) v.index % 2 == 0 else clef == Clef.PERCUSSION || heads.map { it.pos }.average() < 4
            // Ledger lines
            for (h in heads) {
                val lo = if (h.pos < -1) h.pos else 0
                val hi = if (h.pos > 9) h.pos else 8
                var p = -2; while (p >= lo) { if (p % 2 == 0) b.add(LineGlyph(x - sp * 0.95f, bottom - p * sp / 2, x + sp * 0.95f, bottom - p * sp / 2, 0.9f, Ink.STAFF)); p-- }
                p = 10; while (p <= hi) { if (p % 2 == 0) b.add(LineGlyph(x - sp * 0.95f, bottom - p * sp / 2, x + sp * 0.95f, bottom - p * sp / 2, 0.9f, Ink.STAFF)); p++ }
            }
            // Noteheads with seconds displaced to the other side of the stem.
            var lastPos = Int.MIN_VALUE; var displaced = false
            val placed = heads.map { h ->
                displaced = if (h.pos - lastPos == 1) !displaced else false
                lastPos = h.pos
                val dx = if (displaced) (if (up) sp * 1.15f else -sp * 1.15f) else 0f
                Head(h.note, h.pos, h.alter, h.x + dx, h.y, h.cross)
            }
            for (h in placed) {
                val ghost = Articulation.Mark(NoteMark.GHOST) in h.note.articulations
                val harmonic = h.note.articulations.any { it is Articulation.Harmonic }
                if (harmonic && !h.cross) {
                    b.add(PathGlyph.Builder().move(h.x - sp * 0.62f, h.y).line(h.x, h.y - sp * 0.5f).line(h.x + sp * 0.62f, h.y).line(h.x, h.y + sp * 0.5f).close().build(ink, d.denominator >= 4, 1.2f))
                } else if (h.cross) {
                    b.add(LineGlyph(h.x - sp * 0.5f, h.y - sp * 0.45f, h.x + sp * 0.5f, h.y + sp * 0.45f, 1.3f, ink)); b.add(LineGlyph(h.x - sp * 0.5f, h.y + sp * 0.45f, h.x + sp * 0.5f, h.y - sp * 0.45f, 1.3f, ink))
                } else b.add(OvalGlyph(h.x, h.y, sp * 0.62f, sp * 0.44f, -20f, ink, fill = d.denominator >= 4, strokeWidth = 1.3f))
                if (ghost) { b.add(TextGlyph(h.x - sp * 1.05f, h.y + sp * 0.45f, "(", sp * 1.5f, ink)); b.add(TextGlyph(h.x + sp * 1.05f, h.y + sp * 0.45f, ")", sp * 1.5f, ink)) }
                h.alter?.let { accidental(b, h.x - sp * 1.7f, h.y, it, ink) }
                for (k in 0 until d.dots) { val dy = if (h.pos % 2 == 0) -sp / 2 else 0f; b.add(OvalGlyph(h.x + sp * (1.2f + k * 0.7f), h.y + dy, sp * 0.16f, sp * 0.16f, 0f, ink, true)) }
            }
            if (d.denominator >= 2) {
                val stemX = if (up) x + sp * 0.58f else x - sp * 0.58f
                val far = if (up) placed.minOf { it.y } else placed.maxOf { it.y }
                val near = if (up) placed.maxOf { it.y } else placed.minOf { it.y }
                val tip = if (up) far - sp * 3.5f else far + sp * 3.5f
                b.add(LineGlyph(stemX, near, stemX, tip, 1.1f, ink))
                stemmed += Stemmed(beat, stemX, up, tip, placed)
            }
            ties(b, sys, s, placed, sp, ink)
        }
        beams(b, s, stemmed.map { Triple(it.beat, it.x, it.up) }, stemmed.map { it.tipY }, sp, ink)
    }

    private fun ties(b: ScoreLayouter.Builder, sys: SystemBox, s: ScoreLayouter.MeasureSpec, heads: List<Head>, sp: Float, ink: Ink) {
        for (h in heads) {
            b.standardHeads[h.note.id] = Triple(h.x, h.y, sys.index)
            val tie = h.note.links.firstOrNull { it.kind == LinkKind.TIE } ?: continue
            b.pendingTies += ScoreLayouter.PendingTie(tie.target, h.x + sp * 0.6f, h.y + sp * 0.7f, sys.index, sys.x1, sp * 0.9f, ink, standard = true)
        }
    }

    private fun beams(b: ScoreLayouter.Builder, s: ScoreLayouter.MeasureSpec, stems: List<Triple<Beat, Float, Boolean>>, tips: List<Float>, sp: Float, ink: Ink) {
        var k = 0
        while (k < stems.size) {
            val (beat, x, up) = stems[k]
            val n = BeamGroups.beams(beat.duration)
            if (n == 0) { k++; continue }
            val group = BeamGroups.groupOf(beat.offset, s.measure.signature)
            var e = k
            while (e + 1 < stems.size && BeamGroups.beams(stems[e + 1].first.duration) > 0 && BeamGroups.groupOf(stems[e + 1].first.offset, s.measure.signature) == group && stems[e + 1].third == up) e++
            if (e == k) {
                for (f in 0 until n) { val y = tips[k] + (if (up) f * sp * 0.8f else -f * sp * 0.8f); b.add(PathGlyph.Builder().move(x, y).cubic(x + sp * 0.3f, y + (if (up) sp * 0.5f else -sp * 0.5f), x + sp * 1.2f, y + (if (up) sp * 1f else -sp * 1f), x + sp * 0.9f, y + (if (up) sp * 2.1f else -sp * 2.1f)).build(ink, false, 1.3f)) }
            } else {
                val y = if (up) tips.subList(k, e + 1).minOrNull()!! else tips.subList(k, e + 1).maxOrNull()!!
                // Extend stems to the common beam line.
                for (i in k..e) b.add(LineGlyph(stems[i].second, tips[i], stems[i].second, y, 1.1f, ink))
                for (level in 1..4) {
                    var i = k
                    while (i <= e) {
                        if (BeamGroups.beams(stems[i].first.duration) >= level) {
                            var j = i
                            while (j + 1 <= e && BeamGroups.beams(stems[j + 1].first.duration) >= level) j++
                            val yy = y + (if (up) (level - 1) * sp * 0.8f else -(level - 1) * sp * 0.8f)
                            val x0 = stems[i].second; val x1 = if (j > i) stems[j].second else (if (i == e) x0 - sp * 1.1f else x0 + sp * 1.1f)
                            b.add(RectGlyph(min(x0, x1), yy - sp * 0.25f, abs(x1 - x0) + 1f, sp * 0.5f, ink))
                            i = j + 1
                        } else i++
                    }
                }
            }
            k = e + 1
        }
    }

    private fun rest(b: ScoreLayouter.Builder, x: Float, mid: Float, d: WrittenDuration, ink: Ink, dy: Float) {
        val sp = b.styleOf().staffSpace
        val y = mid + dy
        when {
            d.denominator <= 1 -> b.add(RectGlyph(x - sp * 0.6f, y - sp, sp * 1.2f, sp * 0.5f, ink))
            d.denominator == 2 -> b.add(RectGlyph(x - sp * 0.6f, y - sp * 0.5f, sp * 1.2f, sp * 0.5f, ink))
            d.denominator == 4 -> b.add(PathGlyph.Builder().move(x - sp * 0.3f, y - sp * 1.5f).line(x + sp * 0.4f, y - sp * 0.7f).line(x - sp * 0.3f, y).line(x + sp * 0.4f, y + sp * 0.7f)
                .cubic(x - sp * 0.3f, y + sp * 0.5f, x - sp * 0.4f, y + sp * 1.1f, x + sp * 0.05f, y + sp * 1.5f).build(ink, false, sp * 0.25f))
            else -> {
                val n = BeamGroups.beams(d)
                b.add(LineGlyph(x + sp * 0.5f, y - sp, x - sp * 0.2f, y + sp * (0.6f + n * 0.5f), 1.2f, ink))
                for (k in 0 until n) b.add(OvalGlyph(x - sp * 0.1f - k * sp * 0.25f, y - sp * 0.65f + k * sp * 0.9f, sp * 0.25f, sp * 0.25f, 0f, ink, true))
            }
        }
        for (k in 0 until d.dots) b.add(OvalGlyph(x + sp * (1.1f + k * 0.7f), y - sp * 0.5f, sp * 0.16f, sp * 0.16f, 0f, ink, true))
    }

}
