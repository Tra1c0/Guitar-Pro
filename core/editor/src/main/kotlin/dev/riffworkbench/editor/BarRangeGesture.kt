package dev.riffworkbench.editor

import dev.riffworkbench.model.*

/**
 * Bereichsauswahl per langem Drücken (Bericht 1 §5) als reine Logik: ein langer Druck auf einen Takt setzt den Anker
 * (den Cursor-Takt der Auswahl) und wählt genau diesen Takt; Ziehen verschiebt das Bereichsende ([Selection.rangeEnd])
 * taktweise in beide Richtungen; Loslassen beendet die Geste, der Bereich bleibt stehen. Ein einfaches Tippen hebt ihn
 * wie bisher auf (Aufrufer).
 *
 * Nur die Auswahl ändert sich: kein Befehl, kein Undo-Schritt, kein Transport-Seek. Die Ergebnisse werden vom Aufrufer
 * mit [EditorSession.select] übernommen (die Session prüft, dass die Takte existieren).
 */
class BarRangeGesture {
    /** Anker der laufenden Geste, null ohne Geste. */
    var anchor: EntityId? = null
        private set
    val active: Boolean get() = anchor != null

    /**
     * Startet die Geste auf [anchor]: Cursor in diesen Takt (die Position bleibt, wenn der Cursor schon dort stand,
     * sonst Taktanfang), Bereich = dieser eine Takt. Spur, Stimme und Saite bleiben. Eine laufende Geste wird ersetzt.
     */
    fun begin(selection: Selection, anchor: EntityId): Selection {
        this.anchor = anchor
        val offset = if (selection.measureId == anchor) selection.offset else Fraction.ZERO
        return selection.copy(measureId = anchor, offset = offset, rangeEnd = anchor)
    }

    /**
     * Bereichsende auf [bar]; null, wenn keine Geste läuft oder sich nichts ändert (Finger noch im selben Takt) — der
     * Aufrufer meldet nur bei einem Ergebnis ungleich null einen Taktwechsel (Haptik-Tick).
     */
    fun extend(selection: Selection, bar: EntityId): Selection? {
        val a = anchor ?: return null
        if (selection.measureId == a && selection.rangeEnd == bar) return null
        val offset = if (selection.measureId == a) selection.offset else Fraction.ZERO
        return selection.copy(measureId = a, offset = offset, rangeEnd = bar)
    }

    /** Beendet die Geste; die Auswahl bleibt unverändert. true, wenn eine Geste lief. */
    fun end(): Boolean {
        val was = anchor != null
        anchor = null
        return was
    }
}
