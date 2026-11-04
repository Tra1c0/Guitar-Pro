package dev.riffworkbench.adapter.alphatab

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

/** Spike A owns AndroidView, stable-ID mapping, includeNoteBounds, model finish/relink and revision invalidation. */
class AlphaTabBoundary : NotationRenderer, HitTestProvider {
    override suspend fun render(project: Project, widthPx: Int): Outcome<RenderedScore> = unavailable("C-NOTATION", "alphaTab wird im Spike A integriert. Aktuell ist die einfache TAB-Ansicht aktiv.")
    override fun hitTest(score: RenderedScore, x: Float, y: Float) = score.elements.lastOrNull { x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height }?.entityId
}
