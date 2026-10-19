package io.adhush.android

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import io.adhush.core.AudioBlock
import kotlin.math.abs

/**
 * The phone's own playback — a live stream in the browser or an app — as the
 * same 48 kHz mono float blocks the microphone gives (ADR 0018). Android 10's
 * playback capture: the user consents through the "record or cast" dialog,
 * and any player that has not opted out is heard before the speaker, so the
 * phone may be silent. A player that blocks capture yields digital silence;
 * [silentS] counts the consecutive seconds of it so the service can say so.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class StreamSource(private val projection: MediaProjection, private val onBlock: (AudioBlock) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var lastBlockAt = 0L
        private set
    @Volatile private var produced = 0L
    @Volatile var silentS = 0.0
        private set
    val mediaTime: Double get() = produced.toDouble() / MicSource.RATE

    @SuppressLint("MissingPermission")  // RECORD_AUDIO is checked by the service before this is built
    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(MicSource.RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(MicSource.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, MicSource.BLOCK * 4 * 4))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "playback capture failed to initialise" }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({
            val buf = FloatArray(MicSource.BLOCK)
            while (running) {
                var filled = 0
                while (filled < MicSource.BLOCK && running) {
                    val n = rec.read(buf, filled, MicSource.BLOCK - filled, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { Thread.sleep(10); continue }
                    filled += n
                }
                if (!running) break
                var peak = 0f
                for (v in buf) { val a = abs(v); if (a > peak) peak = a }
                silentS = if (peak < 1e-4f) silentS + MicSource.BLOCK.toDouble() / MicSource.RATE else 0.0
                val ts = produced.toDouble() / MicSource.RATE
                produced += MicSource.BLOCK
                lastBlockAt = System.currentTimeMillis()
                onBlock(AudioBlock(ts, buf.copyOf(), MicSource.RATE))
            }
        }, "adhush-stream").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }
}
