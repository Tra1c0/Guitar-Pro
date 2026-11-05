package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.synth.TonePreset
import dev.riffworkbench.synth.TonePresets
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import dev.riffworkbench.ui.score.pitchName

@Composable internal fun ScrollPage(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BigTitle(title, null)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable fun ArrangementScreen(vm: WorkspaceViewModel, state: EditorState) {
    val p = state.project
    val measure = p.measures.first { it.id == state.selection.measureId }
    val index = p.measures.indexOf(measure)
    var title by remember(p.metadata.title) { mutableStateOf(p.metadata.title) }
    var artist by remember(p.metadata.artist) { mutableStateOf(p.metadata.artist) }
    var section by remember(p.sections, measure.id) { mutableStateOf(p.sections.firstOrNull { it.measureId == measure.id }?.label ?: "") }
    ScrollPage(stringResource(R.string.arrangement), stringResource(R.string.portrait_hint)) {
        OutlinedTextField(title, { title = it.take(200) }, label = { Text(stringResource(R.string.project_title)) }, modifier = Modifier.textEditing().fillMaxWidth(), singleLine = true)
        OutlinedTextField(artist, { artist = it.take(200) }, label = { Text(tr("Interpret", "Artist")) }, modifier = Modifier.textEditing().fillMaxWidth(), singleLine = true)
        Button(onClick = { vm.command(EditorCommand.SetMetadata(p.metadata.copy(title = title.trim().ifBlank { p.metadata.title }, artist = artist.trim()))) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.rename)) }
        HorizontalDivider()
        Text("${stringResource(R.string.measure)} ${index + 1}", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.measures.forEachIndexed { i, m -> FilterChip(selected = m.id == state.selection.measureId, onClick = { vm.select(state.selection.copy(measureId = m.id, offset = Fraction.ZERO)) },
                label = { Text("${i + 1} · ${m.signature.numerator}/${m.signature.denominator}" + (p.sections.firstOrNull { it.measureId == m.id }?.let { " · ${it.label}" } ?: "")) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.insertBar(after = false) }) { Text(tr("+ Takt davor", "+ Bar before")) }
            OutlinedButton(onClick = { vm.insertBar(after = true) }) { Text(tr("+ Takt danach", "+ Bar after")) }
            OutlinedButton(onClick = vm::duplicateBars) { Text(tr("Duplizieren", "Duplicate")) }
            OutlinedButton(onClick = vm::deleteBars, enabled = p.measures.size > 1) { Text(tr("Löschen", "Delete")) }
            OutlinedButton(onClick = vm::clearBars) { Text(tr("Leeren", "Clear")) }
        }
        SignatureTempoControls(vm, p, measure, index)
        Text(tr("Tonart", "Key"), style = MaterialTheme.typography.titleSmall)
        Stepper(if (measure.key.fifths >= 0) "♯" else "♭", "${kotlin.math.abs(measure.key.fifths)}", { if (measure.key.fifths > -7) vm.command(EditorCommand.SetKey(measure.id, measure.key.copy(fifths = measure.key.fifths - 1))) },
            { if (measure.key.fifths < 7) vm.command(EditorCommand.SetKey(measure.id, measure.key.copy(fifths = measure.key.fifths + 1))) })
        ToggleRow(tr("Moll", "Minor"), measure.key.minor, { vm.command(EditorCommand.SetKey(measure.id, measure.key.copy(minor = it))) })
        Text(tr("Rhythmisches Feel", "Rhythmic feel"), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RhythmicFeel.STRAIGHT to tr("Gerade", "Straight"), RhythmicFeel.SWING_EIGHTH to "Swing 8tel", RhythmicFeel.SWING_SIXTEENTH to "Swing 16tel", RhythmicFeel.TRIPLET to tr("Triolisch", "Triplet feel")).forEach { (feel, label) ->
                FilterChip(measure.feel == feel, { vm.command(EditorCommand.SetFeel(measure.id, feel)) }, label = { Text(label) })
            }
        }
        Text(tr("Wiederholungen", "Repeats"), style = MaterialTheme.typography.titleSmall)
        ToggleRow(tr("Wiederholung beginnt hier", "Repeat starts here"), measure.repeat.starts, { vm.command(EditorCommand.SetRepeat(measure.id, measure.repeat.copy(starts = it))) })
        Stepper(tr("Endet ×", "Ends ×"), "${measure.repeat.endCount ?: "–"}", { val c = measure.repeat.endCount; vm.command(EditorCommand.SetRepeat(measure.id, measure.repeat.copy(endCount = if (c == null || c <= 2) null else c - 1))) },
            { val c = measure.repeat.endCount; vm.command(EditorCommand.SetRepeat(measure.id, measure.repeat.copy(endCount = ((c ?: 1) + 1).coerceAtMost(32)))) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Volta", "Ending"), style = MaterialTheme.typography.bodyMedium)
            (1..4).forEach { n -> FilterChip(n in measure.repeat.alternateEndings, { vm.command(EditorCommand.SetRepeat(measure.id, measure.repeat.copy(alternateEndings = if (n in measure.repeat.alternateEndings) measure.repeat.alternateEndings - n else measure.repeat.alternateEndings + n))) }, label = { Text("$n.") }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(NavigationKind.SEGNO to "Segno", NavigationKind.CODA to "Coda", NavigationKind.TO_CODA to "To Coda", NavigationKind.DA_CAPO to "D.C.", NavigationKind.DAL_SEGNO to "D.S.", NavigationKind.FINE to "Fine").forEach { (kind, label) ->
                val on = measure.navigation.any { it.kind == kind }
                FilterChip(on, { vm.command(EditorCommand.SetNavigation(measure.id, if (on) measure.navigation.filterNot { it.kind == kind } else measure.navigation + NavigationMark(kind))) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(section, { section = it.take(80) }, label = { Text(tr("Abschnitt", "Section")) }, modifier = Modifier.textEditing().weight(1f), singleLine = true)
            Button(onClick = { vm.command(EditorCommand.SetSection(measure.id, section.ifBlank { null })) }) { Text(tr("Setzen", "Set")) }
        }
        HorizontalDivider()
        Text(tr("Spuren", "Tracks"), style = MaterialTheme.typography.titleMedium)
        p.tracks.forEach { track -> TrackCard(vm, p, track) }
        Text(stringResource(R.string.add_track), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(InstrumentKind.GUITAR to R.string.guitar, InstrumentKind.BASS to R.string.bass, InstrumentKind.PERCUSSION to R.string.percussion, InstrumentKind.PIANO to R.string.piano).forEach { (kind, label) ->
                OutlinedButton(onClick = { vm.command(EditorCommand.AddTrack(kind)) }) { Text("+ ${stringResource(label)}") }
            }
        }
        if (state.diagnostics.isNotEmpty()) { Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium); state.diagnostics.take(8).forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable private fun TrackCard(vm: WorkspaceViewModel, p: Project, track: Track) {
    var name by remember(track.id, track.name) { mutableStateOf(track.name) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it.take(120) }, modifier = Modifier.textEditing().weight(1f), singleLine = true, label = { Text(stringResource(R.string.track)) })
                TextButton(onClick = { vm.command(EditorCommand.RenameTrack(track.id, name)) }, enabled = name.isNotBlank() && name != track.name) { Text("✓") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.command(EditorCommand.MoveTrack(track.id, -1)) }, modifier = Modifier.semanticsLabel(stringResource(R.string.move_up))) { Text("↑") }
                TextButton(onClick = { vm.command(EditorCommand.MoveTrack(track.id, 1)) }, modifier = Modifier.semanticsLabel(stringResource(R.string.move_down))) { Text("↓") }
                TextButton(onClick = { vm.command(EditorCommand.DuplicateTrack(track.id)) }) { Text(tr("Duplizieren", "Duplicate")) }
                TextButton(onClick = { vm.command(EditorCommand.RemoveTrack(track.id)) }, enabled = p.tracks.size > 1) { Text(stringResource(R.string.remove_track)) }
            }
        }
    }
}

@Composable fun DrumScreen(vm: WorkspaceViewModel, state: EditorState) {
    val track = state.project.tracks.firstOrNull { it.id == state.selection.trackId && it.instrument.kind == InstrumentKind.PERCUSSION }
        ?: state.project.tracks.firstOrNull { it.instrument.kind == InstrumentKind.PERCUSSION }
    var grid by remember { mutableStateOf(16) }
    ScrollPage(stringResource(R.string.drums), stringResource(R.string.stepgrid_hint)) {
        if (track == null) {
            NoticeCard(stringResource(R.string.no_drum_track))
            Button(onClick = { vm.command(EditorCommand.AddTrack(InstrumentKind.PERCUSSION)) }) { Text(stringResource(R.string.add_track)) }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.project.measures.forEachIndexed { index, measure -> FilterChip(selected = measure.id == state.selection.measureId, onClick = { vm.select(state.selection.copy(trackId = track.id, measureId = measure.id, offset = Fraction.ZERO, string = 0)) }, label = { Text("${stringResource(R.string.measure)} ${index + 1}") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..3).forEach { voice -> FilterChip(selected = voice == state.selection.voice, onClick = { vm.select(state.selection.copy(trackId = track.id, voice = voice, string = 0)) }, label = { Text("${stringResource(R.string.voice)} ${voice + 1}") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Raster", "Grid"), style = MaterialTheme.typography.labelMedium)
                listOf(8 to "1/8", 16 to "1/16", 32 to "1/32", 12 to "1/8 T", 24 to "1/16 T").forEach { (g, label) -> FilterChip(grid == g, { grid = g; vm.setGrid(if (g % 3 == 0) WrittenDuration(g / 3 * 2, tuplets = listOf(TupletRatio(3, 2))) else WrittenDuration(g)) }, label = { Text(label) }) }
            }
            val measure = state.project.measures.first { it.id == state.selection.measureId }
            val beats = track.staves.first().bars.first { it.measureId == measure.id }.voices.first { it.index == state.selection.voice }.beats
            val slots = (measure.signature.length().toDouble() * grid + 1e-9).toInt().coerceIn(1, 128)
            Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                track.instrument.drumKit.forEach { drum -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(drum.name, Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium)
                    repeat(slots) { slot ->
                        val offset = Fraction.of(slot.toLong(), grid.toLong())
                        val active = beats.firstOrNull { it.offset == offset }?.notes?.any { it.pitch.midiNumber == drum.midiNote } == true
                        val strong = slot % (grid / 4).coerceAtLeast(1) == 0
                        FilledTonalButton(onClick = { vm.toggleDrum(track.id, measure.id, offset, drum.midiNote) }, modifier = Modifier.size(48.dp).semanticsLabel("${drum.name}, ${slot + 1}, ${if (active) "on" else "off"}"), contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (active) MaterialTheme.colorScheme.primary else if (strong) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(if (active) "●" else "·") }
                    }
                } }
            }
            DrumKitMix(vm, track)
            val note = vm.selectedBeat(state)?.notes?.firstOrNull()
            if (note != null) {
                Text(tr("Ausgewählter Schlag", "Selected hit"), style = MaterialTheme.typography.titleSmall)
                var velocity by remember(note.id, note.velocity) { mutableFloatStateOf(note.velocity.toFloat()) }
                Text("Velocity ${velocity.toInt()}")
                Slider(velocity, { velocity = it }, valueRange = 1f..127f, onValueChangeFinished = { vm.command(EditorCommand.SetVelocity(velocity.toInt())) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PercussionTechnique.GHOST to "Ghost", PercussionTechnique.RIMSHOT to "Rimshot", PercussionTechnique.CROSS_STICK to "Cross-Stick", PercussionTechnique.CHOKE to "Choke", PercussionTechnique.OPEN_HI_HAT to "HH offen").forEach { (kind, label) ->
                        AssistChip(onClick = { vm.articulation(Articulation.Percussion(kind)) }, label = { Text(label) })
                    }
                }
            }
        }
    }
}

/** Level and pan per kit element (kick louder, toms panned); one transaction per slider gesture. */
@Composable private fun DrumKitMix(vm: WorkspaceViewModel, track: Track) {
    var selected by remember(track.id) { mutableStateOf<Int?>(null) }
    Text(tr("Kit-Mix pro Element", "Kit mix per element"), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        track.instrument.drumKit.forEach { drum ->
            FilterChip(selected == drum.midiNote, { selected = if (selected == drum.midiNote) null else drum.midiNote }, label = { Text(drum.name) })
        }
    }
    val drum = track.instrument.drumKit.firstOrNull { it.midiNote == selected } ?: return
    var volume by remember(drum) { mutableFloatStateOf(drum.volume.toFloat().coerceIn(0f, 2f)) }
    var pan by remember(drum) { mutableFloatStateOf(drum.pan.toFloat().coerceIn(-1f, 1f)) }
    fun commit(updated: DrumElement) = vm.command(EditorCommand.SetInstrument(track.id,
        track.instrument.copy(drumKit = track.instrument.drumKit.map { if (it.midiNote == drum.midiNote) updated else it }), vm.retunePolicy()))
    val db = if (volume <= 0.0001f) "−∞" else "%+.1f".format(java.util.Locale.ROOT, 20 * kotlin.math.log10(volume.toDouble()))
    Text("${drum.name} · " + tr("Pegel", "Level") + " $db dB", style = MaterialTheme.typography.labelLarge)
    Slider(volume, { volume = it }, valueRange = 0f..2f, onValueChangeFinished = { commit(drum.copy(volume = volume.toDouble())) })
    Text("Pan " + when { pan < -0.02f -> "L ${(-pan * 100).toInt()}"; pan > 0.02f -> "R ${(pan * 100).toInt()}"; else -> "C" }, style = MaterialTheme.typography.labelLarge)
    Slider(pan, { pan = it }, valueRange = -1f..1f, onValueChangeFinished = { commit(drum.copy(pan = pan.toDouble())) })
}

@Composable fun MixerScreen(vm: WorkspaceViewModel, state: EditorState) {
    ScrollPage(stringResource(R.string.mixer), stringResource(R.string.mixer_hint)) {
        state.project.tracks.forEach { track -> MixerStrip(vm, track) }
        var master by remember(state.project.mix.master.volume) { mutableFloatStateOf(state.project.mix.master.volume.toFloat()) }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.master_bus), style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.volume)} ${(master * 100).toInt()}%")
            Slider(master, { master = it }, onValueChangeFinished = { vm.command(EditorCommand.SetMasterMix(state.project.mix.master.copy(volume = master.toDouble()))) })
            Text(tr("Am Ausgang begrenzt ein Limiter Spitzen; Stems werden ohne ihn exportiert.", "A limiter protects the output; stems are exported without it."), style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable private fun MixerStrip(vm: WorkspaceViewModel, track: Track) {
    var volume by remember(track.id, track.mix.volume) { mutableFloatStateOf(track.mix.volume.toFloat()) }
    var pan by remember(track.id, track.mix.pan) { mutableFloatStateOf(track.mix.pan.toFloat()) }
    var presetMenu by remember { mutableStateOf(false) }
    val preset = TonePresets.forTrack(track)
    val family = when (track.instrument.kind) { InstrumentKind.PERCUSSION -> TonePreset.Family.DRUMS; InstrumentKind.BASS -> TonePreset.Family.BASS; InstrumentKind.GUITAR -> TonePreset.Family.GUITAR; else -> TonePreset.Family.KEYS }
    val en = english()
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(track.name, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(track.mix.mute, { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(mute = !track.mix.mute))) }, label = { Text(stringResource(R.string.mute)) })
            FilterChip(track.mix.solo, { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(solo = !track.mix.solo))) }, label = { Text(stringResource(R.string.solo)) })
            Box {
                AssistChip(onClick = { presetMenu = true }, label = { Text(if (en) preset.labelEn else preset.labelDe) })
                DropdownMenu(presetMenu, { presetMenu = false }) {
                    TonePresets.all.filter { it.family == family || family == TonePreset.Family.KEYS }.forEach { option ->
                        DropdownMenuItem(text = { Text(if (en) option.labelEn else option.labelDe) }, onClick = {
                            presetMenu = false
                            vm.command(EditorCommand.SetSound(track.id, track.sound.copy(presetId = option.id, mappingDescription = "RiffWorkbench-Synthese: ${option.labelDe}")))
                            vm.command(EditorCommand.SetMix(track.id, track.mix.copy(effects = emptyList())))
                        })
                    }
                }
            }
        }
        Text("${stringResource(R.string.volume)} ${(volume * 100).toInt()}%")
        Slider(volume, { volume = it }, onValueChangeFinished = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(volume = volume.toDouble()))) }, modifier = Modifier.semanticsLabel(stringResource(R.string.volume)))
        Text("${stringResource(R.string.pan)} ${(pan * 100).toInt()}")
        Slider(pan, { pan = it }, valueRange = -1f..1f, onValueChangeFinished = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(pan = pan.toDouble()))) }, modifier = Modifier.semanticsLabel(stringResource(R.string.pan)))
        EffectChain(vm, track)
    } }
}

/** Pedalboard: the effective chain (preset default until edited); toggles and the main parameter per unit. */
@Composable private fun EffectChain(vm: WorkspaceViewModel, track: Track) {
    val chain = TonePresets.effectiveChain(track)
    Text(tr("Effektkette", "Effect chain"), style = MaterialTheme.typography.titleSmall)
    chain.forEachIndexed { i, unit ->
        val main = when (unit.kind) { EffectKind.AMP -> "gain"; EffectKind.OVERDRIVE -> "drive"; EffectKind.GATE -> "thresholdDb"; EffectKind.REVERB, EffectKind.DELAY, EffectKind.CHORUS -> "mix"; EffectKind.COMPRESSOR -> "thresholdDb"; EffectKind.EQ -> "mid"; EffectKind.FILTER -> "cutoffHz"; EffectKind.CABINET_IR -> "model" }
        val range = when (main) { "thresholdDb" -> if (unit.kind == EffectKind.COMPRESSOR) -60f..0f else -80f..0f; "mid" -> -12f..12f; "cutoffHz" -> 100f..12000f; "model" -> 0f..4f; else -> 0f..1f }
        // Show what the DSP actually uses when the unit does not store the value yet.
        var value by remember(unit) { mutableFloatStateOf((unit.parameters[main] ?: dev.riffworkbench.synth.EffectDefaults.value(unit.kind, main) ?: range.start.toDouble()).toFloat().coerceIn(range)) }
        fun write(updated: EffectUnit) = vm.command(EditorCommand.SetMix(track.id, track.mix.copy(effects = chain.mapIndexed { k, u -> if (k == i) updated else u })))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(unit.enabled, { write(unit.copy(enabled = it)) })
            Text(unit.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
            Slider(value, { value = it }, valueRange = range, modifier = Modifier.weight(1f), onValueChangeFinished = { write(unit.copy(parameters = unit.parameters + (main to value.toDouble()))) })
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(EffectKind.OVERDRIVE, EffectKind.EQ, EffectKind.COMPRESSOR, EffectKind.CHORUS, EffectKind.DELAY, EffectKind.REVERB).forEach { kind ->
            AssistChip(onClick = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(effects = chain + EffectUnit(EntityId("fx-${kind.name.lowercase()}-${chain.size}"), kind)))) }, label = { Text("+ ${kind.name.lowercase()}") })
        }
        if (track.mix.effects.isNotEmpty()) AssistChip(onClick = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(effects = emptyList()))) }, label = { Text(tr("Preset-Kette", "Preset chain")) })
    }
}

@Composable fun InstrumentsScreen(vm: WorkspaceViewModel, state: EditorState) {
    val track = state.project.tracks.first { it.id == state.selection.trackId }
    val instrument = track.instrument
    val settings = vm.retunePolicy()
    var policy by remember { mutableStateOf(settings) }
    val chords by vm.chords.collectAsStateWithLifecycle()
    var chordName by remember { mutableStateOf("E5") }
    ScrollPage(stringResource(R.string.instruments), stringResource(R.string.instrument_hint)) {
        TrackAndVoice(vm, state)
        Text(track.name, style = MaterialTheme.typography.titleLarge)
        if (instrument.tuning.isNotEmpty()) {
            Text(stringResource(R.string.tuning), style = MaterialTheme.typography.titleSmall)
            Text(instrument.tuning.joinToString(" – ") { "${pitchName(it)}${it / 12 - 1}" } + (TuningPresets.matching(instrument.tuning)?.let { "  (${it.name})" } ?: ""))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(policy == RetuningPolicy.KEEP_SOUNDING_PITCH, { policy = RetuningPolicy.KEEP_SOUNDING_PITCH }, label = { Text(stringResource(R.string.retain_pitch)) })
                FilterChip(policy == RetuningPolicy.KEEP_FINGER_POSITION, { policy = RetuningPolicy.KEEP_FINGER_POSITION }, label = { Text(stringResource(R.string.retain_fret)) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TuningPresets.all.filter { it.kind == instrument.kind || (instrument.kind == InstrumentKind.OTHER) }.forEach { preset ->
                    FilterChip(preset.tuning == instrument.tuning, { vm.command(EditorCommand.SetInstrument(track.id, instrument.copy(tuning = preset.tuning, capoByString = List(preset.tuning.size) { instrument.capoByString.firstOrNull() ?: 0 }), policy)) }, label = { Text(preset.name) })
                }
            }
            Text(tr("Einzelne Saiten (tief → hoch)", "Individual strings (low → high)"), style = MaterialTheme.typography.titleSmall)
            instrument.tuning.forEachIndexed { s, pitch ->
                Stepper("${s + 1}", "${pitchName(pitch)}${pitch / 12 - 1}", { if (pitch > 12) vm.command(EditorCommand.SetInstrument(track.id, instrument.copy(tuning = instrument.tuning.toMutableList().also { it[s] = pitch - 1 }), policy)) },
                    { if (pitch < 100) vm.command(EditorCommand.SetInstrument(track.id, instrument.copy(tuning = instrument.tuning.toMutableList().also { it[s] = pitch + 1 }), policy)) })
            }
            Stepper(tr("Capo", "Capo"), "${instrument.capoByString.firstOrNull() ?: 0}", { val c = (instrument.capoByString.firstOrNull() ?: 0) - 1; if (c >= 0) vm.command(EditorCommand.SetInstrument(track.id, instrument.copy(capoByString = List(instrument.tuning.size) { c }), policy)) },
                { val c = (instrument.capoByString.firstOrNull() ?: 0) + 1; if (c <= 12) vm.command(EditorCommand.SetInstrument(track.id, instrument.copy(capoByString = List(instrument.tuning.size) { c }), policy)) })
            Text(stringResource(R.string.fretboard), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.horizontalScroll(rememberScrollState())) { instrument.tuning.indices.reversed().forEach { string -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pitchName(instrument.tuning[string]), Modifier.width(28.dp))
                (0..15).forEach { fret -> OutlinedButton(onClick = { vm.putNoteAt(string, fret) }, modifier = Modifier.size(48.dp).semanticsLabel("${stringResource(R.string.string_label)} ${string + 1}, $fret"), contentPadding = PaddingValues(0.dp)) { Text("$fret") } }
            } } }
            HorizontalDivider()
            Text(tr("Akkordbibliothek", "Chord library"), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(chordName, { chordName = it.take(12) }, label = { Text(tr("Akkord (z. B. Am, F#m7, E5)", "Chord (e.g. Am, F#m7, E5)")) }, modifier = Modifier.textEditing().weight(1f), singleLine = true)
                Button(onClick = { vm.searchChords(chordName) }) { Text(tr("Suchen", "Search")) }
            }
            chords.forEach { chord ->
                OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(chord.frets.joinToString(" ") { it?.toString() ?: "x" }, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.insertChord(chord) }) { Text(tr("Eintragen", "Insert")) }
                } }
            }
        } else {
            NoticeCard(tr("Dieses Instrument hat keine Saiten; Noten werden mit Tonhöhe eingegeben.", "This instrument has no strings; notes are entered by pitch."))
        }
    }
}

@Composable fun HistoryScreen(vm: WorkspaceViewModel, state: WorkspaceState) {
    var name by remember { mutableStateOf("") }
    ScrollPage(stringResource(R.string.history), stringResource(R.string.history_hint)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(vm::undo, enabled = state.editor?.canUndo == true) { Text(stringResource(R.string.undo)) }
            OutlinedButton(vm::redo, enabled = state.editor?.canRedo == true) { Text(stringResource(R.string.redo)) }
        }
        OutlinedTextField(name, { name = it.take(200) }, label = { Text(stringResource(R.string.snapshot_name)) }, modifier = Modifier.textEditing().fillMaxWidth())
        Button(onClick = { vm.createSnapshot(name); name = "" }, enabled = name.isNotBlank()) { Text(stringResource(R.string.snapshot_create)) }
        if (state.snapshots.isEmpty()) Text(stringResource(R.string.snapshots_empty))
        state.snapshots.forEach { snapshot -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(snapshot.name, style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.revision)} ${snapshot.revision}", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { vm.restoreSnapshot(snapshot) }) { Text(stringResource(R.string.restore)) }
        } } }
        PendingCard(if (english()) "Variants" else "Varianten", if (english()) "Branching history builds on immutable revisions and snapshots." else "Verzweigte Historie baut auf unveränderlichen Revisionen und Schnappschüssen auf.", "D")
    }
}
