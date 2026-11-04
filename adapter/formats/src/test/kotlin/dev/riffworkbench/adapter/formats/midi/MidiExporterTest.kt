package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MidiExporterTest {
    private data class N(val track: Int, val channel: Int, val key: Int, val velocity: Int, val on: Long, val off: Long)

    private fun export(project: Project): ExportResult {
        val result = runBlocking { MidiExporter().export(ExportRequest(project, "midi")) }
        assertTrue(result.toString(), result is Outcome.Success)
        return (result as Outcome.Success).value
    }

    private fun notes(smf: SmfData): List<N> = buildList {
        for ((ti, t) in smf.tracks.withIndex()) {
            val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()
            for (i in 0 until t.size) {
                val s = t.status(i); val ch = s and 0x0F
                when {
                    s and 0xF0 == 0x90 && t.data2(i) > 0 -> open.getOrPut(ch * 128 + t.data1(i)) { ArrayDeque() }.addLast(t.tick(i) to t.data2(i))
                    s and 0xF0 == 0x80 || s and 0xF0 == 0x90 -> open[ch * 128 + t.data1(i)]?.removeFirstOrNull()?.let { add(N(ti, ch, t.data1(i), it.second, it.first, t.tick(i))) }
                }
            }
        }
    }.sortedWith(compareBy({ it.on }, { it.track }, { it.key }))

    private fun metas(smf: SmfData, type: Int) = smf.tracks[0].metas.filter { it.type == type }

    @Test fun exportsUnfoldedRouteWithTempoMapMetersAndMarkers() {
        val ref = ReferenceScore()
        val result = export(ref.project)
        assertEquals("audio/midi", result.mediaType)
        assertEquals("Testriff.mid", result.fileName)
        val smf = SmfReader.read(result.bytes)
        assertEquals(1, smf.format)
        assertEquals(960, smf.division)
        assertEquals(4, smf.tracks.size) // conductor + guitar + bass + drums

        // Route m0 m1 m0 m2 m3(7/8) m4 -> starts 0, 3840, 7680, 11520, 15360, 18720; end 22560.
        val tempo = metas(smf, 0x51).map { it.tick to (((it.data[0].toInt() and 0xFF) shl 16) or ((it.data[1].toInt() and 0xFF) shl 8) or (it.data[2].toInt() and 0xFF)) }
        assertEquals(listOf(0L to 400_000, 15_360L to 375_000, 20_640L to 428_571), tempo)
        val meters = metas(smf, 0x58).map { Triple(it.tick, it.data[0].toInt(), 1 shl it.data[1].toInt()) }
        assertEquals(listOf(Triple(0L, 4, 4), Triple(15_360L, 7, 8), Triple(18_720L, 4, 4)), meters)
        assertEquals(listOf(0L to "Riff A", 7_680L to "Riff A", 15_360L to "Bridge"), metas(smf, 0x06).map { it.tick to it.text() })
        assertEquals("Testriff", metas(smf, 0x03).single().text())
        assertEquals(listOf("Gitarre · Drop A", "Bass", "Drums"), smf.tracks.drop(1).map { t -> t.metas.single { it.type == 0x03 }.text() })
        assertTrue(smf.tracks.all { it.endTick == 22_560L })
    }

    @Test fun notesHaveExactTicksPitchesChannelsAndVelocities() {
        val ref = ReferenceScore()
        val smf = SmfReader.read(export(ref.project).bytes)
        val all = notes(smf)
        val guitar = all.filter { it.track == 1 }
        val bass = all.filter { it.track == 2 }
        val drums = all.filter { it.track == 3 }
        assertTrue(guitar.all { it.channel == 0 })
        assertTrue(bass.all { it.channel == 1 })
        assertTrue(drums.all { it.channel == 9 })
        // Bar 1 is played twice (repeat unfolded): palm-muted open A1 eighths with their own velocities.
        val firstPass = guitar.filter { it.on < 3_840 }
        assertEquals((0 until 8).map { N(1, 0, 33, 100 + it, it * 480L, it * 480L + 480) }, firstPass)
        val secondPass = guitar.filter { it.on in 7_680 until 11_520 }
        assertEquals(firstPass.map { it.copy(on = it.on + 7_680, off = it.off + 7_680) }, secondPass)
        // Staccato halves the eighth in the 7/8 bar.
        assertEquals(N(1, 0, 38, 96, 15_360, 15_600), guitar.single { it.on == 15_360L })
        // Tie across the bar line 7/8 -> 4/4 is one note: last eighth of bar 4 plus the quarter of bar 5.
        assertEquals(N(1, 0, 48, 96, 18_240, 19_680), guitar.single { it.on == 18_240L })
        // Triplet eighths (320 ticks) and nested 3:2 x 5:4 sixteenths (1/30 whole = 128 ticks).
        assertEquals(listOf(19_680L, 20_000L, 20_320L), guitar.filter { it.key in setOf(55, 57, 59) && it.on in 19_680L until 20_640L }.map { it.on })
        val nested = guitar.filter { it.on in 20_640L until 21_280L }
        assertEquals((0 until 5).map { 20_640L + 128 * it }, nested.map { it.on })
        assertEquals((0 until 5).map { 60 + it }, nested.map { it.key })
        assertEquals(listOf(21_280L to 21_920L, 21_920L to 22_560L), guitar.filter { it.on >= 21_280L }.map { it.on to it.off })
        // Drums: kick, snare and closed hi-hat on channel 10 with their velocities; 4/4 bars twice, the 7/8 bar once.
        assertEquals(N(3, 9, 36, 110, 0, 480), drums.first { it.key == 36 })
        assertEquals(8 + 8 + 8 + 8 + 7 + 8, drums.count { it.key == 42 })
        // Bass follows the route as well.
        assertEquals(47, bass.size)
    }

    @Test fun isolatedBendBecomesPitchBendOverlappedBendIsReported() {
        val ref = ReferenceScore()
        val result = export(ref.project)
        val smf = SmfReader.read(result.bytes)
        val guitar = smf.tracks[1]
        val controllers = (0 until guitar.size).filter { guitar.status(it) == 0xB0 }.map { guitar.data1(it) to guitar.data2(it) }
        // RPN 0 (pitch-bend sensitivity) = 2 semitones, then RPN null.
        assertTrue(controllers.windowed(6).any { it == listOf(101 to 0, 100 to 0, 6 to 2, 38 to 0, 101 to 127, 100 to 127) })
        val bends = (0 until guitar.size).filter { guitar.status(it) == 0xE0 }.map { guitar.tick(it) to (guitar.data1(it) or (guitar.data2(it) shl 7)) }
        // Only the isolated bend (7/8 bar, third eighth, 16320..16800) is written: up to +100 cents, then back to centre.
        assertTrue(bends.isNotEmpty())
        assertTrue(bends.all { it.first in 16_320L..16_800L })
        assertEquals(16_320L to 8192, bends.first())
        assertEquals(8192 + 4096, bends.maxOf { it.second })
        assertEquals(8192, bends.last().second)
        assertTrue(bends.last().first >= 16_799L)
        val features = result.losses.map { it.featureId }.toSet()
        assertTrue(features.containsAll(setOf("bend", "palm_mute", "dead_note", "tab_position")))
        val bendLoss = result.losses.single { it.featureId == "bend" }
        assertEquals("export", bendLoss.direction)
        assertEquals(ref.guitar.id, bendLoss.entityId)
        // Once per feature and track, not per note: eight palm-muted notes give one entry.
        assertEquals(1, result.losses.count { it.featureId == "palm_mute" })
    }

    @Test fun swingAndFermataAreCarriedByTheTempoMap() {
        val kit = ScoreKit()
        val m = listOf(kit.measure(feel = RhythmicFeel.SWING_EIGHTH), kit.measure())
        val g = kit.track("G", kit.dropA, m, mapOf(
            0 to mapOf(0 to kit.eighths(8) { kit.note(kit.dropA, 0, 0) }),
            1 to mapOf(0 to listOf(kit.beat(Fraction.ZERO, kit.d(2), kit.note(kit.dropA, 0, 0), fermata = Fermata(multiplier = 2.0)), kit.beat(kit.f(1, 2), kit.d(2), kit.note(kit.dropA, 0, 2)))),
        ))
        val p = kit.project(m, listOf(g), listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
        val smf = SmfReader.read(export(p).bytes)
        val tempo = metas(smf, 0x51).map { it.tick to (((it.data[0].toInt() and 0xFF) shl 16) or ((it.data[1].toInt() and 0xFF) shl 8) or (it.data[2].toInt() and 0xFF)).toLong() }
        // Swung eighths: 2/3 and 1/3 of a quarter at 120 BPM; notes stay on the straight grid.
        assertEquals(listOf(0L to 666_667L, 480L to 333_333L), tempo.take(2))
        assertEquals(8, tempo.count { it.first < 3_840 })
        // Fermata doubles the first half of bar 2, then tempo returns.
        assertEquals(listOf(3_840L to 1_000_000L, 5_760L to 500_000L), tempo.filter { it.first >= 3_840 })
        val map = TempoMap(tempo.map { it.first }.toLongArray(), tempo.map { it.second }.toLongArray(), 960)
        assertEquals(2_000_000.0, map.micros(3_840), 5.0)
        assertEquals(2_000_000.0 + 2_000_000.0 + 1_000_000.0, map.micros(7_680), 5.0)
        // Notes are on exact straight ticks.
        assertEquals((0 until 8).map { it * 480L } + listOf(3_840L, 5_760L), notes(smf).map { it.on })
    }

    @Test fun drumTechniquesAndPowerChordSlideShareOneBend() {
        val kit = ScoreKit()
        val m = listOf(kit.measure())
        val slide = Articulation.Slide(SlideKind.SHIFT)
        val g = kit.track("G", kit.dropA, m, mapOf(0 to mapOf(0 to listOf(
            kit.beat(Fraction.ZERO, kit.d(2), kit.note(kit.dropA, 0, 3, slide), kit.note(kit.dropA, 1, 5, slide)),
            kit.beat(kit.f(1, 2), kit.d(2), kit.note(kit.dropA, 0, 5), kit.note(kit.dropA, 1, 7)),
        ))))
        val d = kit.track("D", kit.drums, m, mapOf(0 to mapOf(0 to listOf(kit.beat(Fraction.ZERO, kit.d(4), kit.drum(42, Articulation.Percussion(PercussionTechnique.OPEN_HI_HAT)))))))
        val result = export(kit.project(m, listOf(g, d), listOf(TempoChange(ScorePosition(m[0].id), 120.0))))
        val smf = SmfReader.read(result.bytes)
        val guitar = smf.tracks[1]
        val bends = (0 until guitar.size).filter { guitar.status(it) == 0xE0 }.map { guitar.data1(it) or (guitar.data2(it) shl 7) }
        // Both notes of the power chord slide up two semitones together: the shared curve is written once.
        assertEquals(16383, bends.max())
        assertEquals(8192, bends.last())
        assertFalse(result.losses.any { it.featureId == "slide" })
        assertEquals(46, notes(smf).single { it.track == 2 }.key) // open hi-hat technique -> GM 46
    }

    @Test fun invalidProjectsAndWrongFormatFail() {
        val ref = ReferenceScore()
        val wrong = runBlocking { MidiExporter().export(ExportRequest(ref.project, "musicxml")) }
        assertEquals(FailureCode.VALIDATION, (wrong as Outcome.Failure).code)
        val broken = ref.project.copy(tempo = listOf(TempoChange(ScorePosition(EntityId("missing")), 120.0)))
        val failure = MidiExporter().exportNow(broken) as Outcome.Failure
        assertEquals(FailureCode.VALIDATION, failure.code)
        assertEquals("C-FORMAT", failure.contractId)
    }
}
