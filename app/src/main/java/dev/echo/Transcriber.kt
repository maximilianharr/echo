package dev.echo

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.floor

private const val RATE = 16000

/**
 * Transcribes [m4a] with the on-device recognizer by streaming it as 16 kHz mono PCM.
 * Returns null if nothing was recognized or recognition failed.
 */
suspend fun transcribe(ctx: Context, m4a: File, locale: String): String? = coroutineScope {
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return@coroutineScope null
    val (read, write) = ParcelFileDescriptor.createPipe()
    launch(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { decode(m4a, it) }
        } catch (_: IOException) {
            // recognizer stopped reading
        }
    }
    try {
        recognize(ctx, read, locale)
    } finally {
        read.close()
    }
}

private suspend fun recognize(ctx: Context, audio: ParcelFileDescriptor, locale: String): String? =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            val text = StringBuilder()
            fun done() {
                sr.destroy()
                if (cont.isActive) cont.resume(text.toString().trim().ifEmpty { null })
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { text.append(it.trim()).append(' ') }
                }
                override fun onEndOfSegmentedSession() = done()
                override fun onResults(results: Bundle) {
                    onSegmentResults(results)
                    done()
                }
                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    ) sr.triggerModelDownload(intent)
                    done()
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            cont.invokeOnCancellation { ctx.mainExecutor.execute { sr.destroy() } }
            sr.startListening(intent)
        }
    }

/** Decodes [m4a] and writes it to [out] as 16-bit little-endian mono PCM at [RATE] Hz (linear resampling). */
private fun decode(m4a: File, out: OutputStream) {
    val ex = MediaExtractor().apply { setDataSource(m4a.path); selectTrack(0) }
    val fmt = ex.getTrackFormat(0)
    val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val step = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble() / RATE
    val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(fmt, null, null, 0)
    codec.start()
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var t = 0.0 // input position of the next output sample
    var n = 0L // input index of the current chunk's first sample
    var prev = 0 // input sample n - 1
    try {
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o < 0) continue
            val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
            val frames = pcm.remaining() / ch
            val s = IntArray(frames) { pcm.get(it * ch).toInt() }
            codec.releaseOutputBuffer(o, false)
            val buf = ByteBuffer.allocate((frames / step + 2).toInt() * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (t + 1 < n + frames) {
                val i = floor(t).toLong()
                val a = if (i < n) prev else s[(i - n).toInt()]
                val b = s[(i + 1 - n).toInt()]
                buf.putShort((a + (b - a) * (t - i)).toInt().toShort())
                t += step
            }
            if (frames > 0) {
                prev = s[frames - 1]
                n += frames
            }
            out.write(buf.array(), 0, buf.position())
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    } finally {
        codec.stop()
        codec.release()
        ex.release()
    }
}
