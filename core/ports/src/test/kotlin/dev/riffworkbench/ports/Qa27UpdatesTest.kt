package dev.riffworkbench.ports

import dev.riffworkbench.model.AppSettings
import dev.riffworkbench.model.FailureCode
import dev.riffworkbench.model.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 27 (automatische Updates): tag scheme „v<versionCode>“, parsing of a GitHub `releases/latest` answer (fixture, no
 * network), plausibility, the 6-h throttle of the start check and the flow of [UpdateCoordinator] with fakes.
 */
class Qa27UpdatesTest {
    private val sha = "3f9a0c4b5e6d7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708"

    /** Shape of a real answer (shortened: uploader, reactions and most URLs removed). */
    private fun release(tag: String = "v1057", assetName: String = "ArtiFret-0.57.apk", size: Long = 61_234_567, prerelease: Boolean = false, draft: Boolean = false,
                        url: String = "https://github.com/Tra1c0/Guitar-Pro/releases/download/$tag/$assetName", digest: String? = "sha256:$sha") = """
        {
          "url": "https://api.github.com/repos/Tra1c0/Guitar-Pro/releases/123456",
          "html_url": "https://github.com/Tra1c0/Guitar-Pro/releases/tag/$tag",
          "id": 123456,
          "tag_name": "$tag",
          "target_commitish": "ccr-0b650dff-jnhups-qa",
          "name": "ArtiFret 0.57",
          "draft": $draft,
          "prerelease": $prerelease,
          "created_at": "2026-11-12T10:00:00Z",
          "published_at": "2026-11-12T10:05:00Z",
          "assets": [
            { "name": "checksums.txt", "size": 120, "state": "uploaded", "content_type": "text/plain",
              "browser_download_url": "https://github.com/Tra1c0/Guitar-Pro/releases/download/$tag/checksums.txt", "digest": null },
            { "id": 99, "name": "$assetName", "label": "", "content_type": "application/vnd.android.package-archive", "state": "uploaded",
              "size": $size, "download_count": 3, "browser_download_url": "$url", "digest": ${digest?.let { "\"$it\"" } ?: "null"} }
          ],
          "body": "- Rhythmus tippen: Raster 1/32\n- Update-Prüfung"
        }
    """.trimIndent()

    @Test fun tagParsingAndComparison() {
        assertEquals(1057, Updates.versionCodeOfTag("v1057"))
        assertEquals(1, Updates.versionCodeOfTag(" v1 "))
        for (bad in listOf("1057", "v", "v1.2", "V1057", "release-v12", "v99999999999", "v-3", "")) assertNull(bad, Updates.versionCodeOfTag(bad))
        assertTrue(Updates.isNewer(1058, 1057))
        assertFalse(Updates.isNewer(1057, 1057))
        assertFalse(Updates.isNewer(1056, 1057))
        // A local build has versionCode 1: every CI build (1000 + run number) is newer.
        assertTrue(Updates.isNewer(1001, 1))
    }

    @Test fun parsesTheLatestReleaseFixture() {
        val r = Updates.parseLatestRelease(release(), installedVersionCode = 1042)
        val info = (r as Outcome.Success).value!!
        assertEquals(1057, info.versionCode)
        assertEquals("0.57", info.versionName)
        assertEquals("https://github.com/Tra1c0/Guitar-Pro/releases/download/v1057/ArtiFret-0.57.apk", info.apkUrl)
        assertEquals(61_234_567L, info.sizeBytes)
        assertEquals(sha, info.sha256)
        assertEquals("- Rhythmus tippen: Raster 1/32\n- Update-Prüfung", info.notes)
    }

    @Test fun nothingToOfferIsSuccessNull() {
        fun none(body: String, installed: Int = 1042) = assertNull(body.take(60), (Updates.parseLatestRelease(body, installed) as Outcome.Success).value)
        none(release(), installed = 1057)                       // same build
        none(release(), installed = 1100)                       // installed is newer
        none(release(prerelease = true))
        none(release(draft = true))
        none(release(tag = "nightly-5"))                        // foreign tag scheme
        none(release(assetName = "ArtiFret-0.57.aab"))          // no APK
        none(release(size = 0))
        none(release(size = Updates.MAX_APK_BYTES + 1))
        none(release(url = "https://example.com/ArtiFret-0.57.apk"))
        none(release(url = "http://github.com/Tra1c0/Guitar-Pro/releases/download/v1057/ArtiFret-0.57.apk"))
        none(release(url = "https://github.com/someone/else/releases/download/v1057/ArtiFret-0.57.apk"))
    }

    @Test fun digestIsOptionalAndValidated() {
        assertNull((Updates.parseLatestRelease(release(digest = null), 1) as Outcome.Success).value!!.sha256)
        assertNull("not a sha256", (Updates.parseLatestRelease(release(digest = "md5:abc"), 1) as Outcome.Success).value!!.sha256)
        assertEquals(sha, (Updates.parseLatestRelease(release(digest = "sha256:${sha.uppercase()}"), 1) as Outcome.Success).value!!.sha256)
        // Asset without the ArtiFret-<name> scheme: the version name comes from the release title.
        assertEquals("0.57", (Updates.parseLatestRelease(release(assetName = "app-debug.apk"), 1) as Outcome.Success).value!!.versionName)
    }

    @Test fun unreadableAnswersAreFailures() {
        for (body in listOf("", "<html>rate limited</html>", "[]", "{\"tag_name\": ")) {
            val r = Updates.parseLatestRelease(body, 1)
            assertTrue(body, r is Outcome.Failure && r.code == FailureCode.CORRUPT)
        }
        // GitHub's „Not Found“ object is readable but no release.
        assertNull((Updates.parseLatestRelease("""{"message":"Not Found","documentation_url":"https://docs.github.com"}""", 1) as Outcome.Success).value)
    }

    @Test fun downloadPlausibility() {
        val info = UpdateInfo(1057, "0.57", "u", "", 100, sha)
        assertTrue(Updates.plausibleDownload(info, 100, sha))
        assertTrue(Updates.plausibleDownload(info, 100, sha.uppercase()))
        assertFalse(Updates.plausibleDownload(info, 99, sha))
        assertFalse(Updates.plausibleDownload(info, 100, "0".repeat(64)))
        assertTrue("no digest from GitHub: size only", Updates.plausibleDownload(info.copy(sha256 = null), 100, null))
    }

    @Test fun automaticCheckAtMostEverySixHours() {
        val h = 60L * 60 * 1000
        val now = 1_800_000_000_000L
        assertTrue("never checked", Updates.dueForAutomaticCheck(AppSettings(), now))
        assertFalse("switched off", Updates.dueForAutomaticCheck(AppSettings(updateChecks = false), now))
        assertFalse(Updates.dueForAutomaticCheck(AppSettings(lastUpdateCheckEpochMs = now - 5 * h), now))
        assertFalse(Updates.dueForAutomaticCheck(AppSettings(lastUpdateCheckEpochMs = now - 6 * h + 1), now))
        assertTrue(Updates.dueForAutomaticCheck(AppSettings(lastUpdateCheckEpochMs = now - 6 * h), now))
        assertTrue("clock set back: not stuck for ever", Updates.dueForAutomaticCheck(AppSettings(lastUpdateCheckEpochMs = now + 48 * h), now))
    }

    // --- UpdateCoordinator ---

    private class Settings(initial: AppSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private class FakeChecker(var answer: Outcome<UpdateInfo?>) : UpdateChecker {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun check(): Outcome<UpdateInfo?> { calls++; gate?.await(); return answer }
    }
    private class FakeInstaller(var answer: Outcome<UpdateInstallStep>) : UpdateInstaller {
        val installed = mutableListOf<UpdateInfo>()
        override suspend fun install(update: UpdateInfo, progress: (Long, Long) -> Unit): Outcome<UpdateInstallStep> {
            installed += update; progress(update.sizeBytes / 2, update.sizeBytes); return answer
        }
    }
    private val found = UpdateInfo(1057, "0.57", Updates.DOWNLOAD_PREFIX + "v1057/ArtiFret-0.57.apk", "notes", 1000, sha)

    @Test fun startCheckIsThrottledSilentAndAnnouncesOnlyNewBuilds() = runTest {
        var now = 1_800_000_000_000L
        val settings = Settings(AppSettings())
        val checker = FakeChecker(Outcome.Failure(FailureCode.IO, "offline"))
        val c = UpdateCoordinator(settings, checker, FakeInstaller(Outcome.Success(UpdateInstallStep.CONFIRMATION_REQUESTED)), 1042, "0.42", backgroundScope) { now }
        c.checkOnStart().join()
        assertEquals(1, checker.calls)
        assertEquals("attempt recorded", now, settings.settings.value.lastUpdateCheckEpochMs)
        assertEquals("offline: no message", UpdatePhase.IDLE, c.state.value.phase)
        assertNull(c.state.value.message); assertNull(c.state.value.announcement)

        now += 2 * 60 * 60 * 1000
        checker.answer = Outcome.Success(found)
        c.checkOnStart().join()
        assertEquals("throttled (2 h later)", 1, checker.calls)

        now += 4 * 60 * 60 * 1000
        c.checkOnStart().join()
        assertEquals(2, checker.calls)
        assertEquals(found, c.state.value.announcement)
        assertEquals(UpdatePhase.AVAILABLE, c.state.value.phase)
        c.dismissAnnouncement()
        assertNull(c.state.value.announcement)
        assertEquals("still installable from the settings", found, c.state.value.available)

        now += 7 * 60 * 60 * 1000
        checker.answer = Outcome.Success(null)
        c.checkOnStart().join()
        assertEquals(UpdatePhase.UP_TO_DATE, c.state.value.phase)
        assertNull(c.state.value.announcement)

        settings.settings.value = settings.settings.value.copy(updateChecks = false)
        now += 24 * 60 * 60 * 1000
        c.checkOnStart().join()
        assertEquals("switched off", 3, checker.calls)
    }

    @Test fun startCheckCanBeDisabledForTestRunsOfTheApp() = runTest {
        val settings = Settings(AppSettings())
        val checker = FakeChecker(Outcome.Success(found))
        val c = UpdateCoordinator(settings, checker, FakeInstaller(Outcome.Success(UpdateInstallStep.CONFIRMATION_REQUESTED)), 1042, "0.42", backgroundScope, automatic = false) { 1L }
        c.checkOnStart().join()
        assertEquals(0, checker.calls)
        assertEquals("no settings write", AppSettings(), settings.settings.value)
        c.checkNow().join()
        assertEquals("the manual check still works", UpdatePhase.AVAILABLE, c.state.value.phase)
    }

    @Test fun manualCheckReportsEveryResultAndIgnoresTheSwitch() = runTest {
        val checker = FakeChecker(Outcome.Failure(FailureCode.IO, "Keine Verbindung zu GitHub"))
        val settings = Settings(AppSettings(updateChecks = false, lastUpdateCheckEpochMs = 5L))
        val c = UpdateCoordinator(settings, checker, FakeInstaller(Outcome.Success(UpdateInstallStep.CONFIRMATION_REQUESTED)), 1042, "0.42", backgroundScope) { 10L }
        c.checkNow().join()
        assertEquals(UpdatePhase.FAILED, c.state.value.phase)
        assertEquals("Keine Verbindung zu GitHub", c.state.value.message)
        checker.answer = Outcome.Success(null)
        c.checkNow().join()
        assertEquals(UpdatePhase.UP_TO_DATE, c.state.value.phase)
        assertNull(c.state.value.message)
        checker.answer = Outcome.Success(found)
        c.checkNow().join()
        assertEquals(UpdatePhase.AVAILABLE, c.state.value.phase)
        assertNull("manual check: shown on the page, no start notice", c.state.value.announcement)
        assertEquals("manual check does not touch the throttle", 5L, settings.settings.value.lastUpdateCheckEpochMs)
    }

    @Test fun oneCheckAtATime() = runTest {
        val checker = FakeChecker(Outcome.Success(found)).apply { gate = CompletableDeferred() }
        val c = UpdateCoordinator(Settings(AppSettings()), checker, FakeInstaller(Outcome.Success(UpdateInstallStep.CONFIRMATION_REQUESTED)), 1042, "0.42", backgroundScope) { 1L }
        val first = c.checkNow()
        testScheduler.runCurrent()
        assertEquals(UpdatePhase.CHECKING, c.state.value.phase)
        c.checkNow().join()
        c.install().join()
        assertEquals(1, checker.calls)
        checker.gate!!.complete(Unit)
        first.join()
        assertEquals(UpdatePhase.AVAILABLE, c.state.value.phase)
    }

    @Test fun installReportsProgressPermissionAndFailure() = runTest {
        val installer = FakeInstaller(Outcome.Success(UpdateInstallStep.PERMISSION_NEEDED))
        val c = UpdateCoordinator(Settings(AppSettings()), FakeChecker(Outcome.Success(found)), installer, 1042, "0.42", backgroundScope) { 1L }
        c.install().join()
        assertTrue("nothing found yet: nothing to install", installer.installed.isEmpty())
        c.checkNow().join()
        c.install().join()
        assertEquals(listOf(found), installer.installed)
        assertEquals(UpdatePhase.PERMISSION_NEEDED, c.state.value.phase)
        assertEquals(500L, c.state.value.downloadedBytes)
        assertEquals(1000L, c.state.value.totalBytes)
        installer.answer = Outcome.Success(UpdateInstallStep.CONFIRMATION_REQUESTED)
        c.install().join()
        assertEquals(UpdatePhase.CONFIRMING, c.state.value.phase)
        installer.answer = Outcome.Failure(FailureCode.CORRUPT, "Download beschädigt (Prüfsumme)")
        c.install().join()
        assertEquals(UpdatePhase.FAILED, c.state.value.phase)
        assertEquals("Download beschädigt (Prüfsumme)", c.state.value.message)
        assertEquals("retry stays possible", found, c.state.value.available)
    }
}
