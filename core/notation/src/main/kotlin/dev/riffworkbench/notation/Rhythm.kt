package dev.riffworkbench.notation

import dev.riffworkbench.model.*

internal object MusicText {
    private val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    fun pitchClass(midi: Int) = names[((midi % 12) + 12) % 12]
}

/** Beam groups per meter: quarters in simple time, dotted quarters in compound x/8, 2+2+…+3 in odd x/8. */
internal object BeamGroups {
    fun groupOf(offset: Fraction, signature: TimeSignature): Int {
        val x = offset.toDouble()
        return when {
            signature.denominator == 8 && signature.numerator % 3 == 0 && signature.numerator > 3 -> (x / (3.0 / 8) + 1e-9).toInt()
            signature.denominator == 8 -> {
                val eighth = (x * 8 + 1e-9).toInt()
                val pairs = (signature.numerator - 1) / 2
                if (signature.numerator % 2 == 1 && eighth >= (pairs - 1) * 2) pairs - 1 else eighth / 2
            }
            signature.denominator >= 16 -> (x * 8 + 1e-9).toInt()
            else -> (x * 4 + 1e-9).toInt()
        }
    }
    fun beams(d: WrittenDuration) = when { d.denominator >= 64 -> 4; d.denominator >= 32 -> 3; d.denominator >= 16 -> 2; d.denominator >= 8 -> 1; else -> 0 }
}

/** GP-style rhythm under the TAB staff: stems down, beams, flags, dots, rests and tuplet numbers. */
internal object Rhythm {
    fun draw(b: ScoreLayouter.Builder, sys: SystemBox, measure: Measure, v: Voice, ink: Ink, xFor: (Fraction) -> Float) {
        val style = b.styleOf()
        val top = sys.tabBottom + style.tabLine * 0.45f
        val stem = 20f
        val bottom = top + stem
        val beats = v.beats.sortedBy { it.offset }
        // Rests and unbeamable notes first; beamable runs by beat group.
        val runs = ArrayList<MutableList<Beat>>()
        var current: MutableList<Beat>? = null; var currentGroup = -1
        for (beat in beats) {
            val x = xFor(beat.offset)
            if (beat.notes.isEmpty()) { rest(b, x, top + stem * 0.45f, beat.duration, ink); current = null; currentGroup = -1; continue }
            val beams = BeamGroups.beams(beat.duration)
            if (beat.duration.denominator >= 4) b.add(LineGlyph(x, top, x, if (beat.duration.denominator == 2) top + stem * 0.5f else bottom, 1.1f, ink))
            if (beat.duration.denominator == 2) b.add(LineGlyph(x, top, x, top + stem * 0.5f, 1.1f, ink))
            dots(b, x, bottom, beat.duration, ink)
            val group = BeamGroups.groupOf(beat.offset, measure.signature)
            if (beams > 0) {
                if (current != null && group == currentGroup) current += beat else { current = mutableListOf(beat); runs += current; currentGroup = group }
            } else { current = null; currentGroup = -1 }
        }
        for (run in runs) {
            if (run.size == 1) { flags(b, xFor(run[0].offset), bottom, BeamGroups.beams(run[0].duration), ink); continue }
            val xs = run.map { xFor(it.offset) }
            b.add(RectGlyph(xs.first(), bottom - 2.2f, xs.last() - xs.first() + 0.6f, 2.6f, ink))
            for (level in 2..4) {
                var k = 0
                while (k < run.size) {
                    if (BeamGroups.beams(run[k].duration) >= level) {
                        var e = k
                        while (e + 1 < run.size && BeamGroups.beams(run[e + 1].duration) >= level) e++
                        val y = bottom - 2.2f - (level - 1) * 4.2f
                        if (e > k) b.add(RectGlyph(xs[k], y, xs[e] - xs[k] + 0.6f, 2.6f, ink))
                        else { val dir = if (k == run.lastIndex) -1f else 1f; b.add(RectGlyph(if (dir > 0) xs[k] else xs[k] - 6f, y, 6f, 2.6f, ink)) }
                        k = e + 1
                    } else k++
                }
            }
        }
        tuplets(b, beats, xFor, bottom + 10f, ink)
    }

    fun dots(b: ScoreLayouter.Builder, x: Float, y: Float, d: WrittenDuration, ink: Ink) {
        for (k in 0 until d.dots) b.add(OvalGlyph(x + 4f + k * 3.5f, y - 3f, 1.3f, 1.3f, 0f, ink, true))
    }
    fun flags(b: ScoreLayouter.Builder, x: Float, y: Float, count: Int, ink: Ink) {
        for (k in 0 until count) {
            val yy = y - k * 4.5f
            b.add(PathGlyph.Builder().move(x, yy).cubic(x + 2f, yy - 1f, x + 6f, yy - 3f, x + 6.5f, yy - 7f).build(ink, false, 1.3f))
        }
    }
    fun rest(b: ScoreLayouter.Builder, x: Float, y: Float, d: WrittenDuration, ink: Ink) {
        when {
            d.denominator <= 1 -> b.add(RectGlyph(x - 4f, y - 3f, 8f, 3f, ink))
            d.denominator == 2 -> b.add(RectGlyph(x - 4f, y, 8f, 3f, ink))
            d.denominator == 4 -> b.add(PathGlyph.Builder().move(x - 1.5f, y - 7f).line(x + 2.5f, y - 3f).line(x - 1.5f, y + 0.5f).line(x + 2.5f, y + 4f).cubic(x - 1f, y + 3f, x - 2f, y + 6f, x + 0.5f, y + 8f).build(ink, false, 1.5f))
            else -> {
                val n = BeamGroups.beams(d)
                b.add(LineGlyph(x + 2f, y - 5f, x - 1.5f, y + 6f, 1.3f, ink))
                for (k in 0 until n) b.add(OvalGlyph(x - 0.5f - k * 1.2f, y - 4f + k * 3.5f, 1.6f, 1.6f, 0f, ink, true))
            }
        }
        dots(b, x + 2f, y + 3f, d, ink)
    }
    fun tuplets(b: ScoreLayouter.Builder, beats: List<Beat>, xFor: (Fraction) -> Float, y: Float, ink: Ink) {
        var k = 0
        while (k < beats.size) {
            val t = beats[k].duration.tuplets
            if (t.isEmpty()) { k++; continue }
            var e = k
            var total = Fraction.ZERO
            // A complete tuplet spans `actual` written units of its denominator (3 eighths for an eighth triplet).
            val target = Fraction.of(t.last().actual.toLong(), 1) * Fraction.of(1, beats[k].duration.denominator.toLong())
            while (e < beats.size && beats[e].duration.tuplets == t) { total += Fraction.of(1, beats[e].duration.denominator.toLong()) * Fraction.of(1, 1); if (total >= target) break; e++ }
            val last = e.coerceAtMost(beats.lastIndex)
            val x0 = xFor(beats[k].offset); val x1 = xFor(beats[last].offset)
            b.add(TextGlyph((x0 + x1) / 2, y, "${t.last().actual}", 8.5f, ink, italic = true, bold = true))
            if (t.size > 1) b.add(TextGlyph((x0 + x1) / 2, y + 9f, "${t.first().actual}", 8f, ink, italic = true))
            k = last + 1
        }
    }
}
