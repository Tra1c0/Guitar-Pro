package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

/** Straight route is useful for inspection; never pretends to resolve unsupported repeats. */
class LinearRepeatCompiler : RepeatCompiler {
    override fun compile(project: Project): Outcome<List<PlaybackOccurrence>> {
        if (project.measures.any { it.repeat != RepeatMark() || it.navigation.isNotEmpty() }) return unavailable("C-REPEAT", "Komplexe Wiederholungen werden in Etappe C aufgelöst.")
        return Outcome.Success(project.measures.mapIndexed { index, measure -> PlaybackOccurrence(ScorePosition(measure.id), 1, index) })
    }
}

class PendingPlaybackCompiler : PlaybackCompiler {
    override suspend fun compile(project: Project, sampleRate: Int): Outcome<PlaybackPlan> = unavailable("C-SEQUENCER")
}
class PendingTempoMapper : TempoMapper {
    override fun toMicros(position: PlaybackOccurrence, project: Project): Outcome<Long> = unavailable("C-TEMPO")
}
class PendingAudioSyncMapper : AudioSyncMapper {
    override fun toAudioMicros(position: PlaybackOccurrence, map: SyncMap): Outcome<Long> = unavailable("C-SYNC")
}
