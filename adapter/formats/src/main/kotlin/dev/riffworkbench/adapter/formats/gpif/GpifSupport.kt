package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlException
import dev.riffworkbench.model.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.floor

const val GPIF_CONTRACT = "C-FORMAT"

/** Internal typed failure; converted to [Outcome.Failure] at the port boundary and never leaks to callers. */
internal class GpifException(val code: FailureCode, message: String) : RuntimeException(message)

internal fun corrupt(message: String): Nothing = throw GpifException(FailureCode.CORRUPT, message)
internal fun resourceLimit(message: String): Nothing = throw GpifException(FailureCode.RESOURCE_LIMIT, message)

/** Runs [block] and maps every failure to a typed C-FORMAT outcome. Coroutine cancellation is rethrown, never turned into a result. */
internal inline fun <T> gpifOutcome(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: GpifException) {
    Outcome.Failure(e.code, e.message ?: "Ungültige Guitar-Pro-Datei", contractId = GPIF_CONTRACT)
} catch (e: XmlException) {
    val message = e.message ?: "Ungültiges XML"
    val code = if (message.contains("zu groß") || message.contains("zu tief") || message.contains("zu viele")) FailureCode.RESOURCE_LIMIT else FailureCode.CORRUPT
    Outcome.Failure(code, "score.gpif ist ungültig: $message", contractId = GPIF_CONTRACT)
} catch (e: java.util.zip.ZipException) {
    Outcome.Failure(FailureCode.CORRUPT, "Beschädigtes ZIP-Archiv: ${e.message ?: "unbekannter Fehler"}", contractId = GPIF_CONTRACT)
} catch (e: java.io.IOException) {
    Outcome.Failure(FailureCode.CORRUPT, "Datei konnte nicht gelesen werden: ${e.message ?: "unbekannter Fehler"}", contractId = GPIF_CONTRACT)
} catch (e: OutOfMemoryError) {
    Outcome.Failure(FailureCode.RESOURCE_LIMIT, "Nicht genügend Speicher für diese Datei", contractId = GPIF_CONTRACT)
} catch (e: StackOverflowError) {
    Outcome.Failure(FailureCode.RESOURCE_LIMIT, "Datei ist zu tief verschachtelt", contractId = GPIF_CONTRACT)
} catch (e: RuntimeException) {
    // Model invariants (require) or arithmetic overflow on hostile values: the file is not convertible.
    Outcome.Failure(FailureCode.CORRUPT, "Guitar-Pro-Daten sind nicht konvertierbar: ${e.message ?: e.javaClass.simpleName}", contractId = GPIF_CONTRACT)
}

/**
 * Collects loss entries. Repeated identical reasons are merged into one entry with a count so a large score
 * does not flood the report; the first affected entity is kept.
 */
class LossCollector(private val direction: String) {
    private data class Key(val featureId: String, val reason: String)
    private val counts = LinkedHashMap<Key, Int>()
    private val firstEntity = HashMap<Key, EntityId?>()

    fun add(featureId: String, reason: String, entityId: EntityId? = null) {
        val key = Key(featureId, reason)
        counts[key] = (counts[key] ?: 0) + 1
        if (key !in firstEntity) firstEntity[key] = entityId
    }

    val isEmpty: Boolean get() = counts.isEmpty()

    fun entries(): List<LossEntry> = counts.map { (key, count) ->
        LossEntry(key.featureId, direction, if (count > 1) "${key.reason} (${count}×)" else key.reason, firstEntity[key])
    }
}

internal object GpifNumbers {
    /**
     * Best rational approximation of [value] with denominator <= [maxDenominator] (continued fractions).
     * Used to recover exact positions from decimal ratios that we (or Guitar Pro) wrote: 0.42857142857142855 -> 3/7.
     */
    fun rational(value: Double, maxDenominator: Long = 3840, tolerance: Double = 1e-7): Fraction {
        require(value.isFinite()) { "Ungültige Zahl" }
        val negative = value < 0
        val x = abs(value)
        var h0 = 0L; var h1 = 1L; var k0 = 1L; var k1 = 0L
        var r = x
        var best = Fraction.of(Math.round(x), 1)
        for (i in 0 until 64) {
            val a = floor(r).toLong()
            val h2 = a * h1 + h0
            val k2 = a * k1 + k0
            if (k2 > maxDenominator || k2 <= 0 || h2 < 0) break
            best = Fraction.of(h2, k2)
            if (abs(x - h2.toDouble() / k2) <= tolerance) break
            h0 = h1; h1 = h2; k0 = k1; k1 = k2
            val frac = r - a
            if (frac < 1e-15) break
            r = 1.0 / frac
        }
        return if (negative) Fraction.of(-best.numerator, best.denominator) else best
    }

    /** Shortest decimal text for a double; integral values are written without fraction digits. */
    fun decimal(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return value.toString()
    }

    fun parseIntOrNull(text: String?): Int? = text?.trim()?.toIntOrNull()
    fun parseDoubleOrNull(text: String?): Double? = text?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun ints(text: String?): List<Int> {
        if (text.isNullOrBlank()) return emptyList()
        return text.trim().split(Regex("\\s+")).map { it.toIntOrNull() ?: corrupt("Ungültige Zahlenliste in score.gpif: '${it.take(20)}'") }
    }
}
