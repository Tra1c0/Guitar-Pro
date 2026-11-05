package dev.riffworkbench.ui.score

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.*
import java.io.OutputStream

/** C-FORMAT PDF: A4 pages from the reading layout, systems never split across pages. 1 layout unit = 1 PDF point. */
object PdfExport {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f

    fun write(project: Project, tracks: List<Int>, out: OutputStream, style: LayoutStyle = LayoutStyle.READ.copy(showStandard = true)) {
        val doc = PdfDocument()
        val painter = GlyphPainter(InkPalette.PRINT)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; typeface = Typeface.SANS_SERIF; textAlign = Paint.Align.CENTER }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            y = MARGIN
            page!!.canvas.drawText("${project.metadata.title} · $pageNumber", PAGE_W / 2f, PAGE_H - 18f, small)
        }
        try {
            newPage()
            page!!.canvas.drawText(project.metadata.title, PAGE_W / 2f, y + 18f, title)
            val by = listOf(project.metadata.artist, project.metadata.composer).filter { it.isNotBlank() }.joinToString(" · ")
            if (by.isNotEmpty()) page!!.canvas.drawText(by, PAGE_W / 2f, y + 34f, small)
            y += 48f
            for (t in tracks.filter { it in project.tracks.indices }) {
                val layout = ScoreLayouter.layout(LayoutRequest(project, t, style, width = PAGE_W - 2 * MARGIN, margin = 0f))
                // Keep the heading with the first system (it was left alone at a page bottom).
                val firstHeight = layout.systems.firstOrNull()?.let { it.bottom - it.top } ?: 22f
                if (y + 18f + firstHeight > PAGE_H - MARGIN - 12f) newPage()
                page!!.canvas.drawText(project.tracks[t].name, MARGIN, y + 10f, heading)
                y += 18f
                for ((index, system) in layout.systems.withIndex()) {
                    val h = system.bottom - system.top
                    if (y + h > PAGE_H - MARGIN - 12f) newPage()
                    val c = page!!.canvas
                    c.save()
                    c.translate(MARGIN, y - system.top)
                    c.clipRect(-MARGIN, system.top - 2f, PAGE_W.toFloat(), system.bottom)
                    painter.draw(c, layout.glyphsBySystem[index])
                    c.restore()
                    y += h
                }
                y += 12f
            }
            page?.let { doc.finishPage(it) }
            page = null
            doc.writeTo(out)
        } finally { doc.close() }
    }

}
