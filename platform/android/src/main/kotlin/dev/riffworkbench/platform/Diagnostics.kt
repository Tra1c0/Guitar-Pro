package dev.riffworkbench.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import dev.riffworkbench.ports.PcmOutput
import dev.riffworkbench.ports.SystemProbes
import java.io.File

/** Memory (PSS), thermal status/headroom and per-core CPU clock for the audio diagnostics. Unreadable values are null/empty. */
class AndroidSystemProbes(private val context: Context) : SystemProbes {
    private val power = context.getSystemService(PowerManager::class.java)

    override fun memoryMb(): Double? = runCatching { Debug.getPss() / 1024.0 }.getOrNull()

    override fun thermal(): String? {
        val pm = power ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val status = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "normal"; PowerManager.THERMAL_STATUS_LIGHT -> "leicht"; PowerManager.THERMAL_STATUS_MODERATE -> "mittel"
            PowerManager.THERMAL_STATUS_SEVERE -> "stark"; PowerManager.THERMAL_STATUS_CRITICAL -> "kritisch"; PowerManager.THERMAL_STATUS_EMERGENCY -> "Notfall"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Abschaltung"; else -> "?"
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getThermalHeadroom(0).takeIf { !it.isNaN() } else null
        return status + (headroom?.let { " (Headroom %.2f)".format(java.util.Locale.ROOT, it) } ?: "")
    }

    override fun cpuMhz(): List<Int> = (0 until 16).mapNotNull { cpu ->
        runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000 }.getOrNull()
    }
}

/** Static AudioTrack for short comparison clips; independent of the engine so both sides of a blind test sound identical. */
class AndroidPcmOutput : PcmOutput {
    private var track: AudioTrack? = null

    @Synchronized override fun play(stereo: FloatArray, sampleRate: Int) {
        stop()
        if (stereo.size < 2) return
        val bytes = stereo.size * 4
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(bytes).build()
        try { t.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING); t.play() } catch (e: Exception) { t.release(); return }
        track = t
    }

    @Synchronized override fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
