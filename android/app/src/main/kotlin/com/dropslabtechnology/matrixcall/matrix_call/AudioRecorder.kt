package com.dropslabtechnology.matrixcall.matrix_call

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.vivoka.vsdk.audio.ProducerModule
import com.vivoka.vsdk.util.BufferUtils
import kotlin.concurrent.Volatile

class AudioRecorder(private val audioSource: Int) : ProducerModule() {
    private val TAG = "AudioRecorder"

    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val SAMPLE_RATE = 16000
    private val SAMPLES_PER_BUFFER = 1024

    private var recorder: AudioRecord? = null
    private var recorderThread: Thread? = null

    @Volatile
    private var running = false

    override fun isRunning(): Boolean = running

    override fun open(): Boolean = true
    override fun run(): Boolean = false

    private fun recorderLoop() {
        val r = recorder
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${r?.state})")
            running = false
            return
        }

        try {
            r.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}")
            running = false
            return
        }

        val shortBuf = ShortArray(SAMPLES_PER_BUFFER)

        while (running && recorder != null) {
            val read = try {
                r.read(shortBuf, 0, shortBuf.size)
            } catch (e: Exception) {
                Log.e(TAG, "read failed: ${e.message}")
                break
            }

            if (read <= 0) {
                // read can be 0 or error codes
                continue
            }

            try {
                // send only valid portion
                val bytes = BufferUtils.convertShortsToBytes(shortBuf.copyOf(read))
                dispatchAudio(
                    1,
                    SAMPLE_RATE,
                    bytes,
                    !running
                )
            } catch (e: Exception) {
                Log.e(TAG, "dispatchAudio failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (running) return true

        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (min <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $min")
            return false
        }

        val bufferSize = min * 2

        val r = try {
            AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord ctor failed: ${e.message}")
            return false
        }

        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not be initialized, state=${r.state}")
            try { r.release() } catch (_: Exception) {}
            return false
        }

        recorder = r
        running = true

        recorderThread = Thread({ recorderLoop() }, "VivokaAudioRecorder").apply {
            start()
        }

        return true
    }

    override fun stop(): Boolean {
        running = false

        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null

        try { recorderThread?.join(300) } catch (_: Exception) {}
        recorderThread = null

        return true
    }
}
