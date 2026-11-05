package dev.riffworkbench.data.local

import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

object NativeArchiveLimits {
    const val MAX_COMPRESSED = 64L * 1024 * 1024
    const val MAX_ENTRY = 64L * 1024 * 1024
    const val MAX_TOTAL = 256L * 1024 * 1024
    const val MAX_ENTRIES = 1024
}
@Serializable internal data class BundleManifest(val format: String = "riffworkbench-project", val schemaVersion: Int = 1, val projectHash: String, val assets: List<AssetDescriptor>)
internal data class NativeBundle(val project: Project, val assets: Map<AssetHash, ByteArray>, val descriptors: List<AssetDescriptor>)

internal object NativeProjectCodec {
    fun encode(project: Project, assets: Map<AssetHash, ByteArray>): ByteArray {
        validate(project)
        val descriptors = (project.assets + project.audio.assets).distinctBy { it.hash }
        require(references(project).all { ref -> descriptors.any { it.hash == ref } }) { "Asset-Beschreibung fehlt" }
        val data = projectJson.encodeToString(project).toByteArray()
        val manifest = BundleManifest(projectHash = sha256(data), assets = descriptors)
        val entries = linkedMapOf("manifest.json" to projectJson.encodeToString(manifest).toByteArray(), "project.json" to data)
        descriptors.forEach { descriptor ->
            val bytes = assets[descriptor.hash] ?: throw IllegalArgumentException("Asset fehlt: ${descriptor.name}")
            require(sha256(bytes) == descriptor.hash.value && bytes.size.toLong() == descriptor.sizeBytes) { "Asset ist beschädigt" }
            entries["assets/${descriptor.hash.value}"] = bytes
        }
        require(entries.size <= NativeArchiveLimits.MAX_ENTRIES && entries.values.sumOf { it.size.toLong() } <= NativeArchiveLimits.MAX_TOTAL) { "RESOURCE_LIMIT: Projektarchiv zu groß" }
        require(entries.values.all { it.size <= NativeArchiveLimits.MAX_ENTRY }) { "RESOURCE_LIMIT: Archiveintrag zu groß" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray().also { require(it.size <= NativeArchiveLimits.MAX_COMPRESSED) { "RESOURCE_LIMIT: Komprimiertes Archiv zu groß" } }
    }

    fun decode(bytes: ByteArray): NativeBundle {
        require(bytes.size <= NativeArchiveLimits.MAX_COMPRESSED) { "RESOURCE_LIMIT: Archiv zu groß" }
        val entries = mutableMapOf<String, ByteArray>()
        var total = 0L
        var declared: BundleManifest? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name == "manifest.json" || entry.name == "project.json" || entry.name.matches(Regex("assets/[a-f0-9]{64}"))) { "Ungültiger Archiveintrag: ${entry.name}" }
                require(!entry.isDirectory && entry.name !in entries) { "Doppelter oder ungültiger Archiveintrag" }
                require(entries.size < NativeArchiveLimits.MAX_ENTRIES) { "RESOURCE_LIMIT: Zu viele Archiveinträge" }
                // The manifest comes first (as encode writes it), so every asset is read only up to its declared size:
                // a small archive cannot inflate hundreds of MiB of undeclared data before it is rejected.
                val m = declared
                val limit = when {
                    entry.name == "manifest.json" -> 4L * 1024 * 1024
                    m == null -> throw IllegalArgumentException("Manifest fehlt")
                    entry.name == "project.json" -> NativeArchiveLimits.MAX_ENTRY
                    else -> m.assets.firstOrNull { "assets/${it.hash.value}" == entry.name }?.sizeBytes ?: throw IllegalArgumentException("Unerwartete oder fehlende Assets")
                }
                val payload = readLimitedStream(zip, minOf(limit, NativeArchiveLimits.MAX_ENTRY, NativeArchiveLimits.MAX_TOTAL - total))
                total += payload.size
                entries[entry.name] = payload
                if (entry.name == "manifest.json") {
                    val text = payload.toString(Charsets.UTF_8)
                    checkJsonDepth(text)
                    declared = projectJson.decodeFromString<BundleManifest>(text)
                }
                zip.closeEntry()
            }
        }
        val manifest = declared ?: throw IllegalArgumentException("Manifest fehlt")
        require(manifest.format == "riffworkbench-project") { "Falsches Projektformat" }
        require(manifest.schemaVersion == 1) { "INCOMPATIBLE_VERSION: Archivformat ${manifest.schemaVersion}" }
        val data = entries["project.json"] ?: throw IllegalArgumentException("Partitur fehlt")
        require(sha256(data) == manifest.projectHash) { "Projekt-Prüfsumme stimmt nicht" }
        val project = decodeProject(data)
        validate(project)
        val expected = (project.assets + project.audio.assets).distinctBy { it.hash }
        require(expected == manifest.assets && expected.map { it.hash }.distinct().size == manifest.assets.size) { "Asset-Manifest widerspricht dem Projekt" }
        require(entries.size == 2 + expected.size) { "Unerwartete oder fehlende Assets" }
        val assets = expected.associate { descriptor ->
            val payload = entries["assets/${descriptor.hash.value}"] ?: throw IllegalArgumentException("Asset fehlt: ${descriptor.name}")
            require(payload.size.toLong() == descriptor.sizeBytes && sha256(payload) == descriptor.hash.value) { "Asset-Prüfsumme stimmt nicht" }
            descriptor.hash to payload
        }
        require(references(project).all { it in assets }) { "Referenziertes Asset fehlt" }
        return NativeBundle(project, assets, manifest.assets)
    }

    private fun validate(project: Project) {
        require(project.schemaVersion == 1 && project.measures.isNotEmpty() && project.tracks.isNotEmpty())
        val diagnostics = ScoreValidator.validate(project)
        require(diagnostics.none { it.severity == Severity.ERROR }) { diagnostics.firstOrNull { it.severity == Severity.ERROR }?.message ?: "Ungültiges Projekt" }
    }
    private fun references(p: Project): Set<AssetHash> = buildSet {
        addAll(p.audio.clips.map { it.asset })
        p.provenance.forEach { add(it.originalAsset); addAll(it.unknownData.map(ForeignData::asset)) }
        p.tracks.forEach { track -> track.sound.originalRseData?.let(::add); addAll(track.mix.effects.mapNotNull { it.asset }) }
        addAll(p.mix.master.effects.mapNotNull { it.asset })
        p.mix.buses.forEach { addAll(it.mix.effects.mapNotNull(EffectUnit::asset)) }
        p.audio.clips.forEach { addAll(it.filters.mapNotNull(EffectUnit::asset)) }
        p.soundBanks.forEach { bank -> add(bank.asset); bank.presets.flatMap { it.regions }.forEach { add(it.asset); it.releaseAsset?.let(::add) } }
    }
}

class FileNativeProjectExchange(private val assets: AssetStore) : NativeProjectExchange {
    override suspend fun export(project: Project): Outcome<ExportResult> = withContext(Dispatchers.IO) {
        val collected = mutableMapOf<AssetHash, ByteArray>()
        for (descriptor in (project.assets + project.audio.assets).distinctBy { it.hash }) {
            when (val result = assets.get(descriptor.hash)) {
                is Outcome.Failure -> return@withContext result
                is Outcome.Success -> collected[descriptor.hash] = result.value
            }
        }
        storageOutcome {
            val name = project.metadata.title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(80).ifBlank { "project" }
            ExportResult(NativeProjectCodec.encode(project, collected), "application/octet-stream", "$name.riffproj", emptyList())
        }
    }
    override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = withContext(Dispatchers.IO) {
        when (val decoded = storageOutcome { NativeProjectCodec.decode(bytes) }) {
            is Outcome.Failure -> decoded
            is Outcome.Success -> {
                for (descriptor in decoded.value.descriptors) {
                    when (val stored = assets.put(descriptor.name, descriptor.mediaType, decoded.value.assets.getValue(descriptor.hash), descriptor.rights)) {
                        is Outcome.Failure -> return@withContext stored
                        is Outcome.Success -> Unit
                    }
                }
                Outcome.Success(decoded.value.project.copy(id = newProjectId, revision = 0))
            }
        }
    }
}
