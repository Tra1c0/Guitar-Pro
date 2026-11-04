package dev.riffworkbench.fixtures

import dev.riffworkbench.data.local.*
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.sin

/** Deterministic, originally authored fixtures. This source set is not packaged in the app. */
fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { mkdirs() }
    var sequence = 0
    val ids = IdSource { EntityId("fixture-${sequence++}") }
    val factory = ProjectFactory(ids)
    val cache = java.nio.file.Files.createTempDirectory("riff-fixture-assets").toFile()
    try {
        val store = FileAssetStore(cache)
        val exchange = FileNativeProjectExchange(store)
        val json = Json { encodeDefaults = true; prettyPrint = true }
        suspend fun save(name: String, project: Project, writeJson: Boolean = true) {
            val encoded = exchange.export(project)
            check(encoded is Outcome.Success) { encoded.toString() }
            File(root, "$name.riffproj").writeBytes(encoded.value.bytes)
            if (writeJson) File(root, "$name.json").writeText(json.encodeToString(project))
        }
        val session = EditorSession(factory.create("Metal · Drop A · 150 BPM"), ids)
        val first = session.state.value.selection
        repeat(8) { index ->
            session.select(first.copy(offset = Fraction.of(index.toLong(), 16)))
            check(session.dispatch(EditorCommand.PutNote(fret = listOf(0, 0, 0, 3, 5, 7, 8, 8)[index], marks = if (index == 2) setOf(NoteMark.DEAD) else setOf(NoteMark.PALM_MUTE))) is Outcome.Success)
        }
        session.select(first.copy(voice = 1, string = 1))
        session.dispatch(EditorCommand.PutNote(fret = 0, duration = WrittenDuration(2)))
        val drumId = session.state.value.project.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }.id
        repeat(16) { slot ->
            session.select(first.copy(trackId = drumId, offset = Fraction.of(slot.toLong(), 16)))
            session.dispatch(EditorCommand.ToggleDrum(42))
            if (slot % 8 == 0) session.dispatch(EditorCommand.ToggleDrum(36))
            if (slot % 8 == 4) session.dispatch(EditorCommand.ToggleDrum(38))
        }
        val metal = session.state.value.project.mapVoices { track, barIndex, voice ->
            if (track.id != first.trackId || barIndex != 0 || voice.index != 0) voice
            else voice.copy(beats = voice.beats.mapIndexed { beatIndex, beat ->
                val extra: List<Articulation> = when (beatIndex) {
                    3 -> listOf(Articulation.Slide(SlideKind.SHIFT))
                    4 -> listOf(Articulation.Harmonic(HarmonicKind.PINCH))
                    5 -> listOf(Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 2), 200), PitchCurvePoint(Fraction.ONE, 0)), BendKind.RELEASE))
                    else -> emptyList()
                }
                beat.copy(notes = beat.notes.map { note -> note.copy(articulations = note.articulations + extra) })
            })
        }
        save("drop-a-metal", metal)
        val nested = WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2), TupletRatio(5, 4)))
        val complex = metal.mapVoices { track, barIndex, voice ->
            if (track.id != first.trackId || barIndex != 1 || voice.index != 3) voice
            else voice.copy(beats = List(5) { i -> Beat(ids.next(), nested.length() * Fraction(i.toLong()), nested, listOf(Note(ids.next(), Pitch(track.instrument.pitch(0, 3)), 0, 3))) })
        }
        save("four-voices-nested-tuplets", complex)
        val large = largeFixture(ids).mapVoices { track, _, voice ->
            if (voice.index != 0) voice else voice.copy(beats = List(8) { i ->
                val fret = i % 5
                Beat(ids.next(), Fraction.of(i.toLong(), 8), WrittenDuration(8), listOf(Note(ids.next(), Pitch(track.instrument.pitch(0, fret)), 0, fret)))
            })
        }
        save("eight-tracks-200-bars", large, writeJson = false)
        val wave = clickWave()
        File(root, "clock-120bpm-48k.wav").writeBytes(wave)
        val descriptor = (store.put("clock-120bpm-48k.wav", "audio/wav", wave, AssetRights("original synthetic fixture", "CC0-1.0", true)) as Outcome.Success).value
        val measures = List(4) { Measure(ids.next()) }
        val clockBase = factory.create("Clock · 120 BPM").copy(measures = measures, tracks = listOf(factory.track(InstrumentKind.GUITAR, measures), factory.track(InstrumentKind.PERCUSSION, measures)), sections = emptyList(), tempo = listOf(TempoChange(ScorePosition(measures.first().id), 120.0)))
        val mapId = ids.next()
        val anchors = listOf(
            SyncAnchor(ids.next(), PlaybackOccurrence(ScorePosition(measures.first().id), 1, 0), 0),
            SyncAnchor(ids.next(), PlaybackOccurrence(ScorePosition(measures.last().id, Fraction.of(3, 4)), 1, 3), 7500000),
        )
        val clock = clockBase.copy(assets = listOf(descriptor), audio = AudioArrangement(
            clips = listOf(AudioClip(ids.next(), descriptor.hash, "Synthetic click", syncMapId = mapId)),
            syncMaps = listOf(SyncMap(mapId, SyncMode.SCORE_TEMPO, anchors)),
        ))
        save("shared-clock", clock)
        val files = root.listFiles()!!.filter { it.extension in listOf("json", "riffproj", "wav") && it.name != "manifest.json" }.sortedBy { it.name }
        val checksums = files.map { file -> MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } + "  " + file.name }
        // Lines for fixtures in subdirectories (e.g. gp/ from tools/make_gp_fixtures.py) belong to other generators and are kept.
        val sums = File(root, "SHA256SUMS")
        val foreign = if (sums.isFile) sums.readLines().filter { line -> line.substringAfter("  ", "").contains('/') } else emptyList()
        sums.writeText((checksums + foreign).joinToString("\n") + "\n")
        println("Generated ${files.size} own fixtures in $root")
    } finally { cache.deleteRecursively() }
}

private fun Project.mapVoices(transform: (Track, Int, Voice) -> Voice): Project = copy(
    tracks = tracks.map { track ->
        track.copy(staves = track.staves.map { staff ->
            staff.copy(bars = staff.bars.mapIndexed { barIndex, bar ->
                bar.copy(voices = bar.voices.map { voice -> transform(track, barIndex, voice) })
            })
        })
    },
)

private fun clickWave(): ByteArray {
    val sampleRate = 48000; val frames = sampleRate * 8; val bytes = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    bytes.put("RIFF".toByteArray()); bytes.putInt(36 + frames * 2); bytes.put("WAVEfmt ".toByteArray()); bytes.putInt(16); bytes.putShort(1); bytes.putShort(1)
    bytes.putInt(sampleRate); bytes.putInt(sampleRate * 2); bytes.putShort(2); bytes.putShort(16); bytes.put("data".toByteArray()); bytes.putInt(frames * 2)
    repeat(frames) { frame -> val local = frame % 24000; val sample = if (local < 128) sin(2 * PI * 1000 * local / sampleRate) * (1 - local / 128.0) * 12000 else 0.0; bytes.putShort(sample.toInt().toShort()) }
    return bytes.array()
}
