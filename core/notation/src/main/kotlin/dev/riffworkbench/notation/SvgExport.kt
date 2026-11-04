package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import kotlin.math.roundToInt

/** C-FORMAT visual export: SVG from the same display list the app draws (light theme, system fonts). */
object SvgExport {
    private fun color(ink: Ink) = when (ink) {
        Ink.STAFF -> "#8a8a8a"; Ink.NOTE, Ink.TEXT -> "#141414"; Ink.MUTED -> "#9a9a9a"; Ink.ACCENT -> "#3c5a14"; Ink.SELECTION -> "#4c6611"
        Ink.PLAYHEAD -> "#c83c28"; Ink.SECTION -> "#4c6611"; Ink.ON_SECTION -> "#ffffff"; Ink.ERROR -> "#c00000"; Ink.GRID -> "#dddddd"
    }
    private fun f(v: Float): String { val r = (v * 100).roundToInt() / 100.0; return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString() }
    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** One SVG document with every requested track stacked, each laid out to [width] units. */
    fun render(project: Project, tracks: List<Int>, style: LayoutStyle = LayoutStyle.READ.copy(showStandard = true), width: Float = 760f): String {
        val parts = tracks.filter { it in project.tracks.indices }.map { project.tracks[it].name to ScoreLayouter.layout(LayoutRequest(project, it, style, width)) }
        val header = 64f
        val totalHeight = header + parts.sumOf { it.second.height.toDouble() + 28 }.toFloat()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${f(width)}\" height=\"${f(totalHeight)}\" viewBox=\"0 0 ${f(width)} ${f(totalHeight)}\" font-family=\"sans-serif\">\n")
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n")
        sb.append("<text x=\"${f(width / 2)}\" y=\"30\" font-size=\"20\" font-weight=\"bold\" text-anchor=\"middle\">${esc(project.metadata.title)}</text>\n")
        val by = listOf(project.metadata.artist, project.metadata.composer).filter { it.isNotBlank() }.joinToString(" · ")
        if (by.isNotEmpty()) sb.append("<text x=\"${f(width / 2)}\" y=\"50\" font-size=\"11\" text-anchor=\"middle\">${esc(by)}</text>\n")
        var y = header
        for ((name, layout) in parts) {
            sb.append("<text x=\"12\" y=\"${f(y + 12)}\" font-size=\"12\" font-weight=\"bold\">${esc(name)}</text>\n")
            sb.append("<g transform=\"translate(0 ${f(y + 18)})\">\n")
            for (g in layout.glyphs) sb.append(element(g)).append('\n')
            sb.append("</g>\n")
            y += layout.height + 28f
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    fun element(g: Glyph): String = when (g) {
        is LineGlyph -> "<line x1=\"${f(g.x1)}\" y1=\"${f(g.y1)}\" x2=\"${f(g.x2)}\" y2=\"${f(g.y2)}\" stroke=\"${color(g.ink)}\" stroke-width=\"${f(g.width)}\"" + (if (g.dashed) " stroke-dasharray=\"3 3\"" else "") + "/>"
        is RectGlyph -> "<rect x=\"${f(g.x)}\" y=\"${f(g.y)}\" width=\"${f(g.w)}\" height=\"${f(g.h)}\" rx=\"${f(g.radius)}\" " + (if (g.fill) "fill=\"${color(g.ink)}\"" else "fill=\"none\" stroke=\"${color(g.ink)}\" stroke-width=\"${f(g.strokeWidth)}\"") + "/>"
        is TextGlyph -> {
            val anchor = when (g.align) { Align.START -> "start"; Align.CENTER -> "middle"; Align.END -> "end" }
            val halo = if (g.halo) "<rect x=\"${f(g.minX - 1)}\" y=\"${f(g.y - g.size * 0.78f)}\" width=\"${f(g.width + 2)}\" height=\"${f(g.size * 0.92f)}\" fill=\"#ffffff\"/>" else ""
            halo + "<text x=\"${f(g.x)}\" y=\"${f(g.y)}\" font-size=\"${f(g.size)}\" text-anchor=\"$anchor\" fill=\"${color(g.ink)}\"" + (if (g.bold) " font-weight=\"bold\"" else "") + (if (g.italic) " font-style=\"italic\"" else "") + ">${esc(g.text)}</text>"
        }
        is OvalGlyph -> "<ellipse cx=\"${f(g.cx)}\" cy=\"${f(g.cy)}\" rx=\"${f(g.rx)}\" ry=\"${f(g.ry)}\" transform=\"rotate(${f(g.rotationDeg)} ${f(g.cx)} ${f(g.cy)})\" " + (if (g.fill) "fill=\"${color(g.ink)}\"" else "fill=\"none\" stroke=\"${color(g.ink)}\" stroke-width=\"${f(g.strokeWidth)}\"") + "/>"
        is PathGlyph -> {
            val d = StringBuilder(); val o = g.ops; var i = 0
            while (i < o.size) when (o[i].toInt()) {
                0 -> { d.append("M${f(o[i + 1])} ${f(o[i + 2])} "); i += 3 }
                1 -> { d.append("L${f(o[i + 1])} ${f(o[i + 2])} "); i += 3 }
                2 -> { d.append("C${f(o[i + 1])} ${f(o[i + 2])} ${f(o[i + 3])} ${f(o[i + 4])} ${f(o[i + 5])} ${f(o[i + 6])} "); i += 7 }
                else -> { d.append("Z "); i += 1 }
            }
            "<path d=\"${d.toString().trim()}\" " + (if (g.fill) "fill=\"${color(g.ink)}\"" else "fill=\"none\" stroke=\"${color(g.ink)}\" stroke-width=\"${f(g.strokeWidth)}\" stroke-linecap=\"round\" stroke-linejoin=\"round\"") + "/>"
        }
    }
}
