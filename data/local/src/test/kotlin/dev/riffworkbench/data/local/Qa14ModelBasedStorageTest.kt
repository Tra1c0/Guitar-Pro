package dev.riffworkbench.data.local

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA cycle 14: model-based randomised crash-consistency testing of data/local.
 * Crash points are taken deterministically *inside* operations: a permissive SecurityManager sees every
 * mutating file call (open-for-write, rename, mkdir, delete) and copies the repository root just before the
 * k-th one. That image is a legal state after a process kill. Independently, files are truncated/deleted.
 * Run with QA14_N=1000 QA14_LEN=40 (env) or -Dqa14.n for the campaign; default is a short smoke run.
 */
class Qa14ModelBasedStorageTest {
    sealed class Op {
        data class Create(val seed: Int, val audio: Boolean) : Op()
        /** mode 0 right, 1 stale (older), 2 null on existing, 3 right with revision jump, 4 not-increasing revision. */
        data class Save(val p: Int, val mode: Int, val seed: Int) : Op()
        data class Open(val p: Int) : Op()
        data class Delete(val p: Int) : Op()
        data class SaveDeleted(val p: Int) : Op()
        data class RetryCreate(val p: Int) : Op()
        data class Snap(val p: Int, val action: Int, val k: Int) : Op()
        data class AssetPut(val seed: Int, val reuse: Int?) : Op()
        data class AssetGet(val k: Int) : Op()
        data class Exchange(val p: Int) : Op()
        data class SettingsUpdate(val seed: Int) : Op()
        data class Race(val p: Int) : Op()
        data class CrashDuring(val inner: Op, val k: Int, val tearTemps: Boolean) : Op()
        /** category 0 head, 1 head.previous, 2 revision, 3 temp, 4 asset, 5 settings, 6 snapshot, 7 orphan; mode 0 truncate, 1 delete, 2 empty. */
        data class Corrupt(val category: Int, val pick: Int, val mode: Int, val cut: Int) : Op()
    }
    data class Violation(val inv: String, val step: Int, val msg: String)

    // ------------------------------------------------------------------ crash-point recorder
    object Recorder {
        class Rec(val root: String, val k: Int) { var count = 0; var image: Map<String, ByteArray?>? = null }
        private val recs = ConcurrentHashMap<String, Rec>()
        private val busy = ThreadLocal.withInitial { false }
        @Volatile private var installed = false
        @Synchronized fun install() {
            if (installed) return
            @Suppress("DEPRECATION")
            System.setSecurityManager(object : SecurityManager() {
                override fun checkPermission(perm: java.security.Permission?) {}
                override fun checkPermission(perm: java.security.Permission?, context: Any?) {}
                override fun checkWrite(file: String) = hit(file)
                override fun checkDelete(file: String) = hit(file)
            })
            installed = true
        }
        private fun hit(file: String) {
            if (recs.isEmpty() || busy.get()) return
            val rec = recs.values.firstOrNull { file.startsWith(it.root) } ?: return
            busy.set(true)
            try { synchronized(rec) { rec.count++; if (rec.count == rec.k && rec.image == null) rec.image = image(File(rec.root)) } } finally { busy.set(false) }
        }
        fun arm(root: File, k: Int): Rec = Rec(root.absolutePath + File.separator, k).also { recs[it.root] = it }
        fun disarm(rec: Rec) { recs.remove(rec.root) }
        fun image(root: File): Map<String, ByteArray?> {
            val out = LinkedHashMap<String, ByteArray?>()
            root.walkTopDown().forEach { f -> if (f != root) out[f.relativeTo(root).path] = if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null }
            return out
        }
        fun restore(root: File, image: Map<String, ByteArray?>) {
            root.deleteRecursively(); root.mkdirs()
            image.entries.sortedBy { it.key.length }.forEach { (path, bytes) ->
                val f = File(root, path)
                if (bytes == null) f.mkdirs() else { f.parentFile.mkdirs(); f.writeBytes(bytes) }
            }
        }
    }

    // ------------------------------------------------------------------ model
    class PModel(val id: EntityId, val history: MutableList<Project> = mutableListOf(), var deleted: Boolean = false, var pending: Project? = null) {
        val cur get() = history.last()
        fun copy() = PModel(id, history.toMutableList(), deleted, pending)
    }
    data class Model(val projects: MutableList<PModel>, val snapshots: MutableMap<String, Project>, val assets: MutableMap<AssetHash, ByteArray>, val damaged: MutableSet<AssetHash>, var settings: AppSettings, val brokenSnapshots: MutableSet<String> = mutableSetOf()) {
        fun copy() = Model(projects.map { it.copy() }.toMutableList(), snapshots.toMutableMap(), assets.toMutableMap(), damaged.toMutableSet(), settings, brokenSnapshots.toMutableSet())
    }

    class Runner(val base: File, val tag: String, val record: Boolean = false) {
        val root = File(base, "root").apply { mkdirs() }
        var repo = FileProjectRepository(root)
        var settingsRepo = FileSettingsRepository(File(root, "settings.json"))
        var store = FileAssetStore(File(root, "assets"))
        val violations = mutableListOf<Violation>()
        var step = 0
        private var idN = 0
        val ids = IdSource { EntityId("${tag}i${idN++}") }
        var m = Model(mutableListOf(), mutableMapOf(), mutableMapOf(), mutableSetOf(), AppSettings())
        val trace = mutableListOf<String>()
        var crashes = 0; var corruptions = 0; var bothHeadsDead = 0

        fun tolerate(what: String) = (System.getProperty("qa14.tolerate") ?: System.getenv("QA14_TOLERATE") ?: "").split(",").contains(what)
        fun v(inv: String, msg: String) { violations += Violation(inv, step, msg) }
        fun strip(p: Project) = p.copy(diagnostics = p.diagnostics.filterNot { it.code == "recovered" })
        fun live() = m.projects.filter { !it.deleted && it.history.isNotEmpty() }
        fun pick(i: Int) = live().let { if (it.isEmpty()) null else it[Math.floorMod(i, it.size)] }

        fun edit(p: Project, seed: Int): Project {
            val r = Random(seed); val s = EditorSession(p, ids)
            when (r.nextInt(3)) {
                0 -> s.dispatch(EditorCommand.Rename("T${r.nextInt(100000)}"))
                1 -> s.dispatch(EditorCommand.SetTempo(60.0 + r.nextInt(120)))
                else -> s.dispatch(EditorCommand.PutNote(fret = r.nextInt(20)))
            }
            return s.state.value.project
        }

        suspend fun run(op: Op) {
            when (op) {
                is Op.Create -> {
                    val r = Random(op.seed)
                    var p = ProjectFactory(ids).create("P${r.nextInt(1000)}", withBand = r.nextBoolean())
                    if (op.audio) {
                        val bytes = Random(op.seed).nextBytes(1 + r.nextInt(3000))
                        val d = (store.put("ref.wav", "audio/wav", bytes) as? Outcome.Success)?.value ?: return v("I6-asset", "put failed")
                        m.assets[d.hash] = bytes; m.damaged -= d.hash
                        p = p.copy(audio = AudioArrangement(listOf(d), listOf(AudioClip(ids.next(), d.hash, "ref"))))
                    }
                    val res = repo.save(p, null)
                    if (res is Outcome.Success) m.projects += PModel(p.id, mutableListOf(p)) else v("I0-create", "create failed: $res")
                }
                is Op.RetryCreate -> {
                    val pm = m.projects.filter { it.pending != null }.let { if (it.isEmpty()) return else it[Math.floorMod(op.p, it.size)] }
                    val res = repo.save(pm.pending!!, null)
                    if (res is Outcome.Success) { pm.history += pm.pending!!; pm.pending = null } else v("I8-retry-create", "save(null) of an uncommitted create failed: $res")
                }
                is Op.Save -> {
                    val pm = pick(op.p) ?: return
                    val cur = pm.cur
                    val next = edit(cur, op.seed)
                    val (proj, expected, ok) = when (op.mode) {
                        0 -> Triple(next.copy(revision = cur.revision + 1), cur.revision, true)
                        1 -> Triple(next.copy(revision = cur.revision + 1), cur.revision - 1 - (op.seed and 3), false)
                        2 -> Triple(next.copy(revision = cur.revision + 1), null, false)
                        3 -> Triple(next.copy(revision = cur.revision + 2 + (op.seed and 7)), cur.revision, true)
                        else -> Triple(next.copy(revision = cur.revision), cur.revision, false)
                    }
                    val res = repo.save(proj, expected)
                    if (ok) { if (res is Outcome.Success) pm.history += proj else v("I2-save", "save failed: $res") }
                    else {
                        if (res !is Outcome.Failure || res.code != FailureCode.CONFLICT) v("I3-stale", "mode ${op.mode} expected=$expected stored=${cur.revision}: $res")
                        checkOpen(pm, "after stale save")
                    }
                }
                is Op.Open -> { val pm = pick(op.p) ?: return; checkOpen(pm, "open") }
                is Op.Delete -> {
                    val pm = pick(op.p) ?: return
                    val res = repo.delete(pm.id)
                    if (res is Outcome.Success) { pm.deleted = true; checkGone(pm) } else v("I4-delete", "delete failed: $res")
                }
                is Op.SaveDeleted -> {
                    val pm = m.projects.filter { it.deleted && it.history.isNotEmpty() }.let { if (it.isEmpty()) return else it[Math.floorMod(op.p, it.size)] }
                    val res = repo.save(pm.cur.copy(revision = pm.cur.revision + 1), pm.cur.revision)
                    if (res !is Outcome.Failure || res.code != FailureCode.CONFLICT) v("I4-resurrect", "save to deleted project: $res")
                    val snap = repo.create(pm.cur, "late")
                    if (snap is Outcome.Success) v("I4-resurrect", "snapshot of deleted project succeeded")
                    checkGone(pm)
                }
                is Op.Snap -> {
                    val pm = pick(op.p) ?: return
                    when (op.action) {
                        0 -> when (val res = repo.create(pm.cur, "s${op.k}")) {
                            is Outcome.Success -> m.snapshots["${pm.id.value}/${res.value.id}"] = pm.cur
                            is Outcome.Failure -> v("I0-snapshot", "create failed: $res")
                        }
                        1 -> checkSnapshots(pm, false)
                        else -> {
                            val keys = m.snapshots.keys.filter { it.startsWith("${pm.id.value}/") }
                            if (keys.isNotEmpty()) {
                                val key = keys[Math.floorMod(op.k, keys.size)]
                                when (val res = repo.load(pm.id, key.substringAfter('/'))) {
                                    is Outcome.Success -> if (res.value != m.snapshots[key]) v("I0-snapshot", "snapshot content differs")
                                    is Outcome.Failure -> v("I0-snapshot", "load failed: $res")
                                }
                            }
                        }
                    }
                }
                is Op.AssetPut -> {
                    val bytes = if (op.reuse != null && m.assets.isNotEmpty()) m.assets.values.toList()[Math.floorMod(op.reuse, m.assets.size)] else Random(op.seed).nextBytes(Random(op.seed).nextInt(4000))
                    when (val res = store.put("a.bin", "application/octet-stream", bytes)) {
                        is Outcome.Success -> {
                            if (res.value.hash.value != sha256(bytes)) v("I6-asset", "descriptor hash wrong")
                            m.assets[res.value.hash] = bytes; m.damaged -= res.value.hash
                            checkAsset(res.value.hash, mustExist = true)
                        }
                        is Outcome.Failure -> v("I6-asset", "put failed: $res")
                    }
                }
                is Op.AssetGet -> { if (m.assets.isNotEmpty()) checkAsset(m.assets.keys.toList()[Math.floorMod(op.k, m.assets.size)], mustExist = false) }
                is Op.Exchange -> {
                    val pm = pick(op.p) ?: return
                    val p = pm.cur
                    val refs = p.audio.assets.map { it.hash }
                    val exchange = FileNativeProjectExchange(store)
                    when (val ex = exchange.export(p)) {
                        is Outcome.Failure -> if (refs.all { it in m.assets && it !in m.damaged }) v("I7-exchange", "export failed: $ex")
                        is Outcome.Success -> {
                            val otherStoreDir = File(base, "x${step}").also { it.deleteRecursively() }
                            val other = FileAssetStore(otherStoreDir)
                            val newId = ids.next()
                            when (val im = FileNativeProjectExchange(other).import(ex.value.bytes, newId)) {
                                is Outcome.Failure -> v("I7-exchange", "import failed: $im")
                                is Outcome.Success -> {
                                    if (im.value != p.copy(id = newId, revision = 0)) v("I7-exchange", "round trip differs")
                                    for (h in refs) { val g = other.get(h); if (g !is Outcome.Success || !g.value.contentEquals(m.assets[h])) v("I7-exchange", "asset not carried") }
                                    // and into the main library
                                    val im2 = exchange.import(ex.value.bytes, newId)
                                    if (im2 is Outcome.Success) {
                                        val res = repo.save(im2.value, null)
                                        if (res is Outcome.Success) m.projects += PModel(newId, mutableListOf(im2.value)) else v("I7-exchange", "save of import failed: $res")
                                    } else v("I7-exchange", "second import failed: $im2")
                                }
                            }
                            otherStoreDir.deleteRecursively()
                        }
                    }
                }
                is Op.SettingsUpdate -> {
                    val r = Random(op.seed)
                    val s = AppSettings(r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), RetuningPolicy.values()[r.nextInt(RetuningPolicy.values().size)], listOf("fx${r.nextInt(50)}"), mapOf("k${r.nextInt(5)}" to "cmd${r.nextInt(9)}"))
                    val res = settingsRepo.update(s)
                    if (res is Outcome.Success) m.settings = s else v("I0-settings", "update failed: $res")
                }
                is Op.Race -> {
                    val pm = pick(op.p) ?: return
                    val next = pm.cur.copy(revision = pm.cur.revision + 1, metadata = pm.cur.metadata.copy(title = "race"))
                    val (sv, del) = kotlinx.coroutines.coroutineScope {
                        val a = async(Dispatchers.IO) { repo.save(next, pm.cur.revision) }
                        val b = async(Dispatchers.IO) { repo.delete(pm.id) }
                        a.await() to b.await()
                    }
                    if (del !is Outcome.Success) v("I4-race", "delete failed: $del")
                    if (sv is Outcome.Failure && sv.code != FailureCode.CONFLICT && sv.code != FailureCode.IO) v("I4-race", "save failed oddly: $sv")
                    pm.deleted = true
                    checkGone(pm)
                }
                is Op.CrashDuring -> crashDuring(op)
                is Op.Corrupt -> corrupt(op)
            }
        }

        // ------------------------------------------------------------ checks against the live repository
        suspend fun checkOpen(pm: PModel, what: String) {
            when (val res = repo.open(pm.id)) {
                is Outcome.Success -> if (strip(res.value) != pm.cur) v("I2-durable", "$what: open returned revision ${res.value.revision}, model ${pm.cur.revision}")
                is Outcome.Failure -> v("I2-durable", "$what: open failed $res")
            }
        }
        suspend fun checkGone(pm: PModel) {
            if (repo.open(pm.id) is Outcome.Success) v("I4-resurrect", "deleted project ${pm.id} opens")
            if (File(root, "projects/${pm.id.value}").exists()) v("I4-resurrect", "deleted project dir exists")
            if (repo.projects.value.any { it.id == pm.id }) v("I4-resurrect", "deleted project listed")
        }
        suspend fun checkAsset(h: AssetHash, mustExist: Boolean) {
            when (val g = store.get(h)) {
                is Outcome.Success -> if (sha256(g.value) != h.value || !g.value.contentEquals(m.assets[h])) v("I6-asset", "get returned bytes not matching hash")
                is Outcome.Failure -> if (mustExist || h !in m.damaged) v("I6-asset", "get failed for healthy asset: $g")
            }
        }
        suspend fun checkSnapshots(pm: PModel, lenient: Boolean) {
            when (val l = repo.list(pm.id)) {
                is Outcome.Failure -> v("I0-snapshot", "list failed: $l")
                is Outcome.Success -> {
                    val listed = l.value.map { "${pm.id.value}/${it.id}" }.toSet()
                    val modelled = m.snapshots.keys.filter { it.startsWith("${pm.id.value}/") }.toSet()
                    if (!lenient && (listed - m.brokenSnapshots) != modelled) v("I0-snapshot", "listed $listed, model $modelled")
                    for (key in listed) {
                        val expected = m.snapshots[key]
                        when (val res = repo.load(pm.id, key.substringAfter('/'))) {
                            is Outcome.Success -> if (expected == null) { if (key !in m.brokenSnapshots) v("I1-snapshot", "unknown snapshot listed") else v("I1-snapshot", "broken snapshot loads again") } else if (res.value != expected) v("I1-snapshot", "snapshot content differs")
                            is Outcome.Failure -> if (lenient && m.snapshots.containsKey(key)) { m.snapshots.remove(key); m.brokenSnapshots += key } else if (key !in m.brokenSnapshots) v("I0-snapshot", "listed snapshot does not load: $res")
                        }
                    }
                    if (lenient) m.snapshots.keys.removeAll { it.startsWith("${pm.id.value}/") && it !in listed }
                }
            }
        }

        /** Reopen fresh instances after a crash or a corruption and reconcile the model with what is legal. */
        suspend fun reopenAndCheck(before: Model?, corruptedAsset: AssetHash?) {
            repo = FileProjectRepository(root); store = FileAssetStore(File(root, "assets")); settingsRepo = FileSettingsRepository(File(root, "settings.json"))
            val oracle = before == null // corruption: use on-disk oracle; crash: old/new model states
            // projects
            val snapshotKeysBefore = m.snapshots.keys.toSet()
            for (pm in m.projects.toList()) {
                val old = before?.projects?.firstOrNull { it.id == pm.id }
                val res = try { repo.open(pm.id) } catch (t: Throwable) { v("I1-exception", "open threw $t"); continue }
                val got = (res as? Outcome.Success)?.value?.let(::strip)
                if (got != null && got.id != pm.id) { v("I1-foreign", "open(${pm.id}) returned data of ${got.id}"); continue }
                if (pm.deleted) {
                    if (got == null) continue
                    if (!oracle && old != null && !old.deleted && got == old.history.lastOrNull()) pm.deleted = false // crash before the delete committed
                    else v("I4-resurrect", "deleted ${pm.id} opens after reopen (rev ${got.revision})")
                    continue
                }
                if (pm.history.isEmpty()) { // uncommitted create (pending): must not appear
                    if (got != null) { if (got == pm.pending) { pm.history += got; pm.pending = null } else v("I1-crash", "pending create opens with unexpected content") }
                    continue
                }
                if (oracle) {
                    val dir = File(root, "projects/${pm.id.value}")
                    val primary = intactHead(dir, "head.json"); val previous = intactHead(dir, "head.previous.json")
                    val allowed = pm.history.takeLast(2)
                    when {
                        got == null && (primary != null || previous != null) -> v("I1-recover", "open failed although an intact head exists (head=${primary?.revision} previous=${previous?.revision}): $res")
                        got == null -> { bothHeadsDead++; if (pm.history.size >= 2) println("QA14 DEAD $tag hist=${pm.history.size} files=${dir.list()?.sorted()} trace=${trace.takeLast(6)}"); pm.history.clear(); val d = repo.delete(pm.id); if (d !is Outcome.Success) v("I8-broken", "unreadable project cannot be deleted: $d"); pm.deleted = true }
                        got !in allowed -> v("I1-recover", "open returned rev ${got.revision}, allowed ${allowed.map { it.revision }}")
                        primary != null && got != primary -> v("I1-recover", "fell back to rev ${got.revision} although head.json (rev ${primary.revision}) is intact")
                        else -> if (got != pm.cur) pm.history.removeAt(pm.history.size - 1)
                    }
                    if (got != null && (res as Outcome.Success).value.diagnostics.any { it.code == "recovered" } != (primary == null)) v("I1-recover", "recovered diagnostic mismatch (head intact=${primary != null})")
                } else {
                    val newCur = pm.cur; val oldCur = old?.history?.lastOrNull()
                    when {
                        got == newCur -> {}
                        got != null && got == oldCur -> { pm.history.clear(); pm.history += old!!.history }
                        got == null && oldCur == null -> { pm.pending = newCur; pm.history.clear() }
                        got == null -> v("I1-crash", "open failed after crash (old rev ${oldCur?.revision}, new ${newCur.revision}): $res")
                        else -> v("I1-crash", "open returned rev ${got.revision}, legal ${listOfNotNull(newCur, oldCur).map { it.revision }}")
                    }
                }
            }
            // a crash may have prevented a create from being recorded: projects in new model but not old with no history
            m.projects.removeAll { it.history.isEmpty() && it.pending == null && !it.deleted }
            // listing: every listed project opens and matches the model; no phantom issues after a pure crash
            val ref = repo.refresh()
            if (ref !is Outcome.Success) v("I5-list", "refresh failed $ref")
            for (s in repo.projects.value) {
                val o = repo.open(s.id)
                if (o !is Outcome.Success) v("I5-list", "listed ${s.id} does not open: $o")
                val pm = m.projects.firstOrNull { it.id == s.id }
                if (pm == null || pm.deleted || pm.history.isEmpty()) v("I5-list", "listed ${s.id} is not a live committed project (pending=${pm?.pending != null})")
            }
            for (pm in live()) if (repo.projects.value.none { it.id == pm.id }) v("I5-list", "live ${pm.id} not listed")
            if (!oracle && !tolerate("phantom") && repo.issues.value.isNotEmpty()) v("I1-phantom", "after a pure crash the library reports ${repo.issues.value.map { it.message.take(90) }}")
            // assets
            for (h in m.assets.keys.toList()) when (val g = store.get(h)) {
                is Outcome.Success -> if (!g.value.contentEquals(m.assets[h]) || sha256(g.value) != h.value) v("I6-asset", "asset bytes do not match hash after reopen")
                is Outcome.Failure -> when {
                    h == corruptedAsset -> m.damaged += h
                    before != null && h !in before.assets -> { m.assets.remove(h) } // put not yet committed
                    h in m.damaged -> {}
                    before != null && h in before.damaged -> m.damaged += h // the repairing put did not commit
                    else -> v("I6-asset", "healthy asset unreadable after reopen: $g")
                }
            }
            // settings
            val lr = settingsRepo.load()
            val sv = settingsRepo.settings.value
            if (oracle) { m.settings = sv } else {
                if (lr !is Outcome.Success) v("I1-settings", "settings load failed after crash: $lr")
                if (sv != m.settings && sv != before?.settings) v("I1-settings", "settings after crash $sv not in {old,new}")
                m.settings = sv
            }
            // snapshots
            for (pm in live()) checkSnapshots(pm, lenient = true)
            if (before != null) for (key in before.snapshots.keys) if (key in snapshotKeysBefore && key !in m.snapshots && live().any { key.startsWith("${it.id.value}/") }) v("I1-snapshot", "committed snapshot $key lost by a crash")
            for (key in m.snapshots.keys.toList()) if (live().none { key.startsWith("${it.id.value}/") }) m.snapshots.remove(key)
            // invariant 8: later saves still work
            for (pm in live()) {
                val next = pm.cur.copy(revision = pm.cur.revision + 1, metadata = pm.cur.metadata.copy(title = "probe${step}"))
                when (val res = repo.save(next, pm.cur.revision)) {
                    is Outcome.Success -> pm.history += next
                    is Outcome.Failure -> v("I8-broken", "save after reopen failed for ${pm.id}: $res")
                }
                checkOpen(pm, "probe")
            }
            val su = settingsRepo.update(m.settings.copy(darkTheme = !m.settings.darkTheme))
            if (su is Outcome.Success) m.settings = m.settings.copy(darkTheme = !m.settings.darkTheme) else v("I8-broken", "settings update after reopen failed: $su")
            if (FileSettingsRepository(File(root, "settings.json")).let { it.load(); it.settings.value } != m.settings) v("I2-durable", "settings update not durable")
        }

        fun intactHead(dir: File, name: String): Project? = runCatching {
            val h = projectJson.decodeFromString<ProjectHead>(File(dir, name).readText())
            val b = File(dir, h.file).readBytes(); check(sha256(b) == h.sha256)
            decodeProject(b).also { check(it.revision == h.revision) }
        }.getOrNull()

        suspend fun crashDuring(op: Op.CrashDuring) {
            if (op.inner is Op.CrashDuring || op.inner is Op.Corrupt || op.inner is Op.Race) return
            val before = m.copy()
            Recorder.install()
            val rec = Recorder.arm(root, op.k)
            val vBefore = violations.size
            try { run(op.inner) } finally { Recorder.disarm(rec) }
            if (violations.size > vBefore) return
            val image = rec.image ?: Recorder.image(root)
            crashes++
            Recorder.restore(root, image)
            if (op.tearTemps) File(root, "").walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { f -> f.writeBytes(f.readBytes().copyOf(f.length().toInt() / 2)) }
            reopenAndCheck(before, null)
        }

        suspend fun corrupt(op: Op.Corrupt) {
            val files = root.walkTopDown().filter { it.isFile }.toList().sortedBy { it.path }
            val candidates = files.filter { f ->
                when (op.category) {
                    0 -> f.name == "head.json"
                    1 -> f.name == "head.previous.json"
                    2 -> f.name.matches(Regex("revision-[0-9]+\\.json"))
                    3 -> f.name.endsWith(".tmp")
                    4 -> f.parentFile.name == "assets" && !f.name.startsWith(".")
                    5 -> f.name == "settings.json"
                    6 -> f.parentFile.name == "snapshots"
                    else -> f.name.startsWith("orphan-")
                }
            }
            if (candidates.isEmpty()) return
            val f = candidates[Math.floorMod(op.pick, candidates.size)]
            when (op.mode) {
                0 -> f.writeBytes(f.readBytes().let { it.copyOf(if (it.isEmpty()) 0 else Math.floorMod(op.cut, it.size)) })
                1 -> f.delete()
                else -> f.writeBytes(ByteArray(0))
            }
            corruptions++
            trace += "corrupted ${f.relativeTo(root)} mode ${op.mode}"
            val corruptedAsset = if (op.category == 4) AssetHash(f.name) else null
            if (corruptedAsset != null) m.damaged += corruptedAsset
            reopenAndCheck(null, corruptedAsset)
        }
    }

    companion object {
        fun randomOp(r: Random, depth: Int = 0): Op = when (r.nextInt(100)) {
            in 0..9 -> Op.Create(r.nextInt(), r.nextInt(3) == 0)
            in 10..29 -> Op.Save(r.nextInt(), listOf(0, 0, 0, 1, 2, 3, 4)[r.nextInt(7)], r.nextInt())
            in 30..34 -> Op.Open(r.nextInt())
            in 35..38 -> Op.Delete(r.nextInt())
            in 39..40 -> Op.SaveDeleted(r.nextInt())
            in 41..42 -> Op.RetryCreate(r.nextInt())
            in 43..49 -> Op.Snap(r.nextInt(), r.nextInt(3), r.nextInt())
            in 50..53 -> Op.AssetPut(r.nextInt(), if (r.nextBoolean()) r.nextInt() else null)
            in 54..55 -> Op.AssetGet(r.nextInt())
            in 56..59 -> Op.Exchange(r.nextInt())
            in 60..63 -> Op.SettingsUpdate(r.nextInt())
            in 64..66 -> Op.Race(r.nextInt())
            in 67..86 -> if (depth > 0) Op.Open(r.nextInt()) else Op.CrashDuring(
                listOf(Op.Create(r.nextInt(), r.nextInt(3) == 0), Op.Save(r.nextInt(), 0, r.nextInt()), Op.Save(r.nextInt(), 3, r.nextInt()), Op.Delete(r.nextInt()),
                    Op.Snap(r.nextInt(), 0, r.nextInt()), Op.AssetPut(r.nextInt(), if (r.nextBoolean()) r.nextInt() else null), Op.SettingsUpdate(r.nextInt()), Op.Exchange(r.nextInt()), Op.RetryCreate(r.nextInt()))[r.nextInt(9)],
                1 + r.nextInt(24), r.nextInt(4) == 0)
            else -> Op.Corrupt(r.nextInt(8), r.nextInt(), r.nextInt(3), r.nextInt())
        }
        fun randomSteps(seed: Long, len: Int): List<Op> { val r = Random(seed); return List(len) { randomOp(r) } }
    }

    val scratch: File = File(System.getProperty("qa14.tmp") ?: System.getenv("QA14_TMP") ?: System.getProperty("java.io.tmpdir"))
    private val counter = AtomicInteger()

    fun execute(steps: List<Op>, label: String, stats: Runner.() -> Unit = {}): List<Violation> {
        val base = Files.createTempDirectory(scratch.toPath(), "qa14").toFile()
        val r = Runner(base, "s${label.filter { it.isLetterOrDigit() }}x${counter.incrementAndGet()}")
        try {
            runBlocking {
                for ((i, op) in steps.withIndex()) {
                    r.step = i; r.trace += op.toString().take(160)
                    try { r.run(op) } catch (t: Throwable) { r.v("I1-exception", "${op::class.simpleName} threw $t") }
                    if (r.violations.isNotEmpty()) break
                }
            }
            r.stats()
            return r.violations
        } finally { base.deleteRecursively() }
    }

    fun fails(steps: List<Op>, inv: String) = execute(steps, "shr").any { it.inv == inv }
    fun shrink(steps: List<Op>, inv: String): List<Op> {
        var cur = steps
        // the failing prefix is enough
        execute(cur, "shr").firstOrNull { it.inv == inv }?.let { cur = cur.take(it.step + 1) }
        var chunk = cur.size / 2
        while (chunk >= 1) {
            var i = 0; var changed = false
            while (i < cur.size) {
                val cand = cur.take(i) + cur.drop(i + chunk)
                if (cand.isNotEmpty() && fails(cand, inv)) { cur = cand; changed = true } else i += chunk
            }
            if (!changed) chunk /= 2
        }
        return cur
    }

    private fun prop(name: String) = System.getProperty("qa14.$name") ?: System.getenv("QA14_${name.uppercase()}")

    @Test fun randomisedStorageModel() {
        val n = prop("n")?.toInt() ?: 30
        val len = prop("len")?.toInt() ?: 30
        val baseSeed = prop("seed")?.toLong() ?: 14_000L
        val results = ConcurrentLinkedQueue<Pair<Long, List<Violation>>>()
        val crashes = AtomicInteger(); val corruptions = AtomicInteger(); val dead = AtomicInteger(); val ran = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        for (k in 0 until n) {
            val seed = baseSeed + k
            pool.submit {
                try {
                    val v = execute(randomSteps(seed, len), "r$k") { crashes.addAndGet(this.crashes); corruptions.addAndGet(this.corruptions); dead.addAndGet(bothHeadsDead) }
                    ran.incrementAndGet()
                    if (v.isNotEmpty()) { results += seed to v; println("QA14 FAIL seed=$seed ${v.first()}") }
                } catch (t: Throwable) { println("QA14 RUNNER CRASH seed=$seed $t"); results += seed to listOf(Violation("runner", -1, t.toString())) }
            }
        }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.HOURS)
        println("QA14 ran=${ran.get()} sequences x $len ops, crash images=${crashes.get()}, corruptions=${corruptions.get()}, both-heads-dead=${dead.get()}, failing=${results.size}")
        val byInv = results.flatMap { (seed, vs) -> vs.map { it.inv }.distinct().map { it to seed } }.groupBy({ it.first }, { it.second })
        for ((inv, seeds) in byInv) println("QA14 invariant $inv: ${seeds.size} sequences, seeds=${seeds.sorted().take(10)}")
        if (prop("shrink") != "false") for ((inv, seeds) in byInv) {
            val seed = seeds.min()
            val min = shrink(randomSteps(seed, len), inv)
            println("QA14 MINIMAL $inv (seed=$seed, ${min.size} steps):")
            min.forEach { println("   $it") }
            println("   -> ${execute(min, "fin").firstOrNull { it.inv == inv }}")
        }
        assertTrue("violations: ${byInv.keys} (seeds ${results.map { it.first }.sorted().take(20)})", results.isEmpty())
    }
}
