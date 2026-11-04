package dev.riffworkbench.platform

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Untrusted bytes are read from memory; no temporary file is written. */
private class BytesDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= bytes.size) return -1
        val n = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }
    override fun getSize(): Long = bytes.size.toLong()
    override fun close() = Unit
}

/**
 * C-DECODE on Android: MediaExtractor + MediaCodec, so every container and codec the device ships (MP3, AAC/M4A,
 * Ogg Vorbis/Opus, FLAC, …) works without bundled codecs. Output arrives as 16-bit or float PCM and is streamed in
 * decoder-sized chunks to the sink, which resamples and enforces the length limit.
 */
class MediaCodecAudioDecoder : AudioDecoder {
    override suspend fun probe(bytes: ByteArray): Outcome<AudioInfo> = withContext(Dispatchers.Default) {
        withTrack(bytes) { _, format ->
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) == null)
                Outcome.Failure(FailureCode.VALIDATION, "Dieses Gerät hat keinen Decoder für $mime", contractId = CONTRACT)
            else Outcome.Success(info(format))
        }
    }

    override suspend fun decode(bytes: ByteArray, sink: PcmSink): Outcome<AudioInfo> = withContext(Dispatchers.Default) {
        withTrack(bytes) { extractor, format ->
            val codec = try { MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME).orEmpty()) }
            catch (e: IOException) { return@withTrack Outcome.Failure(FailureCode.VALIDATION, "Kein Decoder für dieses Audioformat", contractId = CONTRACT) }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val bufferInfo = MediaCodec.BufferInfo()
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var floatPcm = false
                var chunk = ShortArray(0)
                var inputDone = false
                var idle = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val input = codec.getInputBuffer(index) ?: return@withTrack corrupt()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(index, 0, size, maxOf(0L, extractor.sampleTime), 0); extractor.advance() }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        index >= 0 -> {
                            idle = 0
                            val output = codec.getOutputBuffer(index) ?: return@withTrack corrupt()
                            output.limit(bufferInfo.offset + bufferInfo.size); output.position(bufferInfo.offset)
                            val samples = if (floatPcm) bufferInfo.size / 4 else bufferInfo.size / 2
                            if (chunk.size < samples) chunk = ShortArray(samples)
                            if (floatPcm) {
                                val floats = output.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                for (i in 0 until samples) chunk[i] = (floats.get(i).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                            } else output.order(ByteOrder.nativeOrder()).asShortBuffer().get(chunk, 0, samples)
                            codec.releaseOutputBuffer(index, false)
                            val frames = if (channels > 0) samples / channels else 0
                            if (frames > 0 && !sink.accept(rate, channels, chunk, frames)) break
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                        // Some decoders never flag the end; give up after ~5 s without output once all input is queued.
                        inputDone && ++idle > 500 -> break
                    }
                }
                Outcome.Success(info(format))
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        }
    }

    private inline fun withTrack(bytes: ByteArray, block: (MediaExtractor, MediaFormat) -> Outcome<AudioInfo>): Outcome<AudioInfo> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(BytesDataSource(bytes))
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return Outcome.Failure(FailureCode.VALIDATION, "Die Datei enthält keine Audiospur", contractId = CONTRACT)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (!format.containsKey(MediaFormat.KEY_SAMPLE_RATE) || !format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                return Outcome.Failure(FailureCode.CORRUPT, "Audiospur ohne Abtastrate oder Kanalzahl", contractId = CONTRACT)
            block(extractor, format)
        } catch (e: CancellationException) {
            throw e // a CancellationException is an IllegalStateException; it must not become a decode failure
        } catch (e: IOException) {
            Outcome.Failure(FailureCode.VALIDATION, "Audioformat nicht erkannt", contractId = CONTRACT)
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(FailureCode.VALIDATION, e.message ?: "Ungültige Audiodaten", contractId = CONTRACT)
        } catch (e: IllegalStateException) { // includes MediaCodec.CodecException
            Outcome.Failure(FailureCode.CORRUPT, "Decoder-Fehler: ${e.message ?: e.javaClass.simpleName}", contractId = CONTRACT)
        } finally {
            extractor.release()
        }
    }

    private fun info(format: MediaFormat) = AudioInfo(
        format.getString(MediaFormat.KEY_MIME).orEmpty(), format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L)

    private fun corrupt() = Outcome.Failure(FailureCode.CORRUPT, "Decoder lieferte keinen Puffer", contractId = CONTRACT)

    private companion object {
        const val CONTRACT = "C-DECODE"
        const val TIMEOUT_US = 10_000L
    }
}
