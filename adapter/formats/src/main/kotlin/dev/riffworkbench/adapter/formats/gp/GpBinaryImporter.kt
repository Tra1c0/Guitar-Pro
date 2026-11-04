package dev.riffworkbench.adapter.formats.gp

import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.ScoreImporter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Content sniffing for Guitar Pro 3–5 binary files ("FICHIER GUITAR PRO vX.YY" header). */
object GpBinaryDetector {
    /** Format id "gp3", "gp4" or "gp5" for a supported header, otherwise null. Needs at most the first 31 bytes. */
    fun version(bytes: ByteArray): String? = GpVersion.detect(bytes)?.formatId
}

/**
 * C-FORMAT: imports Guitar Pro 3.00, 4.00/4.06 and 5.00/5.10 binary files. The version is taken from the header,
 * not from [formatId]; [ImportProvenance.format] records the detected one.
 *
 * Untrusted input: every read is bounds-checked, counts are capped ([GpLimits]); malformed data yields
 * `Failure(CORRUPT)`, unsupported headers `Failure(INCOMPATIBLE_VERSION)`, oversized input or more beats/notes than
 * the budget `Failure(RESOURCE_LIMIT)` — never an exception. Parsing runs on [Dispatchers.Default]; [import] reports
 * progress per measure from that thread and honours cancellation between measures.
 *
 * The returned project passes [ScoreValidator] without errors and lists everything it could not map in
 * [ImportResult.losses] (also stored in its [ImportProvenance]). It references the original bytes as an
 * [AssetDescriptor] by SHA-256; the caller must store exactly these bytes in the asset store.
 */
class GpBinaryImporter(override val formatId: String) : ScoreImporter {
    init { require(formatId in FORMAT_IDS) { "GP-Binärimport unterstützt nur ${FORMAT_IDS.joinToString()}" } }

    override suspend fun import(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit): Outcome<ImportResult> =
        withContext(Dispatchers.Default) {
            val job = coroutineContext.job
            importBytes(bytes, ids) { done, total ->
                job.ensureActive()
                progress(OperationProgress(done.toLong(), total.toLong(), "Guitar-Pro-Takte lesen"))
            }
        }

    internal fun importBytes(bytes: ByteArray, ids: IdSource, onMeasure: (Int, Int) -> Unit = { _, _ -> }): Outcome<ImportResult> {
        if (bytes.size > GpLimits.MAX_INPUT_BYTES) return failure(FailureCode.RESOURCE_LIMIT, "Guitar-Pro-Datei ist größer als 64 MiB")
        val version = GpVersion.detect(bytes) ?: return GpVersion.header(bytes)
            ?.takeIf { it.startsWith("FICHIER GUITAR PRO") || it.startsWith("CLIPBOARD") }
            ?.let { failure(FailureCode.INCOMPATIBLE_VERSION, "Guitar-Pro-Version „$it“ wird nicht unterstützt (nur GP3, GP4 und GP5)") }
            ?: failure(FailureCode.CORRUPT, "Keine Guitar-Pro-3/4/5-Datei: unbekannter Dateikopf")
        return try {
            val result = GpMapper(GpParser.parse(bytes, version, onMeasure), ids).map(bytes)
            val errors = ScoreValidator.validate(result.project).filter { it.severity == Severity.ERROR }
            if (errors.isEmpty()) Outcome.Success(result)
            else Outcome.Failure(FailureCode.CORRUPT, "Guitar-Pro-Datei ergibt kein gültiges Projekt: ${errors.first().message}", errors, CONTRACT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GpLimitException) {
            failure(FailureCode.RESOURCE_LIMIT, "Guitar-Pro-Datei überschreitet die Importgrenzen: ${e.message}")
        } catch (e: GpFormatException) {
            failure(FailureCode.CORRUPT, "Beschädigte Guitar-Pro-Datei: ${e.message}")
        } catch (e: RuntimeException) { // model invariants (e.g. arithmetic limits) violated by implausible content
            failure(FailureCode.CORRUPT, "Beschädigte Guitar-Pro-Datei: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun failure(code: FailureCode, message: String) = Outcome.Failure(code, message, contractId = CONTRACT)

    private companion object {
        const val CONTRACT = "C-FORMAT"
        val FORMAT_IDS = listOf("gp3", "gp4", "gp5")
    }
}
