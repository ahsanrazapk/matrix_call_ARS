// DEPRECATED — superseded by voice/VoiceManager.kt
// This file is retained only to avoid breaking any existing Gradle incremental
// cache.  Do not use this class; use voice.VoiceManager instead.
@file:Suppress("UNUSED")
package com.dropslabtechnology.matrixcall.matrix_call

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vivoka.csdk.asr.Engine
import com.vivoka.csdk.asr.models.AsrResult
import com.vivoka.csdk.asr.recognizer.IRecognizerListener
import com.vivoka.csdk.asr.recognizer.Recognizer
import com.vivoka.csdk.asr.recognizer.RecognizerErrorCode
import com.vivoka.csdk.asr.recognizer.RecognizerEventCode
import com.vivoka.csdk.asr.recognizer.RecognizerResultType
import com.vivoka.csdk.asr.utils.AsrResultParser
import com.vivoka.vsdk.Constants
import com.vivoka.vsdk.Exception
import com.vivoka.vsdk.Vsdk
import com.vivoka.vsdk.audio.Pipeline

class VoiceManager(context: Context) {

    private val context: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var started = false
    private var recognizer: Recognizer? = null

    var commandCallback: CommandCallback? = null
    var currentModel: String = ASR_APPLICATION

    private var pipeline: Pipeline? = null
    private var audioRecorder: AudioRecorder? = null

    init {
        initVivoka()
    }

    private fun initVivoka() {
        val assetsPath = context.filesDir.absolutePath + Constants.vsdkPath
        try {
            AsrAssetsExtractor(context, assetsPath) {
                startVSDK()
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "voice_command_1: ${e.message}")
        }
    }

    private fun startVSDK() {
        try {
            Vsdk.init(context, CONFIG_PATH) { success ->
                if (!success) {
                    Log.e(TAG, "Vsdk.init failed")
                    return@init
                }

                try {
                    Engine.getInstance().init(context) { success1 ->
                        if (!success1) {
                            Log.e(TAG, "Engine.init failed")
                            return@init
                        }
                        make(currentModel)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "voice_command_2: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "voice_command_3: ${e.message}")
        }
    }

    private fun make(model: String?) {
        started = true

        val recognizerListener = object : IRecognizerListener {
            override fun onEvent(eventCode: RecognizerEventCode?, timeMarker: Int, message: String?) {}

            override fun onResult(result: String?, resultType: RecognizerResultType?, isFinal: Boolean) {
                processRecognitionResult(result, resultType, currentModel)
            }

            override fun onError(error: RecognizerErrorCode, message: String?) {
                Log.e("onError", "${error.name} $message")
            }

            override fun onWarning(error: RecognizerErrorCode, message: String?) {
                Log.e("onWarning", "${error.name} $message")
            }
        }

        try {
            recognizer = Engine.getInstance().getRecognizer(ASR_RECOGNIZER, recognizerListener)
            recognizer!!.setModel(model)

            restartPipeline(inCall = false)

        } catch (e: Exception) {
            Log.e(TAG, "voice_command_4: ${e.message} ${e.formattedMessage}")
        }
    }


    fun setCallMode(inCall: Boolean) {
        restartPipeline(inCall)
    }

    private fun restartPipeline(inCall: Boolean) {
        // stop old
        try { pipeline?.stop() } catch (_: Exception) {}
        try { audioRecorder?.stop() } catch (_: Exception) {}
        pipeline = null
        audioRecorder = null

        if (inCall) {
            // ✅ During call: don't start mic recorder again
            // WebRTC/Matrix already uses microphone.
            return
        }

        configureAudioMode(inCall = false)

        // build new
        pipeline = Pipeline()
        pipeline!!.pushBackConsumer(recognizer)

        // Choose one:
        // - VOICE_RECOGNITION is good for ASR
        // - MIC is most compatible
        val source = MediaRecorder.AudioSource.VOICE_RECOGNITION

        audioRecorder = AudioRecorder(source)
        pipeline!!.setProducer(audioRecorder)
        pipeline!!.start()
    }

    private fun configureAudioMode(inCall: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = if (inCall) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = false
    }

    fun stopVivoka() {
        currentModel = ASR_APPLICATION_FREE
        Thread {
            try { recognizer?.setModel(ASR_APPLICATION_FREE) } catch (_: Exception) {}
        }.start()
    }

    fun resumeVivoka() {
        currentModel = ASR_APPLICATION
        Thread {
            try { recognizer?.setModel(ASR_APPLICATION) } catch (_: Exception) {}
        }.start()
    }

    private fun processRecognitionResult(
        result: String?,
        resultType: RecognizerResultType?,
        model: String?
    ) {
        if (result.isNullOrEmpty()) return

        if (resultType == RecognizerResultType.ASR) {
            val asrResult: AsrResult? = try {
                AsrResultParser.parseResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "voice_command_5: ${e.message}")
                null
            }

            asrResult?.hypotheses?.firstOrNull()?.let { hypo ->
                val threshold = if (model == ASR_APPLICATION_FREE) 7000 else 4000

                if (hypo.confidence >= threshold) {
                    if (model == ASR_APPLICATION_FREE) {
                        val command = hypo.text.replace("Hey Sam", "").trim()
                        mainHandler.post { commandCallback?.getSpeechSlot(command) }
                    } else if (hypo.text.contains("Hey Sam")) {
                        val command = hypo.text.replace("Hey Sam", "").trim()
                        mainHandler.post { commandCallback?.getCommandSlot(command) }
                    }
                }

                Log.e("AsrResultHypothesis", "[${hypo.confidence}] - $hypo")
            }
        }

        Thread {
            try {
                if (started) recognizer?.setModel(model)
            } catch (e: Exception) {
                Log.e(TAG, "voice_command_6: ${e.message}")
            }
        }.start()
    }

    fun release() {
        commandCallback = null
        try { pipeline?.stop() } catch (_: Exception) {}
        try { audioRecorder?.stop() } catch (_: Exception) {}
        pipeline = null
        audioRecorder = null
        recognizer = null
    }

    companion object {
        private const val TAG = "VoiceManager"
        private const val ASR_APPLICATION = "grammerModel"
        private const val ASR_APPLICATION_FREE = "free"
        private const val ASR_RECOGNIZER = "rec_1"
        private const val CONFIG_PATH = "config/vsdk.json"
    }
}
