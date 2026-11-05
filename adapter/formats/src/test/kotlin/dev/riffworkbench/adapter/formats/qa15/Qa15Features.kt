package dev.riffworkbench.adapter.formats.qa15

import dev.riffworkbench.model.*

/** Feature-level, ID-free view of a project: "category|location" -> value. */
object Qa15Features {
    fun of(p: Project, includeRests: Boolean = false, includeMix: Boolean = true): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val path = HashMap<EntityId, String>()
        val mIndex = HashMap<EntityId, Int>()
        p.measures.forEachIndexed { m, x -> path[x.id] = "m$m"; mIndex[x.id] = m }
        p.tracks.forEachIndexed { t, track -> track.staves.forEachIndexed { s, st -> st.bars.forEach { bar ->
            val m = mIndex[bar.measureId] ?: -1
            bar.voices.forEach { v -> v.beats.forEach { b ->
                val bp = "t$t/s$s/m$m/v${v.index}/@${b.offset}"
                path[b.id] = bp
                b.notes.forEach { n -> path[n.id] = "$bp/${key(if (track.instrument.kind == InstrumentKind.PERCUSSION) drumSounding(n) else n)}" }
            } }
        } } }
        fun ref(id: EntityId?) = id?.let { path[it] ?: "?" } ?: "-"
        val md = p.metadata
        out["meta.title|"] = md.title; out["meta.artist|"] = md.artist; out["meta.album|"] = md.album
        out["meta.composer|"] = md.composer; out["meta.copyright|"] = md.copyright; out["meta.notes|"] = md.notes
        out["measure.count|"] = p.measures.size.toString()
        p.measures.forEachIndexed { m, x ->
            out["measure.signature|m$m"] = "${x.signature.numerator}/${x.signature.denominator}"
            out["measure.key|m$m"] = "${x.key.fifths}${if (x.key.minor) "m" else ""}"
            if (x.repeat.starts) out["repeat.start|m$m"] = "1"
            x.repeat.endCount?.let { out["repeat.end|m$m"] = it.toString() }
            if (x.repeat.alternateEndings.isNotEmpty()) out["repeat.endings|m$m"] = x.repeat.alternateEndings.sorted().toString()
            // Only D.S. and To Coda use a target (playback RepeatCompiler / GpifEncoder.directions).
            if (x.navigation.isNotEmpty()) out["navigation|m$m"] = x.navigation.map { "${it.kind}->${if (it.kind == NavigationKind.DAL_SEGNO || it.kind == NavigationKind.TO_CODA) ref(it.target) else "-"}" }.sorted().toString()
            if (x.feel != RhythmicFeel.STRAIGHT) out["measure.feel|m$m"] = x.feel.name
            x.pickupLength?.let { out["measure.pickup|m$m"] = it.toString() }
            if (x.forcedSystemBreak) out["measure.break|m$m"] = "1"
        }
        // A tempo marking equal to the tempo already in force changes nothing.
        var current: Double? = null
        p.tempo.sortedWith(compareBy({ mIndex[it.position.measureId] ?: -1 }, { it.position.offset })).forEach {
            if (it.quarterNotesPerMinute != current) out["tempo|${ref(it.position.measureId)}@${it.position.offset}"] = "%.4f".format(it.quarterNotesPerMinute)
            current = it.quarterNotesPerMinute
        }
        p.sections.groupBy { ref(it.measureId) }.forEach { (m, ss) ->
            out["section|$m"] = ss.map { it.label }.sorted().toString()
            out["section.color|$m"] = ss.map { it.color }.sorted().toString()
        }
        p.text.groupBy { "${ref(it.position.measureId)}@${it.position.offset}" }.forEach { (at, ts) ->
            out["text|$at"] = ts.map { "${it.kind}:${it.text}" }.sorted().toString()
            out["text.style|$at"] = ts.map { it.style.toString() }.sorted().toString()
        }
        out["track.count|"] = p.tracks.size.toString()
        p.tracks.forEachIndexed { t, track ->
            val i = track.instrument
            out["track.name|t$t"] = track.name
            out["track.kind|t$t"] = i.kind.name
            out["track.tuning|t$t"] = i.tuning.toString()
            out["track.capo|t$t"] = i.capoByString.toString()
            out["track.transposition|t$t"] = i.transposition.toString()
            out["track.program|t$t"] = i.midiProgram.toString()
            out["track.maxFret|t$t"] = i.maxFret.toString()
            out["track.fineTune|t$t"] = i.fineTuneCents.toString()
            if (i.kind == InstrumentKind.PERCUSSION) out["track.drumKit|t$t"] = i.drumKit.toString()
            out["track.notation|t$t"] = track.staves.map { it.notation.sorted() }.toString()
            if (includeMix) { out["track.mix|t$t"] = track.mix.toString(); out["track.sound|t$t"] = track.sound.toString() }
            track.staves.forEachIndexed { s, st -> st.bars.forEach { bar -> bar.voices.forEach { v -> v.beats.forEach { b ->
                val bp = path[b.id]!!
                if (b.notes.isEmpty() && !includeRests) { b.fermata?.let { f -> val k = "beat.fermata|m${mIndexOf(bp)}@${b.offset}"; out[k] = ((out[k]?.split(",") ?: emptyList()) + "${f.kind}x${f.multiplier}").distinct().sorted().joinToString(",") }; return@forEach }
                out[(if (b.notes.isEmpty()) "rest|" else "beat|") + bp] = durationText(b.duration)
                // Fermatas are compared per measure position (GP7 stores them on the master bar).
                b.fermata?.let { f -> val k = "beat.fermata|m${mIndexOf(bp)}@${b.offset}"; out[k] = ((out[k]?.split(",") ?: emptyList()) + "${f.kind}x${f.multiplier}").distinct().sorted().joinToString(",") }
                b.strum?.let { out["beat.strum|$bp"] = "${it.kind}/${it.direction}/${it.spread}" }
                if (b.chordId != null) out["beat.chord|$bp"] = "1"
                b.notes.forEach { n0 ->
                    // Drum techniques that select another kit piece are compared by the piece that sounds (SequenceCompiler.percussion).
                    val n = if (i.kind == InstrumentKind.PERCUSSION) drumSounding(n0).let { d ->
                        // Percussion GHOST and the GHOST mark act the same on drums (both lower the velocity).
                        if (d.articulations.any { it is Articulation.Percussion && it.kind == PercussionTechnique.GHOST }) d.copy(articulations = d.articulations.filterNot { it is Articulation.Percussion && it.kind == PercussionTechnique.GHOST }.plus(Articulation.Mark(NoteMark.GHOST)).distinct()) else d
                    } else n0
                    val np = "$bp/${key(n)}"
                    out["note|$np"] = "1"
                    out["note.pitch|$np"] = n.pitch.midiNumber.toString()
                    if (n.pitch.cents != 0) out["note.cents|$np"] = n.pitch.cents.toString()
                    n.pitch.spelling?.let { out["note.spelling|$np"] = it }
                    out["note.string|$np"] = n.string.toString()
                    out["note.fret|$np"] = n.fret.toString()
                    // Dynamic overrides the velocity in playback (SequenceCompiler): compare the effective velocity.
                    val dyn = n.articulations.filterIsInstance<Articulation.Dynamic>().firstOrNull()
                    out["note.velocity|$np"] = (dyn?.let { dev.riffworkbench.playback.ScorePlaybackCompiler.dynamicVelocity(it.level) } ?: n.velocity).toString()
                    n.fingering?.let { out["note.fingering|$np"] = it.toString() }
                    // Percussion techniques only act on percussion tracks.
                    // Grace positions are only meaningful on fretted tracks and when they match the tuning (stale after SetInstrument).
                    n.articulations.filterIsInstance<Articulation.Grace>().map { g -> if (g.string == null || g.fret == null || g.string !in i.tuning.indices || i.pitch(g.string!!, g.fret!!) != g.pitch.midiNumber) g.copy(string = null, fret = null) else g }.forEach { g -> val k = "beat.grace|$bp"; out[k] = ((out[k]?.split(";") ?: emptyList()) + g.toString()).sorted().joinToString(";") }
                    n.articulations.filterNot { it is Articulation.Grace || it is Articulation.Dynamic || (it is Articulation.Percussion && it.kind == PercussionTechnique.NORMAL && it.chokeGroup == null) || (it is Articulation.Percussion && i.kind != InstrumentKind.PERCUSSION) }.map(::canonicalCurve).groupBy { slotOf(it) }.forEach { (slot, xs) ->
                        // Only SHIFT/LEGATO slides go to a target note.
                        out["art.$slot|$np"] = xs.map { a -> if (a is Articulation.Slide) "Slide(${a.kind},${if (a.kind == SlideKind.SHIFT || a.kind == SlideKind.LEGATO) ref(a.target) else "-"})" else a.toString() }.sorted().toString()
                    }
                    n.links.groupBy { it.kind }.forEach { (k, ls) -> out["link.${k.name.lowercase()}|$np"] = ls.map { ref(it.target) }.sorted().toString() }
                }
            } } } }
        }
        return out
    }

    /** A final curve point that only holds the previous value carries no information. */
    fun canonicalCurve(a: Articulation): Articulation {
        // Positions to 1e-4 of the note: MusicXML first-beat/last-beat carry percent with two decimals.
        fun trim(ps: List<PitchCurvePoint>): List<PitchCurvePoint> { val q = ps.sortedBy { it.position }.map { PitchCurvePoint(Fraction.of(Math.round(it.position.toDouble() * 10000), 10000), it.cents) }; return if (q.size >= 2 && q.last().cents == q[q.size - 2].cents) trim(q.dropLast(1)) else q }
        return when (a) { is Articulation.Bend -> a.copy(points = trim(a.points)); is Articulation.Whammy -> a.copy(points = trim(a.points)); else -> a }
    }

    /** Normalisation of what a format legitimately adds or cannot carry (documented). */
    fun normalize(format: String, orig: Map<String, String>, back: Map<String, String>): Pair<Map<String, String>, Map<String, String>> {
        val a = LinkedHashMap(orig); val b = LinkedHashMap(back)
        for (k in b.keys.toList()) {
            val cat = k.substringBefore('|')
            // Exporters spell pitches from the key; an importer keeping that spelling adds, not loses, information.
            if (cat == "note.spelling" && a[k] == null) b.remove(k)
            // Importers fill default sound mapping descriptions / used drum kit elements.
            if (cat == "track.sound" && a[k] == SoundAssignment().toString()) b.remove(k).also { a.remove(k) }
        }
        for (k in a.keys.toList()) {
            val cat = k.substringBefore('|')
            // Harmonic node derived on import from the fret.
            if (cat == "art.harmonic" && b[k] != null && b[k]!!.replace(Regex("node=[0-9/]+"), "node=null") == a[k]) b[k] = a[k]!!
            // A HAMMER_ON/PULL_OFF mark means "to the next note on this string"; formats store it as a link.
            // (GP7 derives hammer-on vs pull-off from the pitch direction.)
            for (kind in listOf("hammer_on", "pull_off")) if (cat == "art.mark.$kind" && b[k] == null) {
                val loc = k.substringAfter('|')
                val link = listOf("link.hammer_on|$loc", "link.pull_off|$loc").firstOrNull { b[it] != null && a[it] == null }
                if (link != null) { a.remove(k); b.remove(link) }
            }
            // A SHIFT/LEGATO slide or D.S./To Coda without target means "next note" / "first segno or coda"; an
            // importer that resolves it adds information.
            if ((cat == "art.slide" || cat == "navigation") && b[k] != null && a[k]!!.contains("->-") || cat == "art.slide" && b[k] != null && a[k]!!.contains(",-)")) {
                if (b[k]!!.replace(Regex("->m\\d+"), "->-").replace(Regex(",t[0-9/@smpv]+\\)"), ",-)") == a[k]) b[k] = a[k]!!
            }
            if (cat == "track.drumKit" && b[k] != null && b[k]!!.startsWith(a[k]!!.removeSuffix("]"))) b[k] = a[k]!!
            // MusicXML: fretted tracks are always written as standard + TAB staff (exporter KDoc).
            if (cat == "track.notation" && (format == "musicxml" || format == "mxl")) { a.remove(k); b.remove(k) }
            // Journal cycle 2 (known): DEAD/PULL_OFF on drum notes are dropped by MusicXML.
            if ((cat == "art.mark.dead" || cat == "art.mark.pull_off") && (format == "musicxml" || format == "mxl") && k.substringAfter('|').substringAfterLast('/').startsWith("p")) b[k] = a[k]!!
            // Journal cycle 2 (known): MusicXML import derives BASS from a track name "Bass".
            if (cat == "track.kind" && (format == "musicxml" || format == "mxl") && b[k] == "BASS" && orig["track.name|" + k.substringAfter('|')]?.contains("Bass") == true) b[k] = a[k]!!
        }
        return a to b
    }

    fun drumSounding(n: Note): Note {
        val perc = n.articulations.filterIsInstance<Articulation.Percussion>().firstOrNull() ?: return n
        val midi = when (perc.kind) { PercussionTechnique.OPEN_HI_HAT -> 46; PercussionTechnique.CLOSED_HI_HAT -> 42; PercussionTechnique.PEDAL_HI_HAT -> 44; PercussionTechnique.CROSS_STICK -> 37; else -> return n }
        return n.copy(pitch = n.pitch.copy(midiNumber = midi), articulations = n.articulations.filterNot { it === perc })
    }

    private fun mIndexOf(bp: String) = bp.split("/")[2].removePrefix("m")

    fun key(n: Note) = n.string?.let { "s$it" } ?: "p${n.pitch.midiNumber}"
    fun durationText(d: WrittenDuration) = "1/${d.denominator}${".".repeat(d.dots)}${if (d.tuplets.isEmpty()) "" else d.tuplets.joinToString("") { "[${it.actual}:${it.normal}]" }}"

    fun slotOf(a: Articulation): String = when (a) {
        is Articulation.Mark -> "mark.${a.kind.name.lowercase()}"
        is Articulation.Bend -> "bend"; is Articulation.Whammy -> "whammy"; is Articulation.Slide -> "slide"
        is Articulation.Harmonic -> "harmonic"; is Articulation.Vibrato -> "vibrato"; is Articulation.Trill -> "trill"
        is Articulation.TremoloPicking -> "tremolo"; is Articulation.Grace -> "grace"; is Articulation.Dynamic -> "dynamic"
        is Articulation.Pickstroke -> "pickstroke"; is Articulation.Percussion -> "percussion"
    }

    data class Diff(val category: String, val key: String, val expected: String?, val actual: String?) {
        override fun toString() = "$key: expected=${expected?.take(160)} actual=${actual?.take(160)}"
    }

    /** Field-level diff; children of a missing/extra beat or note are folded into that one entry. */
    fun diff(a: Map<String, String>, b: Map<String, String>): List<Diff> {
        val out = ArrayList<Diff>()
        val keys = LinkedHashSet<String>().apply { addAll(a.keys); addAll(b.keys) }
        val missingParents = HashSet<String>()
        for (k in keys) {
            val cat = k.substringBefore('|'); val loc = k.substringAfter('|')
            if ((cat == "beat" || cat == "rest" || cat == "note") && (a[k] == null || b[k] == null)) missingParents += loc
        }
        for (k in keys) {
            val x = a[k]; val y = b[k]
            if (x == y) continue
            val cat = k.substringBefore('|'); val loc = k.substringAfter('|')
            val primary = cat == "beat" || cat == "rest" || cat == "note"
            val parentMissing = missingParents.any { it != loc && loc.startsWith("$it/") } || (!primary && loc in missingParents)
            if (parentMissing) continue
            val category = when {
                (cat == "beat" || cat == "rest") && x != null && y != null -> "$cat.duration"
                x == null && (cat == "beat" || cat == "note" || cat == "rest") -> "$cat.extra"
                y == null && (cat == "beat" || cat == "note" || cat == "rest") -> "$cat.missing"
                else -> cat
            }
            out += Diff(category, k, x, y)
        }
        return out
    }
}
