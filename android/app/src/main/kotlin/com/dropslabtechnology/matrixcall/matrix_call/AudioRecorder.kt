package com.dropslabtechnology.matrixcall.matrix_call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import androidx.core.content.ContextCompat
import com.vivoka.vsdk.audio.ProducerModule
import com.vivoka.vsdk.util.BufferUtils

/**
 * Low-level PCM audio producer that feeds the Vivoka [Pipeline].
 *
 * Improvements over the original:
 *  - Explicit RECORD_AUDIO permission check before constructing [AudioRecord].
 *  - Volatile [running] flag guards the recorder loop without locking.
 *  - [stop] joins the recorder thread with a generous timeout and resets all
 *    references so subsequent [start] calls work correctly.
 *  - No suppressed MissingPermission lint — the check is real.
 */
class AudioRecorder(
    private val audioSource: Int,
    private val context: Context
) : ProducerModule() {

    private val SAMPLE_RATE       = 16_000
    private val CHANNEL_CONFIG    = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT      = AudioFormat.ENCODING_PCM_16BIT
    private val SAMPLES_PER_FRAME = 1_024

    @Volatile private var running        = false
    @Volatile private var recorder: AudioRecord? = null
    private var recorderThread: Thread?  = null

    override fun isRunning() = running
    override fun open(): Boolean = true
    override fun run():  Boolean = false

    // ── ProducerModule API ────────────────────────────────────────────────────

    override fun start(): Boolean {
        if (running) {
            Log.d(TAG, "start(): already running")
            return true
        }

        // Runtime permission guard — must be held before AudioRecord is created
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "start(): RECORD_AUDIO permission not granted")
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            Log.e(TAG, "start(): getMinBufferSize returned $minBuf")
            return false
        }

        val r = try {
            AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBuf * 2)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "start(): AudioRecord constructor failed: ${e.message}", e)
            return false
        }

        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "start(): AudioRecord not initialized (state=${r.state})")
            try { r.release() } catch (_: Exception) {}
            return false
        }

        recorder = r
        running  = true

        recorderThread = Thread({ recordLoop() }, "VivokaAudioRecorder").also { it.start() }
        Log.d(TAG, "start(): recorder thread launched")
        return true
    }

    override fun stop(): Boolean {
        Log.d(TAG, "stop()")
        running = false

        try { recorder?.stop()    } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null

        // Wait up to 1 s for the loop to exit cleanly
        try { recorderThread?.join(1_000) } catch (_: InterruptedException) {}
        recorderThread = null

        Log.d(TAG, "stop(): done")
        return true
    }

    // ── recorder loop ─────────────────────────────────────────────────────────

    private fun recordLoop() {
        val r = recorder
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "recordLoop: recorder not ready")
            running = false
            return
        }

        try {
            r.startRecording()
            Log.d(TAG, "recordLoop: recording started")
        } catch (e: Exception) {
            Log.e(TAG, "recordLoop: startRecording failed: ${e.message}", e)
            running = false
            return
        }

        val shortBuf = ShortArray(SAMPLES_PER_FRAME)
        while (running && recorder != null) {
            val read = try {
                r.read(shortBuf, 0, shortBuf.size)
            } catch (e: Exception) {
                Log.e(TAG, "recordLoop: read() failed: ${e.message}", e)
                break
            }

            // Negative values are error codes from AudioRecord
            if (read <= 0) continue

            try {
                val bytes = BufferUtils.convertShortsToBytes(shortBuf.copyOf(read))
                dispatchAudio(
                    /* channels   */ 1,
                    /* sampleRate */ SAMPLE_RATE,
                    /* data       */ bytes,
                    /* isLast     */ !running
                )
            } catch (e: Exception) {
                Log.e(TAG, "recordLoop: dispatchAudio failed: ${e.message}", e)
            }
        }

        Log.d(TAG, "recordLoop: exiting")
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
