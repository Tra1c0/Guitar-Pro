package dev.riffworkbench.adapter.formats.qa15

import dev.riffworkbench.adapter.formats.ascii.AsciiTabExporter
import dev.riffworkbench.adapter.formats.gpif.Gp7Exporter
import dev.riffworkbench.adapter.formats.gpif.GpifImporter
import dev.riffworkbench.adapter.formats.midi.MidiExporter
import dev.riffworkbench.adapter.formats.midi.MidiImporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlExporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlImporter
import dev.riffworkbench.data.local.FileAssetStore
import dev.riffworkbench.data.local.FileNativeProjectExchange
import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 15: generative differential round-trip test of the format adapters ("loss honesty").
 * For every exporter with an importer: export -> import -> feature diff against the original.
 * L1 every difference explained by an export loss (or a documented limit), L2 no exception, L3 no ERROR on the import
 * of our own export, L4 export(import(x)) imports to the same features as import(x).
 * -Dqa15.n=1000 -Dqa15.seed=… -Dqa15.formats=gp7,musicxml,mxl,midi,native,ascii -Dqa15.shrink=true
 */
class Qa15FormatRoundTripTest {
    data class Finding(val format: String, val inv: String, val category: String, val detail: String) {
        val signature get() = "$format/$inv/$category"
    }

    companion object {
        private val tmp = java.nio.file.Files.createTempDirectory("qa15assets").toFile()

        /**
         * Category -> export loss feature ids that explain a difference in that category (format-specific).
         * A difference is "explained" if any of these losses was reported by the export.
         */
        val explain: Map<String, Map<String, Set<String>>> = mapOf(
            "gp7" to mapOf(
                "track.mix" to setOf("mix.channels", "mix.eq_fx"),
                "track.sound" to setOf("sound.rse_mapping"),
                "track.fineTune" to setOf("sound.fine_tuning"),
                "track.transposition" to setOf("notes.transpose"),
                "track.tuning" to setOf("notes.transpose"),
                "track.maxFret" to setOf("notes.fret"),
                "track.kind" to setOf("band.instruments"),
                "track.drumKit" to setOf("drums.element_mix", "drums.choke"),
                "track.capo" to setOf("strings.capo"),
                "measure.feel" to setOf("rhythm.swing"),
                "measure.break" to setOf("layout.breaks"),
                "measure.pickup" to setOf("rhythm.pickup"),
                "measure.key" to setOf("signature.key"),
                "navigation" to setOf("repeats.navigation"),
                "repeat.end" to setOf("repeats.basic"),
                "section.color" to setOf("arrangement.sections"),
                "section" to setOf("arrangement.sections"),
                "text" to setOf("layout.text_lyrics"), "text.style" to setOf("layout.text_lyrics"),
                "beat.fermata" to setOf("rhythm.fermata"),
                "beat.strum" to setOf("fx.strum", "fx.rasgueado"),
                "beat.chord" to setOf("layout.chord_symbols"),
                "note.velocity" to setOf("fx.dynamics"),
                "art.dynamic" to setOf("fx.dynamics"),
                "note.cents" to setOf("fx.quartertone"), "note.pitch" to setOf("strings.capo", "notes.transpose"),
                "art.mark.slap" to setOf("fx.slap_pop"), "art.pickstroke" to setOf("fx.pickstroke"), "art.mark.pop" to setOf("fx.slap_pop"),
                "note.spelling" to setOf("notation.standard"),
                "note.fingering" to setOf("notes.fingering"),
                "art.bend" to setOf("fx.bend"), "art.whammy" to setOf("fx.whammy"),
                "art.harmonic" to setOf("fx.harmonic_natural"),
                "art.vibrato" to setOf("fx.vibrato"), "art.trill" to setOf("fx.trill", "fx.vibrato", "strings.capo"),
                "beat.missing" to setOf("rhythm.rest", "rhythm.duration"), "beat.extra" to setOf("rhythm.rest", "rhythm.duration"),
                "art.tremolo" to setOf("fx.tremolo"), "art.grace" to setOf("rhythm.grace"), "beat.grace" to setOf("rhythm.grace"),
                "art.slide" to setOf("fx.slide"),
                "link.tie" to setOf("rhythm.tie"), "link.slur" to setOf("rhythm.slur"),
                "link.hammer_on" to setOf("fx.hammer_pull"), "link.pull_off" to setOf("fx.hammer_pull"), "link.legato_slide" to setOf("fx.slide"),
                "art.mark.hammer_on" to setOf("fx.hammer_pull"), "art.mark.pull_off" to setOf("fx.hammer_pull"),
                "art.percussion" to setOf("drums.hihat", "drums.choke"),
                "note.missing" to setOf("notes.fret", "rhythm.grace"),
                "beat.duration" to setOf("rhythm.duration", "rhythm.dots", "rhythm.tuplets"),
            ),
            "musicxml" to mapOf(
                "track.capo" to setOf("partial_capo"), "track.tuning" to setOf("partial_capo", "instrument_transposition"),
                "track.transposition" to setOf("instrument_transposition"),
                "track.fineTune" to setOf("fine_tune"), "track.mix" to setOf("track_mute", "effects"),
                "track.sound" to setOf("sound_assignment"), "track.drumKit" to setOf("drum_kit"),
                "track.maxFret" to setOf("max_fret"),
                "beat.chord" to setOf("chord_symbols"), "art.grace" to setOf("grace_multiple", "grace_tuplet"), "beat.grace" to setOf("grace_multiple", "grace_tuplet"),
                "beat.strum" to setOf("strum_kind", "strum_spread", "strum"), "beat.fermata" to setOf("fermata_length"),
                "art.harmonic" to setOf("harmonic_pitch"), "art.percussion" to setOf("drum_choke"),
                "note.fingering" to setOf("fingering_alternative"), "art.vibrato" to setOf("vibrato_params"),
                "art.trill" to setOf("trill_interval", "trill_rate"), "art.tremolo" to setOf("tremolo_rate"),
                // Loss codes added by the QA15 fixes (F5 navigation, F6 text_kind, F7 hammer_pull/slide/slur, F8 strum,
                // F12 trill_rate) explain the matching categories.
                "text" to setOf("lyrics", "text_kind"), "text.style" to setOf("lyrics"),
                "navigation" to setOf("navigation"),
                "art.mark.hammer_on" to setOf("hammer_pull"), "art.mark.pull_off" to setOf("hammer_pull"),
                "link.hammer_on" to setOf("hammer_pull"), "link.pull_off" to setOf("hammer_pull"),
                "art.slide" to setOf("slide"), "link.legato_slide" to setOf("slide", "slur"), "link.slur" to setOf("slur"), "measure.pickup" to setOf("irregular_measure"),
                "beat.duration" to setOf("divisions"), "note.missing" to setOf("tab_position"), "note.extra" to setOf("tab_position"), "beat.missing" to setOf("unplayable", "tab_position"),
            ),
        ).let { it + ("mxl" to it.getValue("musicxml")) }
    }

    private fun <T> ok(o: Outcome<T>): T = (o as Outcome.Success).value

    private fun mxl(xml: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/vnd.recordare.musicxml".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write("""<?xml version="1.0" encoding="UTF-8"?><container><rootfiles><rootfile full-path="score.musicxml" media-type="application/vnd.recordare.musicxml+xml"/></rootfiles></container>""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("score.musicxml")); z.write(xml); z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun ids(prefix: String): IdSource { var n = 0; return IdSource { EntityId("$prefix${n++}") } }

    fun export(format: String, p: Project): Outcome<ExportResult> = runBlocking {
        when (format) {
            "gp7" -> Gp7Exporter().export(ExportRequest(p, "gp7"))
            "musicxml" -> MusicXmlExporter().exportNow(p)
            "mxl" -> MusicXmlExporter().exportNow(p).let { if (it is Outcome.Success) Outcome.Success(it.value.copy(bytes = mxl(it.value.bytes))) else it }
            "midi" -> MidiExporter().exportNow(p)
            "native" -> FileNativeProjectExchange(FileAssetStore(tmp)).export(p)
            "ascii" -> AsciiTabExporter().exportNow(p)
            else -> error(format)
        }
    }

    fun import(format: String, bytes: ByteArray, prefix: String): Outcome<ImportResult> = runBlocking {
        when (format) {
            "gp7" -> GpifImporter().import(bytes, ids(prefix))
            "musicxml", "mxl" -> MusicXmlImporter().importNow(bytes, ids(prefix))
            "midi" -> MidiImporter().importNow(bytes, ids(prefix))
            "native" -> FileNativeProjectExchange(FileAssetStore(tmp)).import(bytes, EntityId("native$prefix")).let { if (it is Outcome.Success) Outcome.Success(ImportResult(it.value, emptyList())) else it as Outcome.Failure }
            else -> error(format)
        }
    }

    // ---------------------------------------------------------------- MIDI: compare the sounding performance
    private fun sounding(p: Project): Map<String, String> {
        val plan = ok(ScorePlaybackCompiler().compileNow(p, 48_000))
        val out = LinkedHashMap<String, String>()
        // Notes: per track name, onset rounded to 1 ms, pitch -> velocity (multiset)
        val counts = HashMap<String, Int>()
        for (n in plan.notes) {
            val ms = Math.round(n.frame / 48.0)
            val drum = p.tracks[n.trackIndex].instrument.kind == InstrumentKind.PERCUSSION
            // Journal cycle 2 (known): MIDI merges drum tracks onto channel 10 -> compare drums as one group.
            val name = if (drum) "<drums>" else p.tracks[n.trackIndex].name
            val k = "$name/${if (drum) "drum" else "pitched"}/@${ms}ms/p${if (drum) Math.round(n.soundingPitch) else n.pitch.midiNumber}"
            val c = counts.merge(k, 1, Int::plus)!!
            out["snote|$k#$c"] = "v${n.velocity}"
        }
        out["sdur|"] = Math.round(plan.totalFrames / 48.0).toString()
        val sounding = plan.notes.map { it.trackIndex }.toSet()
        // Tracks without any sounding note: counted separately (finding: MidiImporter drops empty tracks).
        p.tracks.forEachIndexed { t, tr -> if (t !in sounding) out["sempty|${tr.name}"] = "empty" }
        p.tracks.forEachIndexed { t, tr -> if (t in sounding) out["strack|${if (tr.instrument.kind == InstrumentKind.PERCUSSION) "<drums>" else tr.name}"] = "${if (tr.instrument.kind == InstrumentKind.PERCUSSION) "drum" else "pitched"} prg=${tr.instrument.midiProgram}" }
        return out
    }

    /** onset tolerance of 2 ms between expected and actual sounding notes (SMF tempo has integer µs per quarter). */
    private fun soundingDiff(a: Map<String, String>, b: Map<String, String>): List<Qa15Features.Diff> {
        fun parse(m: Map<String, String>) = m.filterKeys { it.startsWith("snote|") }.map { (k, v) ->
            val s = k.removePrefix("snote|").substringBefore('#'); val parts = s.split("/")
            Triple(parts[0] + "/" + parts[1] + "/" + parts[3], parts[2].removePrefix("@").removeSuffix("ms").toLong(), v)
        }
        val exp = parse(a).groupBy { it.first }.mapValues { e -> e.value.sortedBy { it.second }.toMutableList() }
        val act = parse(b).groupBy { it.first }.mapValues { e -> e.value.sortedBy { it.second }.toMutableList() }
        val out = ArrayList<Qa15Features.Diff>()
        for (k in exp.keys + act.keys) {
            val xs = exp[k] ?: mutableListOf(); val ys = (act[k] ?: mutableListOf()).toMutableList()
            for (x in xs) {
                val j = ys.indexOfFirst { Math.abs(it.second - x.second) <= 2 }
                if (j < 0) out += Qa15Features.Diff("snote.missing", "$k@${x.second}", x.third, null)
                else { val y = ys.removeAt(j); if (y.third != x.third) out += Qa15Features.Diff("snote.velocity", "$k@${x.second}", x.third, y.third) }
            }
            for (y in ys) out += Qa15Features.Diff("snote.extra", "$k@${y.second}", null, y.third)
        }
        for (k in (a.keys + b.keys).filter { !it.startsWith("snote|") }) if (a[k] != b[k]) {
            if (k == "sdur|" && a[k] != null && b[k] != null && Math.abs(a[k]!!.toLong() - b[k]!!.toLong()) <= 2) continue
            out += Qa15Features.Diff(k.substringBefore('|'), k, a[k], b[k])
        }
        return out
    }

    private val midiExplain = mapOf(
        "snote.velocity" to setOf("unison"),
        "snote.missing" to setOf("pitch_range", "unison", "tick_resolution", "unplayable"),
        "snote.extra" to setOf("tick_resolution"),
    )

    // ---------------------------------------------------------------- one project, one format
    fun check(format: String, p: Project, profile: Qa15Gen.Profile): List<Finding> {
        val f = ArrayList<Finding>()
        val ex = try { export(format, p) } catch (t: Throwable) { return listOf(Finding(format, "L2", "export.throw", t.toString())) }
        if (ex !is Outcome.Success) {
            val fail = ex as Outcome.Failure
            // Journal cycle 2 #6: D.S. without Segno stays a WARNING in the editor; playback/MIDI refuse it explicitly.
            if (format == "midi" && fail.message.contains("ohne Segno")) return emptyList()
            // Journal cycle 2 (known): endings that exclude every bar are refused by the route compiler on purpose.
            if (format == "midi" && fail.message.contains("schließen alle Takte aus")) return emptyList()
            return listOf(Finding(format, "L2", "export.failure", fail.message))
        }
        if (format == "ascii") return f
        val losses = ex.value.losses
        val im = try { import(format, ex.value.bytes, "a") } catch (t: Throwable) { return listOf(Finding(format, "L2", "import.throw", t.stackTraceToString().take(600))) }
        if (im !is Outcome.Success) {
            val fail = im as Outcome.Failure
            val inv = if (fail.diagnostics.any { it.severity == Severity.ERROR } || fail.message.contains("gültige Partitur")) "L3" else "L2"
            return listOf(Finding(format, inv, "import.failure", "${fail.code} ${fail.message} ${fail.diagnostics.take(3)}"))
        }
        val back = im.value.project
        val errs = ScoreValidator.validate(back).filter { it.severity == Severity.ERROR } + back.diagnostics.filter { it.severity == Severity.ERROR }
        if (errs.isNotEmpty()) f += Finding(format, "L3", "import.error", errs.take(3).toString())
        // L1
        // Export losses, plus losses the import of our own file reports (both reach the user).
        val lossIds = losses.map { it.featureId }.toSet() + im.value.losses.map { it.featureId }
        val diffs = when (format) {
            "midi" -> if (profile == Qa15Gen.Profile.MIDI_SAFE) soundingDiff(sounding(p), sounding(back)) else emptyList()
            "native" -> if (back.copy(id = p.id, revision = p.revision) != p) listOf(Qa15Features.Diff("native.equal", "project", "equal", "differs")) else emptyList()
            else -> Qa15Features.normalize(format, Qa15Features.of(p), Qa15Features.of(back)).let { (x, y) -> Qa15Features.diff(x, y) }
        }
        val table = if (format == "midi") midiExplain else explain[format].orEmpty()
        for ((cat, ds) in diffs.groupBy { it.category }) {
            val allowed = table[cat].orEmpty()
            if (allowed.none { it in lossIds }) f += Finding(format, "L1", cat, "${ds.size}x e.g. ${ds.take(3)} losses=${lossIds.sorted()}")
        }
        // L4
        val ex2 = try { export(format, back) } catch (t: Throwable) { f += Finding(format, "L2", "reexport.throw", t.toString()); return f }
        if (ex2 !is Outcome.Success) { f += Finding(format, "L4", "reexport.failure", (ex2 as Outcome.Failure).message); return f }
        val im2 = try { import(format, ex2.value.bytes, "b") } catch (t: Throwable) { f += Finding(format, "L2", "reimport.throw", t.toString()); return f }
        if (im2 !is Outcome.Success) { f += Finding(format, "L4", "reimport.failure", (im2 as Outcome.Failure).message); return f }
        val back2 = im2.value.project
        val d4 = if (format == "native") (if (back2.copy(id = back.id) != back) listOf(Qa15Features.Diff("native.equal", "project", "", "")) else emptyList())
            else {
                // -Dqa15.l4nodrums=true: leave percussion tracks out (to see L4 issues other than the drum-tie finding).
                fun strip(pr: Project, m: Map<String, String>): Map<String, String> {
                    if (System.getProperty("qa15.l4nodrums") != "true") return m
                    val drums = pr.tracks.withIndex().filter { it.value.instrument.kind == InstrumentKind.PERCUSSION }.map { "t${it.index}" }.toSet()
                    return m.filterKeys { k -> val loc = k.substringAfter('|').substringBefore('/'); loc !in drums }
                }
                Qa15Features.diff(strip(back, Qa15Features.of(back, includeRests = true)), strip(back2, Qa15Features.of(back2, includeRests = true)))
            }
        for ((cat, ds) in d4.groupBy { it.category }) f += Finding(format, "L4", cat, "${ds.size}x e.g. ${ds.take(3)}")
        return f
    }

    fun findings(format: String, spec: Qa15Gen.Spec): List<Finding> {
        val built = try { Qa15Gen.build(spec) } catch (t: Throwable) { return listOf(Finding(format, "GEN", "gen.throw", t.toString())) }
        return check(format, built.project, spec.profile)
    }

    fun shrink(format: String, spec0: Qa15Gen.Spec, signature: String): Qa15Gen.Spec {
        fun fails(s: Qa15Gen.Spec) = try { findings(format, s).any { it.signature == signature } } catch (t: Throwable) { false }
        var spec = spec0
        if (spec.band) spec.copy(band = false).let { if (fails(it)) spec = it }
        var chunk = maxOf(1, spec.steps.size / 2)
        while (true) {
            var progress = false; var i = 0
            while (i < spec.steps.size) {
                val cand = spec.copy(steps = spec.steps.take(i) + spec.steps.drop(i + chunk))
                if (fails(cand)) { spec = cand; progress = true } else i += chunk
            }
            if (!progress) { if (chunk == 1) break; chunk = maxOf(1, chunk / 2) }
        }
        return spec
    }

    @Test fun debugSeed() {
        val d = System.getProperty("qa15.debug") ?: return
        val parts = d.split(":"); val fmt = parts[0]; val seedText = parts[1]
        val format = if (fmt == "midisafe") "midi" else fmt
        var spec = Qa15Gen.randomSpec(seedText.toLong(), if (fmt == "midisafe") Qa15Gen.Profile.MIDI_SAFE else Qa15Gen.Profile.FULL)
        if (parts.size > 3) spec = spec.copy(band = parts[2].toBoolean(), steps = parts[3].split(",").map { it.trim().toLong() })
        val built = Qa15Gen.build(spec)
        println("DEBUG ${Qa15Gen.stats(built.project)}"); built.log.forEach { println("   $it") }
        val ex = (export(format, built.project) as Outcome.Success).value
        println("LOSSES"); ex.losses.forEach { println("   $it") }
        if (format == "musicxml") println(String(ex.bytes))
        val back = (import(format, ex.bytes, "a") as Outcome.Success).value
        println("IMPORT LOSSES ${back.losses}")
        val (a, b) = Qa15Features.normalize(format, Qa15Features.of(built.project, includeRests = true), Qa15Features.of(back.project, includeRests = true))
        if (format == "midi") { println("SOUNDING DIFF"); soundingDiff(sounding(built.project), sounding(back.project)).forEach { println("   $it") }
            println("TRACKS ${built.project.tracks.map { it.name + ":" + it.instrument.kind }} -> ${back.project.tracks.map { it.name + ":" + it.instrument.kind }}") }
        println("DIFF"); Qa15Features.diff(a, b).forEach { println("   ${it.category}: $it") }
        println("ORIG"); a.forEach { (k, v) -> println("   $k = $v") }
        println("BACK"); b.forEach { (k, v) -> println("   $k = $v") }
    }

    @Test fun formatRoundTripCampaign() {
        if (System.getProperty("qa15.debug") != null) return
        val n = System.getProperty("qa15.n")?.toInt() ?: 30
        val base = System.getProperty("qa15.seed")?.toLong() ?: 15_000L
        val formats = (System.getProperty("qa15.formats") ?: "gp7,musicxml,mxl,midi,midisafe,native,ascii").split(",")
        val doShrink = System.getProperty("qa15.shrink") != "false"
        val bySig = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
        val examples = ConcurrentHashMap<String, String>()
        val ran = ConcurrentHashMap<String, AtomicInteger>()
        val perInv = ConcurrentHashMap<String, AtomicInteger>()
        val pool = Executors.newFixedThreadPool(4)
        for (fmt in formats) for (k in 0 until n) pool.submit {
            val seed = base + k
            val format = if (fmt == "midisafe") "midi" else fmt
            val spec = Qa15Gen.randomSpec(seed, if (fmt == "midisafe") Qa15Gen.Profile.MIDI_SAFE else Qa15Gen.Profile.FULL)
            val fs = try { findings(format, spec) } catch (t: Throwable) { listOf(Finding(format, "HARNESS", "harness", t.toString())) }
            ran.computeIfAbsent(fmt) { AtomicInteger() }.incrementAndGet()
            for (inv in fs.map { it.inv }.distinct()) perInv.computeIfAbsent("$fmt/$inv") { AtomicInteger() }.incrementAndGet()
            for (x in fs.distinctBy { it.signature }) {
                val sig = if (fmt == "midisafe") "midisafe/${x.inv}/${x.category}" else x.signature
                bySig.computeIfAbsent(sig) { ConcurrentLinkedQueue() } += seed
                examples.putIfAbsent(sig, x.detail)
            }
        }
        pool.shutdown(); pool.awaitTermination(4, TimeUnit.HOURS)
        println("QA15 projects per format/invariant with >=1 violation: ${perInv.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}")
        println("QA15 ran: ${ran.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}")
        for ((sig, seeds) in bySig.entries.sortedBy { it.key }) println("QA15 $sig: ${seeds.size} projects, seeds=${seeds.sorted().take(8)}\n      ${examples[sig]?.take(700)}")
        if (doShrink) for ((sig, seeds) in bySig.entries.sortedBy { it.key }) {
            val fmt = sig.substringBefore('/')
            val format = if (fmt == "midisafe") "midi" else fmt
            val realSig = "$format/${sig.substringAfter('/')}"
            val seed = seeds.min()
            val spec = shrink(format, Qa15Gen.randomSpec(seed, if (fmt == "midisafe") Qa15Gen.Profile.MIDI_SAFE else Qa15Gen.Profile.FULL), realSig)
            val built = Qa15Gen.build(spec)
            val f = findings(format, spec).firstOrNull { it.signature == realSig }
            println("QA15 MINIMAL $sig seed=$seed band=${spec.band} steps=${spec.steps} (${Qa15Gen.stats(built.project)}): ${f?.detail?.take(900)}")
            built.log.forEach { println("    $it") }
        }
        assertTrue("signatures: ${bySig.keys.sorted()}", bySig.isEmpty())
    }
}
