package dev.riffworkbench.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.AudioInputSource
import dev.riffworkbench.ports.AudioSink
import dev.riffworkbench.ports.SinkFormat

/** Starts the render/capture thread with Android's real-time audio priority. */
val audioThreadStarter: (String, () -> Unit) -> Thread = { name, body ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); body() }, name).apply { isDaemon = true; start() }
}

/**
 * C-AUDIO output: float stereo AudioTrack in low-latency mode at the device's native rate and burst size.
 * The presented position comes from AudioTrack timestamps (the hardware clock), extrapolated to now.
 *
 * Buffer: [OUTPUT_BUFFER_BLOCKS] bursts (at least the track's minimum). A streaming track started empty underruns at
 * once and the output path leaves standby only when data arrives, so [open] primes the whole buffer with silence before
 * `play()` (the documented way to prime a MODE_STREAM track) - the first rendered block then queues behind a full buffer
 * instead of being presented into a waking output. Trade-off: the buffer is the output latency (write() blocks until
 * there is room), so 8 blocks at 192 frames / 48 kHz are 32 ms from render to speaker and the same for note previews,
 * against 16 ms with 4 blocks; in return a single slow (cold, interpreted) block of up to 7 bursts no longer drops out.
 * The transport reports this latency through [SinkFormat.latencyFrames] and compensates the playhead with the timestamps.
 */
class AudioTrackSink(private val context: Context) : AudioSink {
    companion object {
        /** Output buffer in bursts; see the class note on the latency trade-off. */
        const val OUTPUT_BUFFER_BLOCKS = 8
    }
    @Volatile private var track: AudioTrack? = null
    // presentedFrame() runs on the render, poller and stats threads; one shared AudioTimestamp would mix their reads.
    private val timestamp = ThreadLocal.withInitial { AudioTimestamp() }
    private var rate = 48_000

    override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = try {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        rate = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8_000..192_000 } ?: preferredSampleRate
        val burst = manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 32..4096 } ?: preferredBlockFrames
        val block = burst.coerceIn(64, 1024)
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferBytes = maxOf(minBytes, block * 2 * 4 * OUTPUT_BUFFER_BLOCKS)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); Outcome.Failure(FailureCode.UNAVAILABLE, "Audioausgabe konnte nicht initialisiert werden", contractId = "C-AUDIO") }
        else {
            try {
                // Prime before play(): up to bufferSizeInBytes may be written to a stopped streaming track; a short
                // transfer count here only means less silence ahead of the first block, never a failure.
                val silence = FloatArray(t.bufferSizeInFrames * 2)
                var primed = 0
                while (primed < silence.size) {
                    val w = t.write(silence, primed, silence.size - primed, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    primed += w
                }
                t.play()
            } catch (e: Exception) { t.release(); throw e }
            track = t
            val device = t.routedDevice?.productName?.toString() ?: "Lautsprecher"
            Outcome.Success(SinkFormat(rate, block, t.bufferSizeInFrames, device))
        }
    } catch (e: Exception) {
        Outcome.Failure(FailureCode.UNAVAILABLE, e.message ?: "Audioausgabe nicht verfügbar", contractId = "C-AUDIO")
    }

    override fun write(buffer: FloatArray, frames: Int): Int {
        val t = track ?: return 0
        val written = try { t.write(buffer, 0, frames * 2, AudioTrack.WRITE_BLOCKING) } catch (e: IllegalStateException) { -1 }
        return if (written < 0) 0 else written / 2
    }

    override fun presentedFrame(): Long? = presentedFrameOrNegative().takeIf { it >= 0 }

    /** Primitive variant for the render thread (no Long boxing; QA cycle 20). */
    override fun presentedFrameOrNegative(): Long {
        val t = track ?: return -1L
        return try {
            val ts = timestamp.get()
            if (t.getTimestamp(ts)) (ts.framePosition + (System.nanoTime() - ts.nanoTime) * rate / 1_000_000_000L).coerceAtLeast(0) else -1L
        } catch (e: IllegalStateException) { -1L }
    }

    override fun underruns(): Long = try { track?.underrunCount?.toLong() ?: 0 } catch (e: IllegalStateException) { 0 }

    override fun close() {
        val t = track ?: return
        track = null
        try { t.pause(); t.flush(); t.stop() } catch (_: IllegalStateException) { }
        t.release()
    }
}

/** C-TUNER input: mono float AudioRecord with the least processed source the device offers. */
class AudioRecordSource(private val context: Context) : AudioInputSource {
    @Volatile private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun open(preferredSampleRate: Int): Outcome<Int> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return Outcome.Failure(FailureCode.PERMISSION, "Mikrofonzugriff ist nicht erlaubt", contractId = "C-TUNER")
        return try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val unprocessed = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val rate = preferredSampleRate
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (min <= 0) return Outcome.Failure(FailureCode.UNAVAILABLE, "Mikrofon unterstützt das Format nicht", contractId = "C-TUNER")
            val r = AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min, 8192 * 4)).build()
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return Outcome.Failure(FailureCode.UNAVAILABLE, "Mikrofon konnte nicht geöffnet werden", contractId = "C-TUNER") }
            try { r.startRecording() } catch (e: Exception) { r.release(); throw e }
            record = r
            Outcome.Success(rate)
        } catch (e: Exception) { Outcome.Failure(FailureCode.UNAVAILABLE, e.message ?: "Mikrofon nicht verfügbar", contractId = "C-TUNER") }
    }

    override fun read(buffer: FloatArray, frames: Int): Int {
        val r = record ?: return -1
        return try { r.read(buffer, 0, frames, AudioRecord.READ_BLOCKING) } catch (e: IllegalStateException) { -1 }
    }

    override fun close() {
        val r = record ?: return
        record = null
        try { r.stop() } catch (_: IllegalStateException) { }
        r.release()
    }
}

/** Requests audio focus while playing and pauses on focus loss or when headphones are unplugged. */
class PlaybackGuard(private val context: Context, private val onInterrupt: () -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onInterrupt() }
        .build()
    private val noisy = object : BroadcastReceiver() { override fun onReceive(c: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onInterrupt() } }
    private var active = false

    fun acquire(): Boolean {
        if (active) return true
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            active = true
        }
        return granted
    }

    fun release() {
        if (!active) return
        active = false
        manager.abandonAudioFocusRequest(request)
        try { context.unregisterReceiver(noisy) } catch (_: IllegalArgumentException) { }
    }
}
