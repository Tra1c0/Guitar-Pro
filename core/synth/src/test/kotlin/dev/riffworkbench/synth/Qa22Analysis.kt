package dev.riffworkbench.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * QA-Zyklus 22: Messwerkzeuge für Übergänge (gepickt/gebunden), Pinch gegen Natural Harmonic und Preset-Lautheit.
 * Nur stdlib, deterministisch. Fenster in Frames relativ zum Einsatz, Pegel in dB.
 */
internal object Qa22Analysis {
    fun db(x: Double): Double = if (x <= 1e-15) -300.0 else 20 * log10(x)
    fun f1(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x)
    fun f2(x: Double) = String.format(java.util.Locale.ROOT, "%.2f", x)

    /** Biquad in Direktform I mit Double-Zustand (Messfilter, kein Echtzeitpfad). */
    class Section(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size); var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in x.indices) { val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v }
            return y
        }
    }

    // ------------------------------------------------------------------ ITU-R BS.1770-4 (K-Gewichtung, Gating)

    /**
     * K-Gewichtung für eine beliebige Abtastrate: Stufe 1 Hochregal (Kopfmodell, +4 dB über ~1,7 kHz), Stufe 2 RLB-Hochpass
     * (~38 Hz). Die Analogprototypen (f0, Q, Verstärkung) sind die der Norm-Koeffizienten bei 48 kHz, über die bilineare
     * Transformation auf [sr] umgerechnet; bei 48 kHz entstehen die Koeffizienten der Norm (Tabellen 1 und 2) auf 1e-8.
     */
    fun kWeighting(sr: Int): List<Section> {
        // Stufe 1
        val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
        val k = tan(PI * f0 / sr); val vh = Math.pow(10.0, g / 20); val vb = Math.pow(vh, 0.4996667741545416)
        val a0 = 1 + k / q + k * k
        val s1 = Section((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        // Stufe 2
        val f1 = 38.13547087602444; val q2 = 0.5003270373238773
        val k2 = tan(PI * f1 / sr); val a02 = 1 + k2 / q2 + k2 * k2
        val s2 = Section(1.0, -2.0, 1.0, 2 * (k2 * k2 - 1) / a02, (1 - k2 / q2 + k2 * k2) / a02)
        return listOf(s1, s2)
    }

    /**
     * Integrierte Lautheit nach BS.1770-4 (LUFS): K-gewichtete mittlere Leistung je Kanal (Gewicht 1 für L/R), Blöcke 400 ms mit
     * 75 % Überlappung, absolutes Gate −70 LUFS, relatives Gate 10 LU unter dem Mittel der absolut gegateten Blöcke.
     */
    fun integratedLufs(channels: List<FloatArray>, sr: Int): Double {
        val sections = kWeighting(sr)
        val weighted = channels.map { ch -> var y = DoubleArray(ch.size) { ch[it].toDouble() }; for (s in sections) y = s.run(y); y }
        val block = (0.4 * sr).toInt(); val hop = block / 4
        val n = weighted.minOf { it.size }
        val z = ArrayList<Double>()
        var start = 0
        while (start + block <= n) {
            var sum = 0.0
            for (y in weighted) { var e = 0.0; for (i in start until start + block) e += y[i] * y[i]; sum += e / block }
            z += sum
            start += hop
        }
        fun loud(p: Double) = -0.691 + 10 * log10(p.coerceAtLeast(1e-30))
        val abs = z.filter { loud(it) > -70.0 }
        if (abs.isEmpty()) return -300.0
        val rel = loud(abs.average()) - 10.0
        val gated = abs.filter { loud(it) > rel }
        return loud(gated.average())
    }

    // ------------------------------------------------------------------ Übergänge

    /** Butterworth-Hochpass 4. Ordnung (zwei Sektionen, Q 0,541/1,307) bei [hz]. */
    fun highPass4(x: FloatArray, sr: Int, hz: Double): DoubleArray {
        var y = DoubleArray(x.size) { x[it].toDouble() }
        for (q in doubleArrayOf(0.5411961, 1.3065630)) {
            val w = 2 * PI * hz / sr; val c = kotlin.math.cos(w); val a = kotlin.math.sin(w) / (2 * q); val a0 = 1 + a
            y = Section((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - a) / a0).run(y)
        }
        return y
    }
    fun meanPower(x: DoubleArray, from: Int, to: Int): Double { val a = from.coerceIn(0, x.size); val b = to.coerceIn(a, x.size); var s = 0.0; for (i in a until b) s += x[i] * x[i]; return s / (b - a).coerceAtLeast(1) }
    fun meanPower(x: FloatArray, from: Int, to: Int): Double { val a = from.coerceIn(0, x.size); val b = to.coerceIn(a, x.size); var s = 0.0; for (i in a until b) s += x[i].toDouble() * x[i]; return s / (b - a).coerceAtLeast(1) }

    /**
     * Kennzahlen eines Einsatzes bei Frame [t0] (Einsatz am Ausgang, d. h. Notenframe + Kettenlatenz):
     * - [hfTransientDb]: Leistung > 3 kHz (Butterworth-Hochpass 4. Ordnung) in den ersten 8 ms gegen den Körper 30–80 ms nach dem Einsatz.
     * - [hfFluxDb]: Leistung > 3 kHz in den ersten 8 ms gegen die 20 ms vor dem Einsatz (Höhen-Sprung des Übergangs, kurze Auflösung).
     * - [peakDb]: lautestes 5-ms-RMS-Fenster mit Beginn 0–7 ms gegen das RMS des Körpers 30–80 ms (neuer Attack-Peak > 0).
     *   5 ms statt 1 ms, damit bei tiefen Tönen (E2-Periode 12 ms) nicht der Scheitel der Wellenform gemessen wird.
     * - [stepDb]: RMS der ersten 10 ms gegen das RMS 25–5 ms vor dem Einsatz (Pegelsprung des Übergangs).
     * - [dipDb]: leisestes 5-ms-RMS-Fenster mit Beginn −3…+5 ms gegen das RMS 25–5 ms vor dem Einsatz (Pegeleinbruch < 0).
     * - [crestDb]: Spitze/RMS in 0–8 ms.
     */
    class Onset(val hfTransientDb: Double, val hfFluxDb: Double, val peakDb: Double, val stepDb: Double, val dipDb: Double, val crestDb: Double, val peakStepDb: Double) {
        override fun toString() = "HF-Trans ${f1(hfTransientDb)} | HF-Fluss ${f1(hfFluxDb)} | Peak ${f1(peakDb)} | Sprung ${f1(stepDb)} | Spitzensprung ${f1(peakStepDb)} | Einbruch ${f1(dipDb)} | Crest ${f1(crestDb)} dB"
    }
    fun onset(x: FloatArray, sr: Int, t0: Int): Onset {
        val ms = sr / 1000
        val hp = highPass4(x, sr, 3000.0)
        val hfOn = meanPower(hp, t0, t0 + 8 * ms)
        val hfBody = meanPower(hp, t0 + 30 * ms, t0 + 80 * ms)
        val hfPre = meanPower(hp, t0 - 20 * ms, t0)
        val body = meanPower(x, t0 + 30 * ms, t0 + 80 * ms)
        val pre = meanPower(x, t0 - 25 * ms, t0 - 5 * ms)
        var maxWin = 0.0; for (k in 0 until 8) maxWin = maxOf(maxWin, meanPower(x, t0 + k * ms, t0 + (k + 5) * ms))
        var maxStep = 0.0; for (k in 0..7) maxStep = maxOf(maxStep, meanPower(x, t0 + k * ms, t0 + (k + 5) * ms))
        var minWin = Double.MAX_VALUE; for (k in -3..5) minWin = minOf(minWin, meanPower(x, t0 + k * ms, t0 + (k + 5) * ms))
        var pk = 0.0; for (i in t0 until t0 + 8 * ms) if (i in x.indices) pk = maxOf(pk, abs(x[i].toDouble()))
        fun r(a: Double, b: Double) = 10 * log10(a.coerceAtLeast(1e-30) / b.coerceAtLeast(1e-30))
        return Onset(r(hfOn, hfBody), r(hfOn, hfPre), r(maxWin, body), r(meanPower(x, t0, t0 + 10 * ms), pre), r(minWin, pre), db(pk) - 10 * log10(meanPower(x, t0, t0 + 8 * ms).coerceAtLeast(1e-30)), r(maxStep, pre))
    }

    fun rms(x: FloatArray, from: Int, to: Int) = sqrt(meanPower(x, from, to))
}
