package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 26, latency per output device in the workspace (lifecycle shim): the active device selects its profile (else the
 * device-less fallback), the hint „<Name>: Latenz nicht kalibriert“ comes once per device and app process and only for
 * uncalibrated radio links, a calibration is stored for the device it ran on, a device switch aborts a recording or a
 * calibration without writing, and the score's playhead runs max(0, tap(device) − tap(speaker)) behind while playing
 * (stop and seek show their target at once). Transport fake as in Qa25RhythmTapViewModelTest (identity time map).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa26LatencyRouteViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q26vm-${n++}") }
    private val sr = 48_000
    private val ms = sr / 1000L
    private val speaker = AudioRoute(AudioRouteKind.SPEAKER, "Pixel 8")
    private val buds = AudioRoute(AudioRouteKind.BLUETOOTH_A2DP, "Galaxy Buds2")
    private val le = AudioRoute(AudioRouteKind.BLE, "LE Kopfhörer")
    private val wired = AudioRoute(AudioRouteKind.WIRED, "Klinke")

    private class Repository(var stored: Project) : ProjectRepository {
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> { stored = project; return Outcome.Success(Unit) }
    }
    private class Snapshots : SnapshotRepository {
        override suspend fun list(projectId: EntityId) = Outcome.Success(emptyList<SnapshotInfo>())
        override suspend fun create(project: Project, name: String) = Outcome.Success(SnapshotInfo("snap", project.id, project.revision, name, 0))
        override suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project> = unavailable("C-SNAPSHOT")
    }
    private class Settings(initial: AppSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private val exchange = object : NativeProjectExchange {
        override suspend fun export(project: Project): Outcome<ExportResult> = unavailable("C-NATIVE")
        override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = unavailable("C-NATIVE")
    }

    private class FakeTransport(val sr: Int) : Transport {
        val mutable = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        override val state = mutable
        val prepared = ArrayList<Project>()
        var settings = PracticeSettings()
        var plan: PlaybackPlan? = null
        var start = 0L
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> {
            prepared += project; this.settings = settings
            plan = (ScorePlaybackCompiler().compileNow(project, sr, PlaybackOptions(settings.speed, settings.metronome)) as Outcome.Success).value
            return Outcome.Success(Unit)
        }
        override suspend fun play(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PLAYING, sample = SamplePosition(start, sr), position = plan?.positionAt(start), countingIn = settings.countInBars > 0); return Outcome.Success(Unit) }
        override suspend fun pause(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PAUSED); return Outcome.Success(Unit) }
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> { start = plan!!.frameFor(position)!!; mutable.value = mutable.value.copy(sample = SamplePosition(start, sr), position = plan!!.positionAt(start)); return Outcome.Success(Unit) }
        override suspend fun setLoop(range: LoopRange?) = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.STOPPED, countingIn = false, sample = SamplePosition(0, sr), position = plan?.positionAt(0)); return Outcome.Success(Unit) }
        override fun audiblePlanFrameAt(nanos: Long): Double = if (mutable.value.status == TransportStatus.STOPPED) Double.NaN else nanos.toDouble()
        override fun installedPlan(): PlaybackPlan? = plan
        fun advance(frame: Long) { mutable.value = mutable.value.copy(sample = SamplePosition(frame, sr), position = plan?.positionAt(frame), countingIn = false) }
    }

    private class Fixture(val vm: WorkspaceViewModel, val t: FakeTransport, val settings: Settings, val routes: ManualAudioRouteMonitor, val memory: RouteNoticeMemory)
    private fun harness(initial: AppSettings = AppSettings(), route: AudioRoute? = speaker, memory: RouteNoticeMemory = RouteNoticeMemory(), block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val t = FakeTransport(sr)
        val settings = Settings(initial)
        val routes = ManualAudioRouteMonitor(route)
        val project = ProjectFactory(ids).create("Q26", signatures = List(4) { TimeSignature() }).copy(revision = 0)
        val vm = WorkspaceViewModel(project.id, Repository(project), Snapshots(), exchange, settings, t, ids,
            WorkspaceServices(audioRoutes = routes, routeNotices = memory), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); assertNotNull(vm.state.value.editor); block(Fixture(vm, t, settings, routes, memory)) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun profiles(vararg p: Pair<AudioRoute, Double>, fallback: Double = 0.0) = AppSettings(tapLatencyMs = fallback, latencyProfiles = p.associate { (r, v) -> r.key to LatencyProfile(v, 1L) })
    private fun TestScope.switch(x: Fixture, r: AudioRoute?) { x.routes.set(r); advanceUntilIdle() }

    @Test fun deviceSwitchSelectsTheProfileAndTheTapDialogShowsTheActiveDevice() = harness(profiles(speaker to 40.0, buds to 220.0, fallback = 30.0)) { x ->
        x.vm.openRhythmTap()
        assertEquals(40.0, x.vm.rhythm.value.latencyMs, 0.0)
        assertEquals("Pixel 8 (Lautsprecher)", x.vm.rhythm.value.latencyDevice)
        assertTrue(x.vm.rhythm.value.latencyOwn)
        switch(x, buds)
        assertEquals(220.0, x.vm.rhythm.value.latencyMs, 0.0)
        assertEquals("Galaxy Buds2 (Bluetooth)", x.vm.rhythm.value.latencyDevice)
        switch(x, le)
        assertEquals("uncalibrated: device-less fallback", 30.0, x.vm.rhythm.value.latencyMs, 0.0)
        assertFalse(x.vm.rhythm.value.latencyOwn)
        switch(x, null)
        assertNull(x.vm.rhythm.value.latencyDevice)
        assertEquals(30.0, x.vm.rhythm.value.latencyMs, 0.0)
        // The tapped frames are corrected with the active device's value: a tap 220 ms late on the buds lands on the beat.
        switch(x, buds)
        x.vm.startRhythmTap(); advanceUntilIdle()
        val plan = x.t.plan!!
        x.t.advance(plan.spans[0].startFrame + 10); advanceUntilIdle()
        assertEquals(RhythmTapPhase.RECORDING, x.vm.rhythm.value.phase)
        val quarter = Math.round(plan.spans[0].frameAt(0.25))
        x.vm.rhythmTapDown(quarter + 220 * ms); x.vm.rhythmTapUp(quarter + 280 * ms)
        x.t.advance(plan.spans[1].endFrame); advanceUntilIdle()
        val result = x.vm.rhythm.value.result!!
        assertEquals(Fraction.of(1, 4), result.bars[0].events.first { it.kind == RhythmEvent.Kind.ONSET }.offset)
    }

    @Test fun hintOncePerDeviceAndProcessOnlyForUncalibratedRadioLinks() {
        val memory = RouteNoticeMemory()
        harness(profiles(buds to 200.0, fallback = 50.0), route = speaker, memory = memory) { x ->
            assertNull(x.vm.state.value.notice)
            switch(x, wired); assertNull("wired: no hint", x.vm.state.value.notice)
            switch(x, buds); assertNull("calibrated: no hint", x.vm.state.value.notice)
            switch(x, le)
            assertEquals("LE Kopfhörer: Latenz nicht kalibriert", x.vm.state.value.notice)
            assertEquals(NoticeAction("LE Kopfhörer: Latenz nicht kalibriert", NoticeActionKind.CALIBRATE_LATENCY, "Kalibrieren"), x.vm.state.value.noticeAction)
            x.vm.showNotice(null)
            switch(x, speaker); switch(x, le)
            assertNull("only once per device", x.vm.state.value.notice)
            // Another notice text hides the button (the action belongs to its text only).
            x.vm.showNotice("Etwas anderes")
            assertNotEquals(x.vm.state.value.notice, x.vm.state.value.noticeAction?.text)
        }
        // A new workspace in the same app process: still once (the memory lives in the composition root).
        harness(AppSettings(), route = le, memory = memory) { x -> assertNull(x.vm.state.value.notice) }
        // A device already connected when the workspace opens gets its hint (also across the load).
        harness(AppSettings(), route = AudioRoute(AudioRouteKind.HEARING_AID, "Phonak"), memory = memory) { x ->
            assertEquals("Phonak: Latenz nicht kalibriert", x.vm.state.value.notice)
            assertEquals(NoticeActionKind.CALIBRATE_LATENCY, x.vm.state.value.noticeAction?.kind)
        }
    }

    private fun TestScope.calibrate(x: Fixture, lateMs: Int) {
        x.vm.startTapCalibration(); advanceUntilIdle()
        assertEquals(RhythmTapPhase.CALIBRATING, x.vm.rhythm.value.phase)
        val clicks = x.t.plan!!.clicks.map { it.frame }
        x.t.advance(1); advanceUntilIdle()
        clicks.forEachIndexed { i, c -> x.vm.rhythmTapDown(c + (lateMs + i % 3 - 1) * ms) }
        x.t.advance(x.t.plan!!.totalFrames); advanceUntilIdle()
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
    }

    @Test fun calibrationIsStoredForTheDeviceItRanOn() = harness(AppSettings(tapLatencyMs = 33.0), route = buds) { x ->
        x.vm.openRhythmTap()
        calibrate(x, 210)
        val s = x.settings.settings.value
        assertEquals(210.0, s.latencyProfiles[buds.key]!!.tapLatencyMs, 1.0)
        assertTrue(s.latencyProfiles[buds.key]!!.calibratedAtEpochMs > 0)
        assertEquals("device-less value untouched", 33.0, s.tapLatencyMs, 0.0)
        assertEquals(210.0, x.vm.rhythm.value.latencyMs, 1.0); assertTrue(x.vm.rhythm.value.latencyOwn)
        assertTrue(x.vm.state.value.notice!!.endsWith("für Galaxy Buds2 (Bluetooth)"))
        assertEquals("only the buds calibrated: no playhead correction", 0.0, x.vm.playheadDelayMs(), 0.0)
        switch(x, speaker)
        assertEquals(33.0, x.vm.rhythm.value.latencyMs, 0.0)
        calibrate(x, 40)
        assertEquals(40.0, x.settings.settings.value.latencyProfiles[speaker.key]!!.tapLatencyMs, 1.0)
        assertEquals(0.0, x.vm.playheadDelayMs(), 1.0)
        switch(x, buds)
        assertEquals(170.0, x.vm.playheadDelayMs(), 1.0)
        // „Zurücksetzen“ removes the active device's profile; the fallback applies again.
        x.vm.resetTapCalibration(); advanceUntilIdle()
        assertNull(x.settings.settings.value.latencyProfiles[buds.key])
        assertEquals(33.0, x.vm.rhythm.value.latencyMs, 0.0)
        assertEquals(0.0, x.vm.playheadDelayMs(), 0.0)
    }

    @Test fun aDeviceSwitchAbortsRecordingAndCalibrationWithoutWriting() = harness(profiles(speaker to 40.0)) { x ->
        val before = x.vm.state.value.editor!!.project
        x.vm.openRhythmTap(); x.vm.startRhythmTap(); advanceUntilIdle()
        x.t.advance(x.t.plan!!.spans[0].startFrame + 10); advanceUntilIdle()
        assertEquals(RhythmTapPhase.RECORDING, x.vm.rhythm.value.phase)
        x.vm.rhythmTapDown(x.t.plan!!.spans[0].startFrame + 100)
        switch(x, buds)
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
        assertNull(x.vm.rhythm.value.result)
        // Uncalibrated buds: abort and hint in one notice, with „Kalibrieren“.
        assertEquals("Ausgabegerät gewechselt (Galaxy Buds2 (Bluetooth)) – Aufnahme abgebrochen. Galaxy Buds2: Latenz nicht kalibriert", x.vm.state.value.notice)
        assertEquals(NoticeActionKind.CALIBRATE_LATENCY, x.vm.state.value.noticeAction?.kind)
        assertSame(before, x.vm.state.value.editor!!.project)
        assertEquals(TransportStatus.STOPPED, x.t.state.value.status)
        // Calibration: aborted, nothing stored for either device.
        x.vm.startTapCalibration(); advanceUntilIdle()
        x.t.advance(1); advanceUntilIdle()
        x.t.plan!!.clicks.forEach { c -> x.vm.rhythmTapDown(c.frame + 200 * ms) }
        switch(x, speaker)
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
        assertEquals("Ausgabegerät gewechselt (Pixel 8 (Lautsprecher)) – Kalibrierung abgebrochen", x.vm.state.value.notice)
        x.t.advance(x.t.plan!!.totalFrames); advanceUntilIdle()
        assertEquals(mapOf(speaker.key to LatencyProfile(40.0, 1L)), x.settings.settings.value.latencyProfiles)
        assertEquals(0.0, x.settings.settings.value.tapLatencyMs, 0.0)
    }

    @Test fun playheadRunsBehindOnlyWhilePlayingAndStopSeekShowTheTarget() = harness(profiles(speaker to 45.0, buds to 245.0), route = buds) { x ->
        assertEquals(200.0, x.vm.playheadDelayMs(), 1e-9)
        x.vm.togglePlay(); advanceUntilIdle()
        val plan = x.t.plan!!
        val start = x.t.state.value
        assertEquals(TransportStatus.PLAYING, start.status)
        val t0 = 1_000_000_000L
        val second = 1_000_000_000L
        assertEquals("play start: the start position", start.position, x.vm.displayedPlayhead(start, t0))
        x.t.advance(start.sample!!.frame + sr)                                  // one second later
        val playing = x.vm.displayedPlayhead(x.t.state.value, t0 + second)!!
        assertEquals(plan.positionAt(start.sample!!.frame + sr - 200 * ms), playing)
        assertNotEquals(x.t.state.value.position, playing)
        // On the speaker (reference) no correction.
        switch(x, speaker)
        x.t.advance(start.sample!!.frame + 2 * sr)
        assertEquals(x.t.state.value.position, x.vm.displayedPlayhead(x.t.state.value, t0 + 2 * second))
        switch(x, buds)
        // Seek while playing: the target at once.
        x.t.advance(start.sample!!.frame + 2 * sr + 1_600)
        x.vm.displayedPlayhead(x.t.state.value, t0 + 2 * second + 33_000_000)
        val target = plan.spans[3].startFrame
        x.t.advance(target)
        assertEquals(plan.positionAt(target), x.vm.displayedPlayhead(x.t.state.value, t0 + 2 * second + 66_000_000))
        // Pause and stop: the transport position at once.
        x.t.advance(target + sr)
        x.vm.displayedPlayhead(x.t.state.value, t0 + 3 * second + 66_000_000)
        x.vm.togglePlay(); advanceUntilIdle()
        assertEquals(TransportStatus.PAUSED, x.t.state.value.status)
        assertEquals(x.t.state.value.position, x.vm.displayedPlayhead(x.t.state.value, t0 + 3 * second + 99_000_000))
        // Resume: the paused position at once, then the delay again (never behind it).
        x.vm.togglePlay(); advanceUntilIdle()
        assertEquals(TransportStatus.PLAYING, x.t.state.value.status)
        val resumed = x.t.state.value
        assertEquals(resumed.position, x.vm.displayedPlayhead(resumed, t0 + 4 * second))
        x.t.advance(resumed.sample!!.frame + 4_800)                              // 100 ms later: still at the resume point
        assertEquals(resumed.position, x.vm.displayedPlayhead(x.t.state.value, t0 + 4 * second + 100_000_000))
        x.t.mutable.value = x.t.state.value.copy(status = TransportStatus.STOPPED)
        assertNull("stopped: no playhead", x.vm.displayedPlayhead(x.t.state.value, t0 + 5 * second))
    }
}
