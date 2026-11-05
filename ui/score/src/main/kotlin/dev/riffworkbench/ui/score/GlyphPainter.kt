package dev.riffworkbench.ui.score

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import dev.riffworkbench.notation.*

/** Colours for each semantic ink, as ARGB ints. */
class InkPalette(val colors: Map<Ink, Int>, val background: Int, val chips: IntArray? = null, val chipText: Int = 0xFF1A1620.toInt()) {
    fun of(ink: Ink) = colors[ink] ?: 0xFF000000.toInt()
    companion object {
        val PRINT = InkPalette(mapOf(Ink.STAFF to 0xFF8A8A8A.toInt(), Ink.NOTE to 0xFF141414.toInt(), Ink.TEXT to 0xFF141414.toInt(), Ink.MUTED to 0xFF9A9A9A.toInt(),
            Ink.ACCENT to 0xFF3C5A14.toInt(), Ink.SELECTION to 0xFF4C6611.toInt(), Ink.PLAYHEAD to 0xFFC83C28.toInt(), Ink.SECTION to 0xFF4C6611.toInt(),
            Ink.ON_SECTION to 0xFFFFFFFF.toInt(), Ink.ERROR to 0xFFC00000.toInt(), Ink.GRID to 0xFFCFCFCF.toInt()), 0xFFFFFFFF.toInt())
    }
}

/** Draws a display list on an android.graphics.Canvas. Used by the editor view, PDF and PNG export. */
class GlyphPainter(private val palette: InkPalette) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val dash = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    private val path = Path()
    private val rect = RectF()
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val italic = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
    private val boldItalic = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)

    /** Draws glyphs whose horizontal extent intersects [x0, x1] (layout units). */
    fun draw(canvas: Canvas, glyphs: List<Glyph>, x0: Float = -Float.MAX_VALUE, x1: Float = Float.MAX_VALUE) {
        for (g in glyphs) {
            if (g.maxX < x0 || g.minX > x1) continue
            when (g) {
                is LineGlyph -> {
                    stroke.color = palette.of(g.ink); stroke.strokeWidth = g.width; stroke.pathEffect = if (g.dashed) dash else null
                    stroke.strokeCap = if (g.dashed) Paint.Cap.BUTT else Paint.Cap.ROUND
                    canvas.drawLine(g.x1, g.y1, g.x2, g.y2, stroke)
                    stroke.pathEffect = null; stroke.strokeCap = Paint.Cap.ROUND
                }
                is RectGlyph -> {
                    rect.set(g.x, g.y, g.x + g.w, g.y + g.h)
                    val p = if (g.fill) fill else stroke.also { it.strokeWidth = g.strokeWidth }
                    p.color = palette.of(g.ink)
                    if (g.radius > 0f) canvas.drawRoundRect(rect, g.radius, g.radius, p) else canvas.drawRect(rect, p)
                }
                is TextGlyph -> {
                    text.textSize = g.size
                    text.typeface = when { g.bold && g.italic -> boldItalic; g.bold -> bold; g.italic -> italic; else -> Typeface.SANS_SERIF }
                    val w = text.measureText(g.text)
                    val left = when (g.align) { Align.START -> g.x; Align.CENTER -> g.x - w / 2; Align.END -> g.x - w }
                    val chips = palette.chips
                    if (g.chip >= 0 && chips != null && chips.isNotEmpty()) {
                        // Coloured fret pill: the colour names the string, as in the design's TAB.
                        val color = chips[g.chip % chips.size]
                        val h = g.size * 1.34f
                        val top = g.y - g.size * 0.36f - h / 2
                        val pad = g.size * 0.3f
                        val chipW = maxOf(w + 2 * pad, h)
                        val cx = left + w / 2
                        rect.set(cx - chipW / 2, top, cx + chipW / 2, top + h)
                        if (g.chipOutline) {
                            fill.color = palette.background; canvas.drawRoundRect(rect, h * 0.36f, h * 0.36f, fill)
                            stroke.color = color; stroke.strokeWidth = 1.6f; canvas.drawRoundRect(rect, h * 0.36f, h * 0.36f, stroke)
                            text.color = color
                        } else {
                            fill.color = color; canvas.drawRoundRect(rect, h * 0.36f, h * 0.36f, fill)
                            text.color = palette.chipText
                        }
                        canvas.drawText(g.text, left, g.y, text)
                        continue
                    }
                    if (g.halo) { fill.color = palette.background; canvas.drawRect(left - 1.5f, g.y - g.size * 0.8f, left + w + 1.5f, g.y + g.size * 0.14f, fill) }
                    text.color = palette.of(g.ink)
                    canvas.drawText(g.text, left, g.y, text)
                }
                is OvalGlyph -> {
                    rect.set(g.cx - g.rx, g.cy - g.ry, g.cx + g.rx, g.cy + g.ry)
                    val p = if (g.fill) fill else stroke.also { it.strokeWidth = g.strokeWidth }
                    p.color = palette.of(g.ink)
                    if (g.rotationDeg != 0f) { canvas.save(); canvas.rotate(g.rotationDeg, g.cx, g.cy); canvas.drawOval(rect, p); canvas.restore() } else canvas.drawOval(rect, p)
                }
                is PathGlyph -> {
                    path.reset()
                    val o = g.ops; var i = 0
                    while (i < o.size) when (o[i].toInt()) {
                        0 -> { path.moveTo(o[i + 1], o[i + 2]); i += 3 }
                        1 -> { path.lineTo(o[i + 1], o[i + 2]); i += 3 }
                        2 -> { path.cubicTo(o[i + 1], o[i + 2], o[i + 3], o[i + 4], o[i + 5], o[i + 6]); i += 7 }
                        else -> { path.close(); i += 1 }
                    }
                    val p = if (g.fill) fill else stroke.also { it.strokeWidth = g.strokeWidth }
                    p.color = palette.of(g.ink)
                    canvas.drawPath(path, p)
                }
            }
        }
    }
}
