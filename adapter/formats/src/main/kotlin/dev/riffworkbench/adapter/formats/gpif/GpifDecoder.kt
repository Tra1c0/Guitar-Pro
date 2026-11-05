package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlElement
import dev.riffworkbench.adapter.formats.xml.XmlReader
import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*

/** Master bar attributes that are resolved after all bars are read. */
internal class MasterBarPlan(
    val measure: Measure,
    val barIds: List<Int>,
    val section: String?,
    val targets: List<String>,
    val jumps: List<String>,
    val fermatas: List<Pair<Fraction, Fermata>>,
)

/** GPIF (score.gpif of GP6/GP7/GP8) -> Project. Shared by the .gp and .gpx importers. */
internal object GpifDecoder {
    const val MAX_TRACKS = 256
    const val MAX_MASTER_BARS = 20_000
    const val MAX_BARS = 400_000

    private val ROOT_CHILDREN = setOf("GPVersion", "GPRevision", "Encoding", "Score", "MasterTrack", "Tracks", "MasterBars", "Bars", "Voices", "Beats", "Notes", "Rhythms")
    private val MASTER_BAR_CHILDREN = setOf("Key", "Time", "Bars", "Repeat", "AlternateEndings", "Section", "Directions", "TripletFeel", "Fermatas")

    fun decode(gpif: ByteArray, partConfiguration: ByteArray?, ids: IdSource, losses: LossCollector): Project {
        val root = XmlReader.parse(gpif)
        if (root.name != "GPIF") corrupt("score.gpif hat kein GPIF-Wurzelelement")
        reportRoot(root, losses)
        val projectId = ids.next()
        val metadata = metadata(root.child("Score"), losses)

        val trackEls = orderedTracks(root)
        if (trackEls.isEmpty()) corrupt("score.gpif enthält keine Spuren")
        if (trackEls.size > MAX_TRACKS) resourceLimit("Mehr als $MAX_TRACKS Spuren")
        val partFlags = partConfiguration?.let { PartConfiguration.read(it) ?: run { losses.add("layout.stylesheet", "PartConfiguration ist unlesbar; Standardansicht wird verwendet"); null } }
        val plans = trackEls.mapIndexed { i, el -> GpifTracks.parse(el, i, partFlags?.getOrNull(i), losses) }

        val masterBarEls = root.child("MasterBars")?.children("MasterBar").orEmpty()
        if (masterBarEls.isEmpty()) corrupt("score.gpif enthält keine Takte")
        if (masterBarEls.size > MAX_MASTER_BARS) resourceLimit("Mehr als $MAX_MASTER_BARS Takte")
        if (masterBarEls.size.toLong() * plans.sumOf { it.staffCount } > MAX_BARS) resourceLimit("Partitur enthält mehr als $MAX_BARS Takte über alle Spuren")
        val masters = masterBarEls.map { masterBar(it, ids.next(), losses) }

        val index = GpifIndex(root, losses)
        val budget = ImportBudget()
        val texts = ArrayList<PendingText>()
        val chordIds = HashMap<Pair<Int, String>, EntityId>()
        val chordDefinitions = ArrayList<ChordDefinition>()
        val reader = BarReader(index, ids, losses, budget, { t, c ->
            chordIds[t to c] ?: plans[t].chords[c]?.let { info ->
                val id = ids.next()
                chordIds[t to c] = id
                val plan = plans[t]
                val frets = if (plan.stringed) plan.tuning.indices.map { info.frets[it] } else emptyList()
                val pitches = frets.mapIndexedNotNull { s, f -> f?.let { plan.pitch(s, it).takeIf { p -> p in 0..127 } } }
                chordDefinitions += ChordDefinition(id, info.name.ifEmpty { "Akkord" }.take(80), pitches, frets, firstFret = info.firstFret)
                id
            }
        }, texts)

        val assembly = GpifAssembly(plans, masters, losses)
        val trackIds = plans.map { ids.next() }
        val staffIds = plans.map { p -> List(p.staffCount) { ids.next() } }
        masters.forEachIndexed { m, master ->
            var k = 0
            val length = master.measure.signature.length()
            plans.forEachIndexed { t, plan ->
                repeat(plan.staffCount) { s ->
                    val ref = master.barIds.getOrNull(k++)
                    val barEl = ref?.takeIf { it >= 0 }?.let { index.bars[it.toString()] ?: run { losses.add("import.unknown_fields", "Fehlende Taktreferenz wird als leerer Takt übernommen"); null } }
                    assembly.add(t, s, reader.read(barEl, plan, t, m, length))
                }
            }
            if (master.barIds.size > k && master.barIds.drop(k).any { it >= 0 }) losses.add("import.unknown_fields", "Überzählige Taktreferenzen ohne Spur werden ignoriert")
        }
        val anacrusis = root.child("MasterTrack")?.child("Anacrusis") != null
        var measures = assembly.finish(anacrusis)
        val sections = masters.mapIndexedNotNull { m, master -> master.section?.let { Section(ids.next(), measures[m].id, it) } }
        measures = Navigation.resolve(measures, masters, losses)
        val tempo = tempo(root.child("MasterTrack"), measures, losses)
        val scoreTexts = texts.map { ScoreText(ids.next(), ScorePosition(measures[it.measureIndex].id, it.offset), it.text, it.kind) }

        val tracks = plans.mapIndexed { t, plan -> assembly.track(t, trackIds[t], staffIds[t], measures) }
        val project = Project(
            id = projectId, metadata = metadata, measures = measures, tracks = tracks, sections = sections, tempo = tempo,
            text = scoreTexts, chords = chordDefinitions, layout = LayoutSettings(trackOrder = trackIds),
        )
        val errors = ScoreValidator.validate(project).filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) corrupt("Konvertierte Partitur ist ungültig: ${errors.first().message} (${errors.size} Fehler)")
        return project
    }

    private fun orderedTracks(root: XmlElement): List<XmlElement> {
        val all = root.child("Tracks")?.children("Track").orEmpty()
        val byId = all.associateBy { it.attr("id") }
        val order = root.textOf("MasterTrack", "Tracks")?.let { runCatching { GpifNumbers.ints(it) }.getOrNull() }
        if (order != null && order.isNotEmpty() && order.distinct().size == order.size) {
            val listed = order.map { byId[it.toString()] }
            if (listed.all { it != null }) return listed.filterNotNull()
        }
        return all
    }

    private fun reportRoot(root: XmlElement, losses: LossCollector) {
        for (c in root.elements) if (c.name !in ROOT_CHILDREN) when (c.name) {
            "ScoreViews" -> losses.add("layout.multitrack", "Ansichtskonfiguration (ScoreViews) wird nicht übernommen")
            "BackingTrack", "AudioTracks", "Assets" -> if (c.elements.isNotEmpty() || c.text.isNotEmpty()) losses.add("audio.reference", "Eingebettetes Audio / Backing-Track (GP8) wird nicht übernommen")
            else -> losses.add("import.unknown_fields", "GPIF-Bereich '${c.name}' wird nicht übernommen")
        }
        val rse = root.child("MasterTrack")?.child("RSE")
        if (rse != null && rse.elements.isNotEmpty()) losses.add("sound.rse_mapping", "Master-Effekte (RSE) werden nicht übernommen")
    }

    private fun metadata(score: XmlElement?, losses: LossCollector): ProjectMetadata {
        fun t(name: String) = score?.textOf(name)?.trim().orEmpty().take(500)
        for (name in listOf("SubTitle", "Words", "Tabber", "Instructions")) if (t(name).isNotEmpty()) losses.add("projects.metadata", "Metadatenfeld $name wird nicht übernommen")
        if (listOf("FirstPageHeader", "FirstPageFooter", "PageHeader", "PageFooter", "ScoreSystemsLayout", "PageSetup").any { score?.child(it)?.let { e -> e.text.isNotBlank() || e.elements.isNotEmpty() } == true })
            losses.add("layout.page", "Seitenlayout (Kopf-/Fußzeilen, Systemumbrüche, Seiteneinrichtung) wird nicht übernommen")
        val music = t("Music").ifEmpty { t("WordsAndMusic") }
        return ProjectMetadata(title = t("Title").ifEmpty { "Guitar-Pro-Import" }, artist = t("Artist"), album = t("Album"), composer = music, copyright = t("Copyright"), notes = t("Notices"))
    }

    private fun masterBar(el: XmlElement, id: EntityId, losses: LossCollector): MasterBarPlan {
        for (c in el.elements) if (c.name !in MASTER_BAR_CHILDREN) when (c.name) {
            "DoubleBar" -> losses.add("signature.time", "Doppelstrich wird nicht übernommen")
            "FreeTime" -> losses.add("signature.time", "Freie Taktart wird nicht übernommen")
            else -> losses.add("import.unknown_fields", "Taktelement '${c.name}' wird nicht übernommen")
        }
        val time = el.textOf("Time")?.trim()?.split('/')
        val signature = runCatching { TimeSignature(time!![0].trim().toInt(), time[1].trim().toInt()) }.getOrElse {
            losses.add("signature.time", "Ungültige Taktart wird als 4/4 übernommen"); TimeSignature()
        }
        val accidentals = GpifNumbers.parseIntOrNull(el.textOf("Key", "AccidentalCount")) ?: 0
        if (accidentals !in -7..7) losses.add("signature.key", "Ungültige Tonart wird als C-Dur übernommen")
        val transposeAs = el.textOf("Key", "TransposeAs")?.trim()
        if (transposeAs != null && transposeAs != "Sharps") losses.add("signature.key", "Transpositionsvorzeichen ($transposeAs) werden nicht übernommen")
        val key = KeySignature(accidentals.takeIf { it in -7..7 } ?: 0, el.textOf("Key", "Mode")?.trim() == "Minor")
        val repeatEl = el.child("Repeat")
        val starts = repeatEl?.attr("start") == "true"
        val ends = repeatEl?.attr("end") == "true"
        val count = GpifNumbers.parseIntOrNull(repeatEl?.attr("count")) ?: 2
        if (count > 64) losses.add("repeats.basic", "Wiederholungsanzahl über 64 wird begrenzt")
        val endings = runCatching { GpifNumbers.ints(el.textOf("AlternateEndings")) }.getOrElse { emptyList() }
        if (endings.any { it !in 1..64 }) losses.add("repeats.endings", "Ungültige Nummern alternativer Enden werden ignoriert")
        val repeat = RepeatMark(starts, if (ends) count.coerceIn(2, 64) else null, endings.filter { it in 1..64 }.toSet())
        val feel = when (val f = el.textOf("TripletFeel")?.trim()) {
            null, "", "NoTripletFeel", "None" -> RhythmicFeel.STRAIGHT
            "Triplet8th" -> RhythmicFeel.SWING_EIGHTH
            "Triplet16th" -> RhythmicFeel.SWING_SIXTEENTH
            "Dotted8th", "Scottish8th" -> { losses.add("rhythm.swing", "Feel $f wird als Swing-Achtel angenähert"); RhythmicFeel.SWING_EIGHTH }
            "Dotted16th", "Scottish16th" -> { losses.add("rhythm.swing", "Feel $f wird als Swing-Sechzehntel angenähert"); RhythmicFeel.SWING_SIXTEENTH }
            else -> { losses.add("rhythm.swing", "Unbekanntes Feel $f wird ignoriert"); RhythmicFeel.STRAIGHT }
        }
        val letter = el.textOf("Section", "Letter")?.trim().orEmpty()
        val text = el.textOf("Section", "Text")?.trim().orEmpty()
        if (letter.isNotEmpty() && text.isNotEmpty()) losses.add("arrangement.sections", "Abschnittsbuchstabe wird nicht separat gespeichert (Text bleibt erhalten)")
        val section = (text.ifEmpty { letter }).takeIf { el.child("Section") != null }?.ifEmpty { "Abschnitt" }?.take(120)
        val directions = el.child("Directions")
        val fermatas = el.child("Fermatas")?.children("Fermata").orEmpty().mapNotNull { f -> fermata(f, losses) }
        val barIds = runCatching { GpifNumbers.ints(el.textOf("Bars")) }.getOrElse { corrupt("Ungültige Taktliste in score.gpif") }
        return MasterBarPlan(Measure(id, signature, key, repeat = repeat, feel = feel), barIds, section,
            directions?.children("Target").orEmpty().map { it.text.trim() }, directions?.children("Jump").orEmpty().map { it.text.trim() }, fermatas)
    }

    /** Fermata offsets are "num/den" quarter notes from the bar start (best knowledge); Length is the extra stretch. */
    private fun fermata(el: XmlElement, losses: LossCollector): Pair<Fraction, Fermata>? {
        val kind = when (el.textOf("Type")?.trim()) { "Short" -> FermataKind.SHORT; "Long" -> FermataKind.LONG; else -> FermataKind.MEDIUM }
        val parts = el.textOf("Offset")?.trim()?.split('/')
        val offset = runCatching { Fraction.of(parts!![0].trim().toLong(), parts[1].trim().toLong() * 4) }.getOrNull()
            ?: run { losses.add("rhythm.fermata", "Fermate ohne gültige Position wird verworfen"); return null }
        if (offset < Fraction.ZERO) return null
        val length = GpifNumbers.parseDoubleOrNull(el.textOf("Length"))?.takeIf { it >= 0.0 && it <= 7.0 }
        return offset to Fermata(kind, if (length != null) 1.0 + length else 1.5)
    }

    private fun tempo(masterTrack: XmlElement?, measures: List<Measure>, losses: LossCollector): List<TempoChange> {
        val result = LinkedHashMap<ScorePosition, TempoChange>()
        for (a in masterTrack?.child("Automations")?.children("Automation").orEmpty()) {
            when (val type = a.textOf("Type")?.trim()) {
                "Tempo" -> {
                    val bar = GpifNumbers.parseIntOrNull(a.textOf("Bar"))
                    val measure = bar?.let { measures.getOrNull(it) } ?: run { losses.add("tempo.changes", "Tempoänderung außerhalb der Takte wird verworfen"); null } ?: continue
                    val tokens = a.textOf("Value")?.trim()?.split(Regex("\\s+")).orEmpty()
                    val bpm = tokens.getOrNull(0)?.toDoubleOrNull()?.takeIf { it.isFinite() }
                    val reference = tokens.getOrNull(1)?.toIntOrNull() ?: 2
                    val factor = GpifUnits.quarterFactor(reference) ?: run { losses.add("tempo.changes", "Unbekannte Tempo-Bezugseinheit $reference; Viertel angenommen"); 1.0 }
                    val qpm = bpm?.times(factor)
                    if (qpm == null || qpm !in 10.0..1000.0) { losses.add("tempo.changes", "Ungültiges Tempo wird verworfen"); continue }
                    if (a.textOf("Linear")?.trim() == "true") losses.add("tempo.changes", "Lineare Tempoübergänge werden als sprunghafte Änderung übernommen")
                    if (!a.textOf("Text").isNullOrBlank()) losses.add("tempo.changes", "Tempobezeichnung (Text) wird nicht übernommen")
                    val ratio = (GpifNumbers.parseDoubleOrNull(a.textOf("Position")) ?: 0.0).coerceIn(0.0, 1.0)
                    val length = measure.pickupLength ?: measure.signature.length()
                    var offset = GpifNumbers.rational(ratio, 100_000, 1e-9) * length
                    if (offset >= length) offset = Fraction.ZERO.also { losses.add("tempo.changes", "Tempoänderung am Taktende wird an den Taktanfang gelegt") }
                    val position = ScorePosition(measure.id, offset)
                    result[position] = TempoChange(position, qpm)
                }
                "SyncPoint" -> losses.add("audio.sync", "Audio-Synchronisationspunkte (GP8) werden nicht übernommen")
                else -> losses.add("mix.automation", "Master-Automation '${type ?: "?"}' wird nicht übernommen")
            }
        }
        return result.values.toList()
    }
}

/** GPIF Directions <-> NavigationMark. Al-Coda/Al-Fine suffixes follow from the score's To-Coda/Fine marks. */
internal object Navigation {
    fun resolve(measures: List<Measure>, masters: List<MasterBarPlan>, losses: LossCollector): List<Measure> {
        val segno = masters.indexOfFirst { "Segno" in it.targets }.takeIf { it >= 0 }?.let { measures[it].id }
        val coda = masters.indexOfFirst { "Coda" in it.targets }.takeIf { it >= 0 }?.let { measures[it].id }
        val marked = measures.mapIndexed { m, measure ->
            val marks = ArrayList<NavigationMark>()
            for (t in masters[m].targets) when (t) {
                "Segno" -> marks += NavigationMark(NavigationKind.SEGNO)
                "Coda" -> marks += NavigationMark(NavigationKind.CODA)
                "Fine" -> marks += NavigationMark(NavigationKind.FINE)
                else -> losses.add("repeats.navigation", "Sprungziel $t (Doppel-Segno/-Coda) wird nicht unterstützt", measure.id)
            }
            for (j in masters[m].jumps) when (j) {
                "DaCapo", "DaCapoAlCoda", "DaCapoAlFine" -> marks += NavigationMark(NavigationKind.DA_CAPO)
                "DaSegno", "DaSegnoAlCoda", "DaSegnoAlFine" -> {
                    if (segno == null) losses.add("repeats.navigation", "Dal Segno ohne Segno", measure.id)
                    marks += NavigationMark(NavigationKind.DAL_SEGNO, segno)
                }
                "DaCoda" -> {
                    if (coda == null) losses.add("repeats.navigation", "To Coda ohne Coda", measure.id)
                    marks += NavigationMark(NavigationKind.TO_CODA, coda)
                }
                else -> losses.add("repeats.navigation", "Sprung $j (Doppel-Segno/-Coda) wird nicht unterstützt", measure.id)
            }
            measure.copy(navigation = marks)
        }
        val hasToCoda = marked.any { m -> m.navigation.any { it.kind == NavigationKind.TO_CODA } }
        val hasFine = marked.any { m -> m.navigation.any { it.kind == NavigationKind.FINE } }
        masters.forEachIndexed { m, master ->
            for (j in master.jumps) {
                val kind = when { j.startsWith("DaCapo") -> NavigationKind.DA_CAPO; j.startsWith("DaSegno") && !j.startsWith("DaSegnoSegno") -> NavigationKind.DAL_SEGNO; else -> null } ?: continue
                if (j.contains("Double")) continue
                val expected = jumpName(kind, hasToCoda, hasFine)
                if (expected != j) losses.add("repeats.navigation", "Sprung $j wird als $expected interpretiert", marked[m].id)
            }
        }
        return marked
    }

    fun jumpName(kind: NavigationKind, hasToCoda: Boolean, hasFine: Boolean): String {
        val base = if (kind == NavigationKind.DA_CAPO) "DaCapo" else "DaSegno"
        return base + when { hasToCoda -> "AlCoda"; hasFine -> "AlFine"; else -> "" }
    }
}
