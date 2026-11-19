package dev.riffworkbench.editor

import dev.riffworkbench.model.*

/**
 * Zyklus 30 (user decision, replaces the Zyklus-25 refusal „Haltebogen würde unterbrochen“): shortening a tied origin, or
 * deleting a tied continuation as a beat, releases the tie at the origin and turns the continuation(s) into rests — nothing
 * moves. A continuation chain (origin → c1 → c2 …) goes as a whole; a beat that keeps no note becomes an explicit rest of
 * the same duration, other notes of a chord stay. Pure (project → project), applied inside one editor command, so it is
 * one undo step.
 */
internal object TieRelease {
    private inline fun forEachNote(t: Track, f: (Beat, Note) -> Unit) { for (st in t.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) f(b, n) }

    /** Notes reached from [origins] over TIE links (transitively), without the origins themselves. */
    fun continuations(t: Track, origins: Set<EntityId>): Set<EntityId> {
        val next = HashMap<EntityId, List<EntityId>>()
        forEachNote(t) { _, n -> val ties = n.links.filter { it.kind == LinkKind.TIE }.map { it.target }; if (ties.isNotEmpty()) next[n.id] = ties }
        val out = LinkedHashSet<EntityId>()
        val queue = ArrayDeque(origins.flatMap { next[it].orEmpty() })
        while (queue.isNotEmpty()) { val id = queue.removeFirst(); if (id in origins || !out.add(id)) continue; queue.addAll(next[id].orEmpty()) }
        return out
    }

    /** Notes of [t] that tie into one of [notes]. */
    fun origins(t: Track, notes: Set<EntityId>): Set<EntityId> {
        val out = HashSet<EntityId>()
        forEachNote(t) { _, n -> if (n.links.any { it.kind == LinkKind.TIE && it.target in notes }) out += n.id }
        return out
    }

    /** Drops the TIE links of [origins] in track [trackId] and turns everything they continued into rests. */
    fun release(p: Project, trackId: EntityId, origins: Set<EntityId>): Project {
        val track = p.tracks.firstOrNull { it.id == trackId } ?: return p
        val removed = continuations(track, origins)
        if (removed.isEmpty() && origins.none { o -> var tied = false; forEachNote(track) { _, n -> if (n.id == o && n.links.any { it.kind == LinkKind.TIE }) tied = true }; tied }) return p
        val edited = p.copy(tracks = p.tracks.map { t -> if (t.id != trackId) t else t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.map { bar ->
            bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { b ->
                if (b.notes.none { it.id in origins || it.id in removed }) b else {
                    val kept = b.notes.filter { it.id !in removed }.map { n -> if (n.id in origins) n.copy(links = n.links.filterNot { it.kind == LinkKind.TIE }) else n }
                    b.copy(notes = kept, explicitRest = kept.isEmpty() || b.explicitRest, strum = if (kept.isEmpty()) null else b.strum)
                }
            }) })
        }) }) })
        return Links.prune(edited, removed)
    }
}
