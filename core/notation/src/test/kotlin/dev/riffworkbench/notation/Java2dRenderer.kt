package dev.riffworkbench.notation

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Test-only renderer: draws a ScoreLayout with Java2D so layouts can be inspected as PNG files. */
object Java2dRenderer {
    private fun color(ink: Ink): Color = when (ink) {
        Ink.STAFF -> Color(150, 150, 150); Ink.NOTE -> Color(20, 20, 20); Ink.MUTED -> Color(150, 150, 150); Ink.ACCENT -> Color(60, 90, 20)
        Ink.SELECTION -> Color(80, 120, 20); Ink.PLAYHEAD -> Color(200, 60, 40); Ink.TEXT -> Color(40, 40, 40); Ink.SECTION -> Color(76, 102, 17)
        Ink.ON_SECTION -> Color.WHITE; Ink.ERROR -> Color.RED; Ink.GRID -> Color(220, 220, 220)
    }
    fun render(layout: ScoreLayout, file: File, scale: Float = 2f, maxWidth: Float = 2400f) {
        val w = minOf(layout.width, maxWidth)
        val img = BufferedImage((w * scale).toInt().coerceAtLeast(1), (layout.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
        g.scale(scale.toDouble(), scale.toDouble())
        for (glyph in layout.glyphs) {
            if (glyph.minX > w) continue
            when (glyph) {
                is LineGlyph -> {
                    g.color = color(glyph.ink)
                    g.stroke = if (glyph.dashed) BasicStroke(glyph.width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(3f, 3f), 0f) else BasicStroke(glyph.width)
                    g.draw(java.awt.geom.Line2D.Float(glyph.x1, glyph.y1, glyph.x2, glyph.y2))
                }
                is RectGlyph -> {
                    g.color = color(glyph.ink)
                    val shape = RoundRectangle2D.Float(glyph.x, glyph.y, glyph.w, glyph.h, glyph.radius * 2, glyph.radius * 2)
                    if (glyph.fill) g.fill(shape) else { g.stroke = BasicStroke(glyph.strokeWidth); g.draw(shape) }
                }
                is TextGlyph -> {
                    g.font = Font(Font.SANS_SERIF, (if (glyph.bold) Font.BOLD else Font.PLAIN) or (if (glyph.italic) Font.ITALIC else 0), 1).deriveFont(glyph.size)
                    val fm = g.fontMetrics
                    val tw = fm.stringWidth(glyph.text).toFloat()
                    val x = when (glyph.align) { Align.START -> glyph.x; Align.CENTER -> glyph.x - tw / 2; Align.END -> glyph.x - tw }
                    if (glyph.halo) { g.color = Color.WHITE; g.fill(java.awt.geom.Rectangle2D.Float(x - 1f, glyph.y - glyph.size * 0.78f, tw + 2f, glyph.size * 0.92f)) }
                    g.color = color(glyph.ink)
                    g.drawString(glyph.text, x, glyph.y)
                }
                is OvalGlyph -> {
                    g.color = color(glyph.ink)
                    val old = g.transform
                    g.transform(AffineTransform.getRotateInstance(Math.toRadians(glyph.rotationDeg.toDouble()), glyph.cx.toDouble(), glyph.cy.toDouble()))
                    val e = Ellipse2D.Float(glyph.cx - glyph.rx, glyph.cy - glyph.ry, glyph.rx * 2, glyph.ry * 2)
                    if (glyph.fill) g.fill(e) else { g.stroke = BasicStroke(glyph.strokeWidth); g.draw(e) }
                    g.transform = old
                }
                is PathGlyph -> {
                    g.color = color(glyph.ink)
                    val path = Path2D.Float(); val o = glyph.ops; var i = 0
                    while (i < o.size) when (o[i].toInt()) {
                        0 -> { path.moveTo(o[i + 1], o[i + 2]); i += 3 }
                        1 -> { path.lineTo(o[i + 1], o[i + 2]); i += 3 }
                        2 -> { path.curveTo(o[i + 1], o[i + 2], o[i + 3], o[i + 4], o[i + 5], o[i + 6]); i += 7 }
                        else -> { path.closePath(); i += 1 }
                    }
                    if (glyph.fill) g.fill(path) else { g.stroke = BasicStroke(glyph.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(path) }
                }
            }
        }
        g.dispose()
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
    }
}
