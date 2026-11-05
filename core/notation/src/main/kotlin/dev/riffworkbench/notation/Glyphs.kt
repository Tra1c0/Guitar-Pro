package dev.riffworkbench.notation

/** Semantic colours; renderers map them to the active theme. */
enum class Ink { STAFF, NOTE, MUTED, ACCENT, SELECTION, PLAYHEAD, TEXT, SECTION, ON_SECTION, ERROR, GRID }
enum class Align { START, CENTER, END }

/** Renderer-neutral drawing primitives in layout units (1 unit = 1 dp at zoom 1). */
sealed interface Glyph { val minX: Float; val maxX: Float }
data class LineGlyph(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val ink: Ink, val dashed: Boolean = false) : Glyph {
    override val minX get() = minOf(x1, x2); override val maxX get() = maxOf(x1, x2)
}
data class RectGlyph(val x: Float, val y: Float, val w: Float, val h: Float, val ink: Ink, val fill: Boolean = true, val radius: Float = 0f, val strokeWidth: Float = 1f) : Glyph {
    override val minX get() = x; override val maxX get() = x + w
}
/**
 * [y] is the text baseline. [halo] asks the renderer to clear the background behind the text (fret numbers on lines).
 * [chip] >= 0 marks a TAB fret: the string index counted from the highest string, so a renderer may draw a coloured
 * pill per string ([chipOutline] for dead notes). Renderers without chip colours fall back to the halo.
 */
data class TextGlyph(val x: Float, val y: Float, val text: String, val size: Float, val ink: Ink, val align: Align = Align.CENTER, val bold: Boolean = false, val italic: Boolean = false, val halo: Boolean = false, val chip: Int = -1, val chipOutline: Boolean = false) : Glyph {
    val width: Float get() = TextMetrics.width(text, size, bold)
    override val minX get() = when (align) { Align.START -> x; Align.CENTER -> x - width / 2; Align.END -> x - width }
    override val maxX get() = minX + width
}
data class OvalGlyph(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val rotationDeg: Float, val ink: Ink, val fill: Boolean, val strokeWidth: Float = 1.2f) : Glyph {
    override val minX get() = cx - rx - 1; override val maxX get() = cx + rx + 1
}
/** Path ops: 0 = moveTo(x,y), 1 = lineTo(x,y), 2 = cubicTo(x1,y1,x2,y2,x,y), 3 = close. */
class PathGlyph(val ops: FloatArray, val ink: Ink, val fill: Boolean, val strokeWidth: Float = 1.2f) : Glyph {
    override val minX: Float; override val maxX: Float
    init {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var i = 0
        while (i < ops.size) {
            val n = when (ops[i].toInt()) { 0, 1 -> 2; 2 -> 6; else -> 0 }
            var k = 0; while (k < n) { val x = ops[i + 1 + k]; if (x < lo) lo = x; if (x > hi) hi = x; k += 2 }
            i += 1 + n
        }
        minX = if (lo == Float.MAX_VALUE) 0f else lo; maxX = if (hi == -Float.MAX_VALUE) 0f else hi
    }
    class Builder {
        private val list = ArrayList<Float>()
        fun move(x: Float, y: Float) = apply { list += 0f; list += x; list += y }
        fun line(x: Float, y: Float) = apply { list += 1f; list += x; list += y }
        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = apply { list += 2f; list += x1; list += y1; list += x2; list += y2; list += x; list += y }
        fun close() = apply { list += 3f }
        fun build(ink: Ink, fill: Boolean, strokeWidth: Float = 1.2f) = PathGlyph(list.toFloatArray(), ink, fill, strokeWidth)
    }
}

/** Platform-independent text width estimate (sans-serif digits ≈ 0.56 em). Renderers center text, so small errors stay symmetric. */
object TextMetrics {
    fun width(text: String, size: Float, bold: Boolean = false): Float {
        var w = 0f
        for (c in text) w += when {
            c.isDigit() -> 0.56f; c == ' ' -> 0.28f; c == '(' || c == ')' || c == '<' || c == '>' -> 0.36f
            c.isUpperCase() -> 0.66f; c == '.' || c == ',' || c == '·' -> 0.28f; else -> 0.52f
        }
        return w * size * if (bold) 1.06f else 1f
    }
}
