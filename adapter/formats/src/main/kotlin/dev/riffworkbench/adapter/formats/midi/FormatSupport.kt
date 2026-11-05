package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.adapter.formats.xml.XmlException
import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import kotlin.coroutines.cancellation.CancellationException

/*
 * Helpers shared by the MIDI, MusicXML and ASCII adapters (C-FORMAT). They live in the midi package because
 * the MusicXML importer reuses the MIDI importer's fret and rhythm heuristics.
 */

internal const val FORMAT_CONTRACT = "C-FORMAT"

internal object FormatLimits {
    /** Maximum accepted input size for every importer. */
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024
    /** Upper bound for created measures, matching the playback route budget. */
    const val MAX_MEASURES = 20_000
    const val MAX_NOTES = 1_000_000
}

/** Thrown inside parsers and mapped to a typed [Outcome.Failure] by [guarded]. */
internal class FormatException(val code: FailureCode, message: String) : RuntimeException(message)

internal fun corrupt(message: String): Nothing = throw FormatException(FailureCode.CORRUPT, message)
internal fun resourceLimit(message: String): Nothing = throw FormatException(FailureCode.RESOURCE_LIMIT, message)

internal fun formatFailure(code: FailureCode, message: String, diagnostics: List<Diagnostic> = emptyList()): Outcome.Failure =
    Outcome.Failure(code, message, diagnostics, FORMAT_CONTRACT)

/**
 * Runs an importer or exporter body. Malformed input never escapes as an exception: parser errors become
 * CORRUPT, exhausted budgets RESOURCE_LIMIT. Coroutine cancellation is rethrown, never turned into a result.
 */
internal inline fun <T> guarded(what: String, failureCode: FailureCode = FailureCode.CORRUPT, block: () -> Outcome<T>): Outcome<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: FormatException) {
    formatFailure(e.code, e.message ?: "$what ist beschädigt")
} catch (e: XmlException) {
    formatFailure(FailureCode.CORRUPT, e.message ?: "$what enthält fehlerhaftes XML")
} catch (e: StackOverflowError) {
    formatFailure(FailureCode.RESOURCE_LIMIT, "$what ist zu tief verschachtelt")
} catch (e: OutOfMemoryError) {
    formatFailure(FailureCode.RESOURCE_LIMIT, "Nicht genügend Speicher für $what")
} catch (e: Exception) {
    formatFailure(failureCode, "$what konnte nicht verarbeitet werden: ${e.message ?: e.javaClass.simpleName}")
}

/** Collects loss entries once per feature and entity instead of once per note. */
internal class LossLog(private val direction: String) {
    private val entries = LinkedHashMap<String, LossEntry>()
    fun add(featureId: String, reason: String, entityId: EntityId? = null) {
        entries.getOrPut(featureId + "|" + (entityId?.value ?: "")) { LossEntry(featureId, direction, reason, entityId) }
    }
    fun toList(): List<LossEntry> = entries.values.toList()
}

internal fun exportFileName(project: Project, extension: String): String {
    val base = project.metadata.title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(80).trim('_').ifBlank { "project" }
    return "$base.$extension"
}

/** Exporters refuse projects with validation errors instead of writing undefined output. */
internal fun exportBlocker(project: Project): Outcome.Failure? {
    if (project.measures.isEmpty() || project.tracks.isEmpty()) return formatFailure(FailureCode.VALIDATION, "Projekt enthält keine Takte oder Spuren")
    val errors = ScoreValidator.validate(project).filter { it.severity == Severity.ERROR }
    return if (errors.isEmpty()) null else formatFailure(FailureCode.VALIDATION, "Projekt ist nicht exportierbar: ${errors.first().message}", errors)
}

/** Importers only hand out projects without validation errors. */
internal fun validatedImport(project: Project, losses: List<LossEntry>): Outcome<ImportResult> {
    val errors = ScoreValidator.validate(project).filter { it.severity == Severity.ERROR }
    if (errors.isNotEmpty()) return formatFailure(FailureCode.CORRUPT, "Import ergibt keine gültige Partitur: ${errors.first().message}", errors)
    return Outcome.Success(ImportResult(project, losses))
}

// Exact rational helpers on top of the model's Fraction (which only offers plus/times/compare).
internal operator fun Fraction.unaryMinus(): Fraction = Fraction.of(-numerator, denominator)
internal operator fun Fraction.minus(other: Fraction): Fraction = this + (-other)
internal operator fun Fraction.div(other: Fraction): Fraction {
    require(other.numerator != 0L) { "Division durch null" }
    return this * if (other.numerator > 0) Fraction.of(other.denominator, other.numerator) else Fraction.of(-other.denominator, -other.numerator)
}
internal fun frac(numerator: Long, denominator: Long = 1): Fraction = Fraction.of(numerator, denominator)
internal fun Fraction.isZero() = numerator == 0L
internal fun Fraction.isInteger() = denominator == 1L
/** Floor for non-negative and negative values. */
internal fun Fraction.floor(): Long = Math.floorDiv(numerator, denominator)
internal fun Fraction.isMultipleOf(unit: Fraction): Boolean = (this / unit).isInteger()
internal fun minF(a: Fraction, b: Fraction) = if (a <= b) a else b
internal fun maxF(a: Fraction, b: Fraction) = if (a >= b) a else b

internal fun gcdL(a: Long, b: Long): Long { var x = Math.abs(a); var y = Math.abs(b); while (y != 0L) { val t = x % y; x = y; y = t }; return x }
/** Least common multiple or null when it would exceed [cap]. */
internal fun lcmOrNull(a: Long, b: Long, cap: Long = Long.MAX_VALUE / 4): Long? {
    if (a == 0L || b == 0L) return maxOf(a, b)
    val g = gcdL(a, b)
    val q = a / g
    if (q > cap / b) return null
    return q * b
}

/** Parses an XML decimal such as "480", "0.5" or "-1.25" into an exact fraction (at most 9 decimals). */
internal fun parseDecimal(text: String?): Fraction? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty() || t.length > 40) return null
    val m = Regex("([+-]?)(\\d*)(?:\\.(\\d{0,9}))?").matchEntire(t) ?: return null
    val whole = m.groupValues[2]
    val decimals = m.groupValues[3]
    if (whole.isEmpty() && decimals.isEmpty()) return null
    if (whole.length > 15) return null
    var denominator = 1L
    repeat(decimals.length) { denominator *= 10 }
    val numerator = (whole.ifEmpty { "0" }.toLong()) * denominator + (decimals.ifEmpty { "0" }.toLong())
    return Fraction.of(if (m.groupValues[1] == "-") -numerator else numerator, denominator)
}

internal fun formatNumber(value: Double, decimals: Int = 3): String {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) return value.toLong().toString()
    return java.math.BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

internal val QUARTER: Fraction = Fraction.of(1, 4)
