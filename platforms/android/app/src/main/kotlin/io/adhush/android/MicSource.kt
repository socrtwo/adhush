package io.adhush.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import io.adhush.core.AudioBlock

/**
 * 48 kHz mono float blocks of 100 ms, matching the core's CaptureConfig.
 * UNPROCESSED where the device offers it, else CAMCORDER — never a voice
 * source, whose AGC would erase the loudness deltas the detector lives on.
 */
class MicSource(context: Context, private val onBlock: (AudioBlock) -> Unit) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var lastBlockAt = 0L
        private set
    @Volatile private var produced = 0L
    /** The engine's clock: seconds of audio delivered so far. */
    val mediaTime: Double get() = produced.toDouble() / RATE

    private val chosen: Pair<Int, String> =
        if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            Pair(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED")
        else Pair(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER")
    private val source: Int get() = chosen.first
    val sourceName: String get() = chosen.second

    @SuppressLint("MissingPermission")  // the service checks RECORD_AUDIO before constructing this
    fun start() {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, BLOCK * 4 * 4))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({
            val buf = FloatArray(BLOCK)
            while (running) {
                var filled = 0
                while (filled < BLOCK && running) {
                    val n = rec.read(buf, filled, BLOCK - filled, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { Thread.sleep(10); continue }
                    filled += n
                }
                if (!running) break
                val ts = produced.toDouble() / RATE
                produced += BLOCK
                lastBlockAt = System.currentTimeMillis()
                onBlock(AudioBlock(ts, buf.copyOf(), RATE))
            }
        }, "adhush-mic").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }

    companion object {
        const val RATE = 48_000
        const val BLOCK = 4_800
    }
}
