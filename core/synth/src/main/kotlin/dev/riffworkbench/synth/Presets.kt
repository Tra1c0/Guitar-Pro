package dev.riffworkbench.synth

import dev.riffworkbench.model.*

/** A built-in sound: voice model, string timbre and default effect chain. Own synthesis, no third-party content. */
data class TonePreset(val id: String, val labelDe: String, val labelEn: String, val family: Family, val defaultChain: List<EffectUnit>) {
    enum class Family { GUITAR, BASS, DRUMS, KEYS }
}

object TonePresets {
    private fun fx(kind: EffectKind, vararg params: Pair<String, Double>) = EffectUnit(EntityId("fx-${kind.name.lowercase()}"), kind, parameters = mapOf(*params))

    val all: List<TonePreset> = listOf(
        TonePreset("rw.guitar.highgain", "E-Gitarre · High Gain", "Electric · high gain", TonePreset.Family.GUITAR, listOf(
            fx(EffectKind.GATE, "thresholdDb" to -52.0, "releaseMs" to 45.0),
            fx(EffectKind.AMP, "gain" to 0.86, "bass" to 2.0, "mid" to -1.0, "treble" to 2.0, "presence" to 3.0, "level" to -5.0),
            fx(EffectKind.CABINET_IR, "model" to 0.0),
            fx(EffectKind.EQ, "low" to -1.0, "mid" to 1.0, "high" to 0.0, "midHz" to 1400.0))),
        TonePreset("rw.guitar.crunch", "E-Gitarre · Crunch", "Electric · crunch", TonePreset.Family.GUITAR, listOf(
            fx(EffectKind.AMP, "gain" to 0.5, "bass" to 1.0, "mid" to 1.5, "treble" to 1.0, "presence" to 1.5, "level" to -2.0),
            fx(EffectKind.CABINET_IR, "model" to 1.0),
            fx(EffectKind.REVERB, "size" to 0.35, "damping" to 0.5, "mix" to 0.08))),
        TonePreset("rw.guitar.clean", "E-Gitarre · Clean", "Electric · clean", TonePreset.Family.GUITAR, listOf(
            // QA cycle 22: make-up 9 dB (was 6): the Clean amp's power stage holds the v127 chord peaks, so Clean gains 1.3 LU at the
            // same stem peak; body gain reduction of a held v127 chord 4.0 dB, attack 8 ms (default) lets the pick transient through;
            // amp input of a v96 note -12.7 dBFS (budget -12), THD 1.6 %. A 3 ms attack spread the levels across the neck 5.8 dB (budget 5.5).
            fx(EffectKind.COMPRESSOR, "thresholdDb" to -30.0, "ratio" to 3.0, "makeupDb" to 9.0),
            fx(EffectKind.AMP, "gain" to 0.08, "bass" to 1.0, "treble" to 2.0, "presence" to 1.0, "level" to 3.0),
            fx(EffectKind.CABINET_IR, "model" to 2.0),
            fx(EffectKind.CHORUS, "rateHz" to 0.7, "depthMs" to 2.5, "mix" to 0.1),
            fx(EffectKind.REVERB, "size" to 0.5, "damping" to 0.45, "mix" to 0.12))),
        TonePreset("rw.guitar.acoustic", "Akustikgitarre", "Acoustic guitar", TonePreset.Family.GUITAR, listOf(
            fx(EffectKind.EQ, "low" to 2.0, "mid" to -2.0, "high" to 2.0, "midHz" to 500.0),
            fx(EffectKind.REVERB, "size" to 0.45, "damping" to 0.4, "mix" to 0.18))),
        TonePreset("rw.bass.pick", "Bass · Plektrum", "Bass · pick", TonePreset.Family.BASS, listOf(
            fx(EffectKind.COMPRESSOR, "thresholdDb" to -22.0, "ratio" to 4.0, "attackMs" to 5.0, "makeupDb" to 1.0),
            fx(EffectKind.AMP, "gain" to 0.3, "bass" to 3.0, "mid" to 1.0, "treble" to 1.0, "level" to -3.0),
            fx(EffectKind.CABINET_IR, "model" to 3.0))),
        TonePreset("rw.bass.finger", "Bass · Finger", "Bass · finger", TonePreset.Family.BASS, listOf(
            fx(EffectKind.COMPRESSOR, "thresholdDb" to -20.0, "ratio" to 3.0, "makeupDb" to 3.0),
            fx(EffectKind.EQ, "low" to 3.0, "mid" to 0.0, "high" to -3.0, "midHz" to 700.0))),
        TonePreset("rw.drums.rock", "Drums · Rock/Metal", "Drums · rock/metal", TonePreset.Family.DRUMS, listOf(
            fx(EffectKind.COMPRESSOR, "thresholdDb" to -16.0, "ratio" to 3.0, "attackMs" to 4.0, "releaseMs" to 90.0, "makeupDb" to 0.0),
            fx(EffectKind.EQ, "low" to 1.0, "mid" to -1.5, "high" to 1.0, "midHz" to 500.0),
            fx(EffectKind.REVERB, "size" to 0.3, "damping" to 0.6, "mix" to 0.1))),
        TonePreset("rw.keys.piano", "Piano", "Piano", TonePreset.Family.KEYS, listOf(fx(EffectKind.REVERB, "size" to 0.5, "damping" to 0.5, "mix" to 0.18))),
        TonePreset("rw.keys.epiano", "E-Piano", "Electric piano", TonePreset.Family.KEYS, listOf(fx(EffectKind.CHORUS, "rateHz" to 0.5, "depthMs" to 2.0, "mix" to 0.3), fx(EffectKind.REVERB, "mix" to 0.15))),
        TonePreset("rw.keys.organ", "Orgel", "Organ", TonePreset.Family.KEYS, listOf(fx(EffectKind.CHORUS, "rateHz" to 5.5, "depthMs" to 1.0, "mix" to 0.3))),
        TonePreset("rw.keys.pad", "Pad / Streicher", "Pad / strings", TonePreset.Family.KEYS, listOf(fx(EffectKind.REVERB, "size" to 0.8, "damping" to 0.4, "mix" to 0.3))),
        TonePreset("rw.keys.lead", "Synth-Lead", "Synth lead", TonePreset.Family.KEYS, listOf(fx(EffectKind.DELAY, "timeMs" to 330.0, "feedback" to 0.3, "mix" to 0.2))),
    )
    fun find(id: String?): TonePreset? = all.firstOrNull { it.id == id }

    /** Preset of a track: explicit choice, otherwise derived from instrument kind and GM program. */
    fun forTrack(track: Track): TonePreset {
        find(track.sound.presetId)?.let { return it }
        val program = track.instrument.midiProgram
        val id = when (track.instrument.kind) {
            InstrumentKind.PERCUSSION -> "rw.drums.rock"
            InstrumentKind.BASS -> when (program) { 32, 33, 35 -> "rw.bass.finger"; else -> "rw.bass.pick" }
            InstrumentKind.GUITAR -> when (program) { 24, 25 -> "rw.guitar.acoustic"; 26, 27, 28 -> "rw.guitar.clean"; 29 -> "rw.guitar.crunch"; 30, 31 -> "rw.guitar.highgain"; else -> "rw.guitar.clean" }
            InstrumentKind.PIANO, InstrumentKind.OTHER -> keysFor(program)
        }
        return find(id)!!
    }
    private fun keysFor(program: Int) = when (program) {
        in 0..3, in 6..7 -> "rw.keys.piano"; 4, 5 -> "rw.keys.epiano"; in 16..23 -> "rw.keys.organ"
        in 40..55, in 88..95 -> "rw.keys.pad"; in 24..31 -> "rw.guitar.clean"; in 32..39 -> "rw.bass.pick"; else -> "rw.keys.lead"
    }

    /** Chain actually used by the engine: the track's own effects when edited, otherwise the preset default. */
    fun effectiveChain(track: Track): List<EffectUnit> = track.mix.effects.ifEmpty { forTrack(track).defaultChain }

    internal fun stringTone(preset: TonePreset): StringTone = when (preset.id) {
        "rw.guitar.highgain" -> StringTone(0.16f, 7.0, 0.11, 0.12f, 0.05f, 0.55f)
        "rw.guitar.crunch" -> StringTone(0.2f, 6.0, 0.13, 0.18f, 0.04f, 0.6f)
        "rw.guitar.acoustic" -> StringTone(0.1f, 3.6, 0.17, 0.08f, 0.05f, 0.45f)
        "rw.bass.pick" -> StringTone(0.26f, 8.0, 0.14, 0.28f, 0.05f, 0.85f)
        "rw.bass.finger" -> StringTone(0.42f, 8.0, 0.22, 0.45f, 0.02f, 0.95f)
        else -> StringTone(0.15f, 5.0, 0.14, 0.15f, 0.03f, 0.65f)
    }
    /** Electric guitar presets play the bundled DI samples (when a bank is installed); acoustic and bass stay modelled. */
    internal fun isSampledGuitar(preset: TonePreset) = preset.id == "rw.guitar.highgain" || preset.id == "rw.guitar.crunch" || preset.id == "rw.guitar.clean"
    /**
     * Input trim of the normalised DI samples (SampleBank.Region.gain: -23 dBFS RMS over the first 400 ms, peaks <= -3 dBFS) per preset,
     * measured in QA cycle 18: a v96 note reached the Clean amp around -16 dBFS (-12.7 dBFS since the cycle-22 compressor make-up; no
     * saturation), Crunch sits near its knee so
     * pick dynamics survive, High Gain stays fully saturated while a single v127 note peaks below -6 dBFS before the gate.
     */
    internal fun sampleGain(preset: TonePreset): Float = when (preset.id) { "rw.guitar.highgain" -> 0.6f; "rw.guitar.crunch" -> 0.28f; else -> 0.3f }
    /**
     * Output trim of a sampled preset (QA cycle 22), applied after its effect chain while the sample bank plays it; never above
     * 0 dB. Reference phrase at the master, ITU-R BS.1770-4 integrated: Clean -23.2, Crunch -17.0, High Gain -15.9 LUFS before,
     * -24.3 / -18.0 / -17.9 with these trims (and the Clean compressor make-up above). High Gain and Crunch are lowered only as far as the
     * standard band keeps its cycle-21 guitar-to-bass/drums balance and loudness; Clean is held by its stem headroom (v127 chords at
     * the highest track fader 1.0: -0.8 dBFS). The 3 LU spread target is not reachable that way: Clean's peaks are its
     * pick transients, which the compressor lets through by design (6.4 LU). A level after the chain changes nothing of the character.
     * The modelled fallback (no bank) is untouched.
     */
    internal fun outputTrimDb(preset: TonePreset): Double = when (preset.id) { "rw.guitar.highgain" -> -2.5; "rw.guitar.crunch" -> -1.0; "rw.guitar.clean" -> -4.0; else -> 0.0 }
    /** Modelled harmonics inside a sampled preset follow the sample trim, so a pinch harmonic sits ~3 dB under a picked note at the amp input. */
    internal fun harmonicGain(preset: TonePreset): Float = when (preset.id) { "rw.guitar.highgain" -> 0.45f; "rw.guitar.crunch" -> 0.2f; else -> 0.22f }
    internal fun keysMode(preset: TonePreset): KeysMode = when (preset.id) {
        "rw.keys.epiano" -> KeysMode.EPIANO; "rw.keys.organ" -> KeysMode.ORGAN; "rw.keys.pad" -> KeysMode.PAD; "rw.keys.lead" -> KeysMode.LEAD; else -> KeysMode.PIANO
    }
}
