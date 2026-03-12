package com.dropslabtechnology.matrixcall.matrix_call.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dropslabtechnology.matrixcall.matrix_call.AsrAssetsExtractor
import com.dropslabtechnology.matrixcall.matrix_call.AudioRecorder
import com.vivoka.csdk.asr.Engine
import com.vivoka.csdk.asr.models.AsrResult
import com.vivoka.csdk.asr.recognizer.IRecognizerListener
import com.vivoka.csdk.asr.recognizer.Recognizer
import com.vivoka.csdk.asr.recognizer.RecognizerErrorCode
import com.vivoka.csdk.asr.recognizer.RecognizerEventCode
import com.vivoka.csdk.asr.recognizer.RecognizerResultType
import com.vivoka.csdk.asr.utils.AsrResultParser
import com.vivoka.vsdk.Constants
import com.vivoka.vsdk.Vsdk
import com.vivoka.vsdk.audio.Pipeline
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralised, singleton-scoped voice manager.
 *
 * Responsibilities:
 *  - Initialise the Vivoka SDK once (via [MyApplication]).
 *  - Own the [Recognizer] and [Pipeline] for the lifetime of the process.
 *  - Start / stop the microphone safely, preventing duplicate instances.
 *  - Handle audio-focus requests so the mic co-exists correctly with WebRTC.
 *  - Post all listener callbacks on the main thread.
 *
 * Usage:
 *   VoiceManager.instance.setListener(...)
 *   VoiceManager.instance.init(context)   // called once from Application
 */
class VoiceManager private constructor(context: Context) {

    // ── singleton ────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "VoiceManager"

        private const val ASR_GRAMMAR_MODEL = "grammerModel"
        private const val ASR_FREE_MODEL    = "free"
        private const val ASR_RECOGNIZER_ID = "rec_1"
        private const val CONFIG_PATH       = "config/vsdk.json"

        /** Confidence thresholds — lower in free mode to catch more speech. */
        private const val CONFIDENCE_GRAMMAR = 4_000
        private const val CONFIDENCE_FREE    = 7_000

        @Volatile private var _instance: VoiceManager? = null

        fun getInstance(context: Context): VoiceManager =
            _instance ?: synchronized(this) {
                _instance ?: VoiceManager(context.applicationContext).also { _instance = it }
            }
    }

    // ── fields ───────────────────────────────────────────────────────────────
    private val appContext: Context = context.applicationContext
    private val mainHandler  = Handler(Looper.getMainLooper())

    /** Background executor — single thread to serialise all SDK operations. */
    private val sdkExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VoiceManager-SDK").apply { isDaemon = true }
    }

    private val sdkInitialised  = AtomicBoolean(false)
    private val micRunning      = AtomicBoolean(false)
    private val inCallMode      = AtomicBoolean(false)

    @Volatile private var recognizer:   Recognizer?    = null
    @Volatile private var pipeline:     Pipeline?      = null
    @Volatile private var audioRecorder: AudioRecorder? = null
    @Volatile private var currentModel:  String        = ASR_GRAMMAR_MODEL

    /** External listener — updated from the main thread by [MainActivity]. */
    @Volatile var listener: VoiceCommandListener? = null

    // Audio focus (API 26+)
    private var audioFocusRequest: AudioFocusRequest? = null

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Begin async SDK initialisation.  Safe to call multiple times — will
     * no-op if already initialised or in progress.
     */
    fun init() {
        if (sdkInitialised.get()) {
            Log.d(TAG, "init: already initialised, skipping")
            postStatus("already_initialized")
            return
        }
        Log.d(TAG, "init: starting SDK initialisation")
        sdkExecutor.execute { extractAssetsAndInit() }
    }

    /** Switch to free-speech (post wake-word) model. */
    fun switchToFreeModel() {
        Log.d(TAG, "switchToFreeModel")
        sdkExecutor.execute {
            currentModel = ASR_FREE_MODEL
            setModelSafe(ASR_FREE_MODEL)
        }
    }

    /** Switch back to grammar / wake-word detection model. */
    fun switchToGrammarModel() {
        Log.d(TAG, "switchToGrammarModel")
        sdkExecutor.execute {
            currentModel = ASR_GRAMMAR_MODEL
            setModelSafe(ASR_GRAMMAR_MODEL)
        }
    }

    /**
     * Call when a VoIP call starts/ends.
     * During a call we release the mic so WebRTC can own it exclusively.
     */
    fun setCallMode(inCall: Boolean) {
        Log.d(TAG, "setCallMode(inCall=$inCall)")
        inCallMode.set(inCall)
        sdkExecutor.execute {
            if (inCall) {
                stopMicLocked()
                abandonAudioFocus()
            } else {
                startMicLocked()
            }
        }
    }

    /**
     * Pause the microphone (e.g. app goes to background without a call).
     * A paired [resume] will restore it.
     */
    fun pause() {
        Log.d(TAG, "pause: stopping mic")
        sdkExecutor.execute { stopMicLocked() }
    }

    /**
     * Resume the microphone after [pause], only if not in a call.
     */
    fun resume() {
        Log.d(TAG, "resume: starting mic if not in call")
        sdkExecutor.execute {
            if (!inCallMode.get()) startMicLocked()
        }
    }

    /**
     * Fully release all SDK resources.
     * After this call the instance should not be reused; call [init] again if needed.
     */
    fun release() {
        Log.d(TAG, "release: tearing down SDK")
        listener = null
        sdkExecutor.execute {
            stopMicLocked()
            abandonAudioFocus()
            try { recognizer = null } catch (_: Exception) {}
            sdkInitialised.set(false)
        }
    }

    // ── internal: asset extraction → SDK init ────────────────────────────────

    /**
     * Runs on [sdkExecutor].  Extracts ASR model assets then initialises the SDK.
     * Assets are only copied once; subsequent launches skip the I/O work.
     */
    private fun extractAssetsAndInit() {
        val assetsPath = appContext.filesDir.absolutePath + Constants.vsdkPath
        Log.d(TAG, "extractAssetsAndInit: assetsPath=$assetsPath")

        AsrAssetsExtractor(appContext, assetsPath) {
            // This callback fires on the main thread (see AsrAssetsExtractor).
            // Re-submit back to the SDK executor for SDK calls.
            sdkExecutor.execute { initVsdk() }
        }.start()
    }

    /** Runs on [sdkExecutor]. */
    private fun initVsdk() {
        try {
            Vsdk.init(appContext, CONFIG_PATH) { success ->
                if (!success) {
                    Log.e(TAG, "Vsdk.init failed")
                    postError("Vsdk.init failed")
                    return@init
                }
                Log.d(TAG, "Vsdk.init succeeded")
                sdkExecutor.execute { initEngine() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vsdk.init exception: ${e.message}", e)
            postError("Vsdk.init exception: ${e.message}")
        }
    }

    /** Runs on [sdkExecutor]. */
    private fun initEngine() {
        try {
            Engine.getInstance().init(appContext) { success ->
                if (!success) {
                    Log.e(TAG, "Engine.init failed")
                    postError("Engine.init failed")
                    return@init
                }
                Log.d(TAG, "Engine.init succeeded")
                sdkExecutor.execute { createRecognizer() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine.init exception: ${e.message}", e)
            postError("Engine.init exception: ${e.message}")
        }
    }

    /** Runs on [sdkExecutor]. */
    private fun createRecognizer() {
        try {
            val rec = Engine.getInstance().getRecognizer(ASR_RECOGNIZER_ID, recognizerListener)
            rec.setModel(currentModel)
            recognizer = rec                        // assign only on success
            sdkInitialised.set(true)
            Log.d(TAG, "Recognizer created, starting mic")
            postStatus("initialized")
            startMicLocked()
        } catch (e: Exception) {
            Log.e(TAG, "createRecognizer exception: ${e.message}", e)
            postError("createRecognizer exception: ${e.message}")
        }
    }

    // ── internal: mic / pipeline ──────────────────────────────────────────────

    /** Must be called from [sdkExecutor]. */
    private fun startMicLocked() {
        if (!sdkInitialised.get()) {
            Log.d(TAG, "startMicLocked: SDK not ready, deferring")
            return
        }
        if (micRunning.get()) {
            Log.d(TAG, "startMicLocked: already running")
            return
        }
        if (recognizer == null) {
            Log.e(TAG, "startMicLocked: recognizer is null")
            return
        }

        Log.d(TAG, "startMicLocked: requesting audio focus and building pipeline")
        requestAudioFocus()
        setAudioMode(inCall = false)

        try {
            val rec = recognizer!!
            val p   = Pipeline()
            p.pushBackConsumer(rec)

            val ar = AudioRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, appContext)
            p.setProducer(ar)
            p.start()

            pipeline     = p
            audioRecorder = ar
            micRunning.set(true)
            Log.d(TAG, "startMicLocked: pipeline started")
            postStatus("mic_started")
        } catch (e: Exception) {
            Log.e(TAG, "startMicLocked: pipeline start failed: ${e.message}", e)
            postError("mic start failed: ${e.message}")
        }
    }

    /** Must be called from [sdkExecutor]. */
    private fun stopMicLocked() {
        if (!micRunning.get()) return
        Log.d(TAG, "stopMicLocked: tearing down pipeline")
        try { pipeline?.stop() }      catch (_: Exception) {}
        try { audioRecorder?.stop() } catch (_: Exception) {}
        pipeline      = null
        audioRecorder = null
        micRunning.set(false)
        Log.d(TAG, "stopMicLocked: done")
        postStatus("mic_stopped")
    }

    /** Runs on [sdkExecutor] — safe to call repeatedly. */
    private fun setModelSafe(model: String) {
        try {
            recognizer?.setModel(model)
            Log.d(TAG, "setModelSafe: model=$model")
        } catch (e: Exception) {
            Log.e(TAG, "setModelSafe($model): ${e.message}", e)
        }
    }

    // ── internal: audio focus ─────────────────────────────────────────────────

    private fun requestAudioFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "audioFocusChange: $focusChange")
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                            sdkExecutor.execute { stopMicLocked() }
                        AudioManager.AUDIOFOCUS_GAIN ->
                            sdkExecutor.execute { if (!inCallMode.get()) startMicLocked() }
                    }
                }
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun abandonAudioFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun setAudioMode(inCall: Boolean) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = if (inCall) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = false
    }

    // ── internal: recognizer listener ────────────────────────────────────────

    private val recognizerListener = object : IRecognizerListener {
        override fun onEvent(eventCode: RecognizerEventCode?, timeMarker: Int, message: String?) {
            Log.v(TAG, "recognizerEvent: $eventCode [$timeMarker] $message")
        }

        override fun onResult(result: String?, resultType: RecognizerResultType?, isFinal: Boolean) {
            handleResult(result, resultType)
        }

        override fun onError(error: RecognizerErrorCode, message: String?) {
            Log.e(TAG, "recognizerError: ${error.name} — $message")
            postError("recognizer error: ${error.name} — $message")
        }

        override fun onWarning(error: RecognizerErrorCode, message: String?) {
            Log.w(TAG, "recognizerWarning: ${error.name} — $message")
        }
    }

    /**
     * Decode an ASR result, dispatch to [listener], then advance the model.
     * Does NOT spawn a new thread — called from whatever thread Vivoka uses,
     * then delegates to [sdkExecutor] for the model change.
     */
    private fun handleResult(result: String?, resultType: RecognizerResultType?) {
        if (result.isNullOrEmpty()) return
        if (resultType != RecognizerResultType.ASR) return

        val asrResult: AsrResult = try {
            AsrResultParser.parseResult(result) ?: return
        } catch (e: Exception) {
            Log.e(TAG, "parseResult failed: ${e.message}", e)
            return
        }

        val hypo = asrResult.hypotheses?.firstOrNull() ?: return
        val model = currentModel
        Log.d(TAG, "handleResult: model=$model confidence=${hypo.confidence} text='${hypo.text}'")

        val threshold = if (model == ASR_FREE_MODEL) CONFIDENCE_FREE else CONFIDENCE_GRAMMAR
        if (hypo.confidence >= threshold) {
            if (model == ASR_FREE_MODEL) {
                // Free-speech model: strip wake word prefix and emit as speech
                val text = hypo.text.replace("Hey Sam", "").trim()
                Log.d(TAG, "Free speech: '$text'")
                mainHandler.post { listener?.onSpeech(text) }
            } else {
                // Grammar model: dispatch ALL results as commands.
                // Strip the wake-word prefix if present ("Hey Sam accept call" → "accept call").
                // If the entire text IS the wake word, skip (empty command is meaningless).
                val command = hypo.text.replace("Hey Sam", "").trim()
                if (command.isNotEmpty()) {
                    Log.d(TAG, "Command: '$command'")
                    mainHandler.post { listener?.onCommand(command) }
                } else {
                    // Wake word only — switch to free model so user can speak freely
                    Log.d(TAG, "Wake word detected, switching to free model")
                    mainHandler.post { listener?.onStatusChanged("wake_word_detected") }
                }
            }
        }

        // Rearm the recognizer on the SDK executor (not a throwaway thread)
        val snapshot = model
        sdkExecutor.execute { setModelSafe(snapshot) }
    }

    // ── internal: helpers ─────────────────────────────────────────────────────

    private fun postStatus(status: String) =
        mainHandler.post { listener?.onStatusChanged(status) }

    private fun postError(message: String) =
        mainHandler.post { listener?.onError(message) }
}
