package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-Zyklus 19, Nutzerbericht (Handy): "Drum-Grid überlappt / ist teils unbedienbar bei sehr dichten Rastern".
 * Die Schlagzeugspur wird ohne TAB gesetzt (nur Notenköpfe). Der Editor legt sie mit Zeilenumbruch auf Viewport-Breite
 * (Handy 360 dp, Zoom 1: Layout-Einheiten = dp) und dem Eingaberaster des DrumScreens (1/16, 1/32) als leere Spalten an.
 *
 * Invarianten: (1) Mindestabstand benachbarter Ereignisse (Anker zu Anker) >= Kopfbreite; (2) die Köpfe benachbarter
 * Ereignisse (inkl. versetzter Sekunden) berühren sich nicht (Lücke >= 0,25 Linienabstand); (3) ein Tipp auf die Mitte
 * eines Kopfes trifft eine Note desselben Ereignisses (ScoreView: notes.firstOrNull { contains }).
 */
class Qa19DrumGridLayoutTest {
    private var n = 0
    private fun id() = EntityId("q19d${n++}")
    private val drums = InstrumentDefinition(kind = InstrumentKind.PERCUSSION, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)
    private fun d(midi: Int) = Note(id(), Pitch(midi))
    private fun project(bars: List<List<Beat>>): Project {
        val measures = bars.map { Measure(id()) }
        val track = Track(id(), "Drums", drums, listOf(Staff(id(), setOf(NotationKind.PERCUSSION), measures.mapIndexed { i, m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) bars[i] else emptyList()) }) })))
        return Project(id = id(), measures = measures, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(measures[0].id), 150.0)))
    }
    /** Rock-Groove in 16teln: HH jede 16tel, Kick auf den Achteln 1, 3, 3+, Snare auf 2 und 4. */
    private fun sixteenths() = (0 until 16).map { k -> Beat(id(), Fraction.of(k.toLong(), 16), WrittenDuration(16), listOfNotNull(d(42), if (k == 0 || k == 8 || k == 10) d(36) else null, if (k == 4 || k == 12) d(38) else null)) }
    /** Blast/Fill in 32teln: HH jede 32tel, Kick auf jeder 16tel, Snare auf 2/4, ab Zählzeit 3 Tom-Fill mit Sekunden (Tom 47 + Snare, Tom 48 + Tom 50). */
    private fun thirtySeconds() = (0 until 32).map { k ->
        val notes = mutableListOf(d(42))
        if (k % 2 == 0) notes += d(36)
        if (k == 8 || k == 24) notes += d(38)
        if (k >= 16 && k % 4 == 1) { notes += d(47); notes += d(38) }
        if (k >= 16 && k % 4 == 3) { notes += d(48); notes += d(50) }
        Beat(id(), Fraction.of(k.toLong(), 32), WrittenDuration(32), notes)
    }
    /** Wie der Editor (WorkspaceViewModel.relayout): EDIT-Stil, Rand 6, Zeilenumbruch auf die Viewport-Breite, Raster = Eingabedauer. */
    private fun editorLayout(p: Project, width: Float?, grid: Fraction?) = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, width, grid, 0, null, margin = 6f))

    private class Event(val system: Int, val measure: Int, val offset: Fraction, val anchor: Float, val left: Float, val right: Float, val boxes: List<NoteBox>)

    /** Messung je Layout: kleinster Ankerabstand und kleinste Kopflücke benachbarter Ereignisse, plus Treffer der Kopfmitten. */
    private fun check(name: String, l: ScoreLayout, p: Project, style: LayoutStyle = LayoutStyle.EDIT): List<String> {
        val sp = style.staffSpace
        val head = sp * 0.62f                      // Halbachse des Notenkopfs (Standard.voice: OvalGlyph rx = 0.62 sp)
        val measureIndex = p.measures.withIndex().associate { it.value.id to it.index }
        val systemOf = l.measures.associate { it.measureId to it.system }
        val events = l.notes.groupBy { it.measureId to it.offset }.map { (k, boxes) ->
            val centres = boxes.map { it.x + it.w / 2 }
            val col = l.columns.first { it.measureId == k.first && it.offset == k.second }
            Event(systemOf.getValue(k.first), measureIndex.getValue(k.first), k.second, col.x, centres.min() - head, centres.max() + head, boxes)
        }.sortedWith(compareBy({ it.measure }, { it.offset }))
        var minAnchor = Float.MAX_VALUE; var minGap = Float.MAX_VALUE; var where = ""
        val out = ArrayList<String>()
        for (k in 1 until events.size) {
            val a = events[k - 1]; val b = events[k]
            if (a.system != b.system) continue
            val anchor = b.anchor - a.anchor; val gap = b.left - a.right
            if (gap < minGap) { minGap = gap; where = "Takt ${b.measure + 1} @${b.offset}" }
            minAnchor = minOf(minAnchor, anchor)
            if (anchor < 2 * head) out += "$name: Ankerabstand ${"%.1f".format(anchor)} < Kopfbreite ${"%.1f".format(2 * head)} vor Takt ${b.measure + 1} @${b.offset}"
            if (gap < 0.25f * sp) out += "$name: Köpfe berühren sich (Lücke ${"%.1f".format(gap)}) vor Takt ${b.measure + 1} @${b.offset}"
        }
        // Tipp auf jede Kopfmitte: die erste getroffene Box (wie ScoreView) muss zum selben Ereignis gehören.
        var wrongTaps = 0
        for (nb in l.notes) {
            val cx = nb.x + nb.w / 2; val cy = nb.y + nb.h / 2
            val hit = l.notes.firstOrNull { cx >= it.x && cx <= it.x + it.w && cy >= it.y && cy <= it.y + it.h }
            if (hit == null || hit.measureId != nb.measureId || hit.offset != nb.offset) wrongTaps++
        }
        if (wrongTaps > 0) out += "$name: $wrongTaps Kopfmitten treffen ein anderes Ereignis"
        val widths = l.columns.map { it.width }
        println("D19 Layout $name: ${l.systems.size} Systeme, Breite ${"%.0f".format(l.width)}, Spalten ${"%.1f".format(widths.min())}..${"%.1f".format(widths.max())}, kleinster Ankerabstand ${"%.1f".format(minAnchor)} (Kopfbreite ${"%.1f".format(2 * head)}), kleinste Kopflücke ${"%.1f".format(minGap)} bei $where, falsche Kopftreffer $wrongTaps")
        return out.distinctBy { it.substringBefore(" vor ") }.take(6)
    }

    @Test fun denseDrumGridsKeepNoteheadsApartOnAPhone() {
        val failures = ArrayList<String>()
        val p16 = project(List(4) { sixteenths() })
        val p32 = project(List(2) { thirtySeconds() })
        for ((name, l) in listOf(
            "16tel, Raster 1/16, 360 dp" to editorLayout(p16, 360f, Fraction.of(1, 16)),
            "16tel, Raster 1/32, 360 dp" to editorLayout(p16, 360f, Fraction.of(1, 32)),
            "16tel, Raster 1/32, 360 dp / Zoom 1,5" to editorLayout(p16, 240f, Fraction.of(1, 32)),
            "32tel, Raster 1/32, 360 dp" to editorLayout(p32, 360f, Fraction.of(1, 32)),
            "32tel, Raster 1/32, 412 dp" to editorLayout(p32, 412f, Fraction.of(1, 32)),
            "32tel, ohne Umbruch" to editorLayout(p32, null, Fraction.of(1, 32)),
            "32tel, Lesemodus 360 dp" to ScoreLayouter.layout(LayoutRequest(p32, 0, LayoutStyle.READ, 360f, null)),
        )) failures += check(name, l, if (name.startsWith("16")) p16 else p32, if ("Lesemodus" in name) LayoutStyle.READ else LayoutStyle.EDIT)
        assertTrue("Drum-Notenköpfe überlappen:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Die Spalten dürfen nicht über den Takt hinauslaufen, wenn die Mindestbreite die Stauchung begrenzt (P3/P4 aus Qa15). */
    @Test fun compressedDrumBarsKeepColumnsInsideTheirMeasure() {
        val p = project(List(2) { thirtySeconds() })
        val l = editorLayout(p, 360f, Fraction.of(1, 32))
        val bad = l.columns.filter { c -> val m = l.measures.first { it.measureId == c.measureId }; c.x < m.contentX - 0.01f || c.x >= m.x + m.width }
        val boxes = l.measures.zipWithNext().filter { (a, b) -> a.system == b.system && a.x + a.width > b.x + 0.01f }
        assertTrue("Spalten außerhalb ihres Takts: ${bad.take(3)}; überlappende Takte: ${boxes.take(2)}", bad.isEmpty() && boxes.isEmpty())
    }
}
