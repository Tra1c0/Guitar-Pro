package dev.riffworkbench.data.local

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class FileAssetStore(private val root: File) : AssetStore {
    override suspend fun put(name: String, mediaType: String, bytes: ByteArray, rights: AssetRights): Outcome<AssetDescriptor> = withContext(Dispatchers.IO) {
        storageOutcome {
            require(bytes.size <= NativeArchiveLimits.MAX_ENTRY) { "RESOURCE_LIMIT: Asset zu groß" }
            val hash = AssetHash(sha256(bytes))
            val file = File(root, hash.value)
            if (!file.exists()) atomicWrite(file, bytes)
            else require(sha256(readLimited(file)) == hash.value) { "Vorhandenes Asset ist beschädigt" }
            AssetDescriptor(hash, name, mediaType, bytes.size.toLong(), rights)
        }
    }
    override suspend fun get(hash: AssetHash): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        storageOutcome { readLimited(File(root, hash.value)).also { require(sha256(it) == hash.value) { "Asset-Prüfsumme stimmt nicht" } } }
    }
}

class FileSettingsRepository(private val file: File) : SettingsRepository {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(AppSettings())
    override val settings = mutable.asStateFlow()
    override suspend fun load(): Outcome<Unit> = withContext(Dispatchers.IO) { mutex.withLock {
        storageOutcome {
            if (file.exists()) mutable.value = projectJson.decodeFromString<AppSettings>(readLimited(file, 1024 * 1024).toString(Charsets.UTF_8))
            Unit
        }
    } }
    override suspend fun update(settings: AppSettings): Outcome<Unit> = withContext(Dispatchers.IO) { mutex.withLock {
        storageOutcome { atomicWrite(file, projectJson.encodeToString(settings).toByteArray()); mutable.value = settings }
    } }
}
