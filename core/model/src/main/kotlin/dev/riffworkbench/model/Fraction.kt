package dev.riffworkbench.model

import kotlinx.serialization.Serializable

/** Exact whole-note units. Arithmetic fails explicitly instead of silently overflowing. */
@Serializable
data class Fraction(val numerator: Long = 0, val denominator: Long = 1) : Comparable<Fraction> {
    init {
        require(denominator > 0) { "Positive denominator required" }
        require(gcd(numerator, denominator) == 1L) { "Use Fraction.of for normalization" }
    }

    operator fun plus(other: Fraction): Fraction {
        val common = gcd(denominator, other.denominator)
        val a = multiply(numerator, other.denominator / common)
        val b = multiply(other.numerator, denominator / common)
        val sum = a + b
        require(((a xor sum) and (b xor sum)) >= 0) { "Rational addition overflow" }
        return of(sum, multiply(denominator / common, other.denominator))
    }
    operator fun times(other: Fraction): Fraction {
        val a = gcd(numerator, other.denominator)
        val b = gcd(other.numerator, denominator)
        return of(multiply(numerator / a, other.numerator / b), multiply(denominator / b, other.denominator / a))
    }
    override fun compareTo(other: Fraction): Int {
        val common = gcd(denominator, other.denominator)
        return multiply(numerator, other.denominator / common).compareTo(multiply(other.numerator, denominator / common))
    }
    fun toDouble(): Double = numerator.toDouble() / denominator
    override fun toString(): String = "$numerator/$denominator"

    companion object {
        val ZERO = Fraction()
        val ONE = Fraction(1)
        fun of(numerator: Long, denominator: Long = 1): Fraction {
            require(denominator > 0)
            val g = gcd(numerator, denominator)
            return Fraction(numerator / g, denominator / g)
        }
        private fun gcd(a: Long, b: Long): Long {
            var x = b
            var y = a % b
            while (y != 0L) { val next = x % y; x = y; y = next }
            return if (x < 0) -x else x
        }
        private fun multiply(a: Long, b: Long): Long {
            if (a == 0L || b == 0L) return 0
            require(!(a == Long.MIN_VALUE && b == -1L) && !(b == Long.MIN_VALUE && a == -1L)) { "Rational multiplication overflow" }
            val result = a * b
            require(result / b == a) { "Rational multiplication overflow" }
            return result
        }
    }
}

@Serializable
data class TupletRatio(val actual: Int, val normal: Int) {
    init { require(actual > 0 && normal > 0) }
}

@Serializable
data class WrittenDuration(val denominator: Int = 16, val dots: Int = 0, val tuplets: List<TupletRatio> = emptyList()) {
    init { require(denominator in 1..1024 && denominator.countOneBits() == 1); require(dots in 0..4) }
    fun length(): Fraction {
        var value = Fraction.of(1, denominator.toLong())
        var dot = value
        repeat(dots) { dot *= Fraction.of(1, 2); value += dot }
        tuplets.forEach { value *= Fraction.of(it.normal.toLong(), it.actual.toLong()) }
        return value
    }
}
