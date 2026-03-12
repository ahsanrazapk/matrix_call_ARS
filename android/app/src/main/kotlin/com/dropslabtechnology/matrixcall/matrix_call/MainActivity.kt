package com.dropslabtechnology.matrixcall.matrix_call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.arspectra.lightcontrol.spLightController
import com.dropslabtechnology.matrixcall.matrix_call.voice.VoiceCommandListener
import com.dropslabtechnology.matrixcall.matrix_call.voice.VoiceManager
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Single Activity host for the Flutter UI.
 *
 * All voice-command logic lives in [VoiceManager] (singleton).
 * This class only:
 *  - Hosts the Flutter engine + fragment
 *  - Bridges Flutter ↔ native via Method/Event channels
 *  - Manages runtime RECORD_AUDIO permission
 *  - Delegates Activity lifecycle events to [VoiceManager]
 */
class MainActivity : AppCompatActivity() {

    // ── channel names ─────────────────────────────────────────────────────────
    private val CH_METHODS = "vivoka_sdk_flutter/methods"
    private val CH_EVENTS  = "vivoka_sdk_flutter/events"

    // ── constants ─────────────────────────────────────────────────────────────
    private val FLUTTER_ENGINE_ID    = "main_engine"
    private val FLUTTER_CONTAINER_ID = 10_001
    private val REQ_RECORD_AUDIO     = 1001

    // ── state ─────────────────────────────────────────────────────────────────
    @Volatile private var eventSink: EventChannel.EventSink? = null
    private lateinit var lightController: spLightController
    private lateinit var flutterEngine:   FlutterEngine

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // Basic UI container for the Flutter fragment
        val container = FrameLayout(this).apply { id = FLUTTER_CONTAINER_ID }
        setContentView(container)

        lightController = spLightController(this)

        // Obtain or create the (cached) Flutter engine
        val cached = FlutterEngineCache.getInstance().get(FLUTTER_ENGINE_ID)
        flutterEngine = if (cached != null) {
            cached
        } else {
            FlutterEngine(this).also { eng ->
                eng.dartExecutor.executeDartEntrypoint(
                    DartExecutor.DartEntrypoint.createDefault()
                )
                FlutterEngineCache.getInstance().put(FLUTTER_ENGINE_ID, eng)
            }
        }

        if (supportFragmentManager.findFragmentByTag(FLUTTER_FRAGMENT_TAG) == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(
                    FLUTTER_CONTAINER_ID,
                    FlutterFragment.withCachedEngine(FLUTTER_ENGINE_ID).build<FlutterFragment>(),
                    FLUTTER_FRAGMENT_TAG
                )
                .commitNow()
        }

        setupChannels()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        // Resume the microphone when the app comes to the foreground,
        // only if the SDK was already initialised (permission granted previously).
        voiceManager.resume()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        // Pause (but do not release) the microphone when the app goes to background.
        // Vivoka is process-scoped, so we stop the hardware mic to save battery
        // and avoid holding audio focus unnecessarily.
        voiceManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // Only fully release when the process is truly going away (isFinishing).
        // Configuration changes (rotation) must NOT release the SDK.
        if (isFinishing) {
            voiceManager.listener = null
            voiceManager.release()
        }
    }

    // ── permission handling ───────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO granted — initialising VoiceManager")
                initVoiceManager()
            } else {
                Log.w(TAG, "RECORD_AUDIO denied")
                sendEvent("error", "RECORD_AUDIO permission denied")
            }
        }
    }

    // ── channel setup ─────────────────────────────────────────────────────────

    private fun setupChannels() {
        // Event channel — Flutter listens for status / command / speech events
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, CH_EVENTS)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    Log.d(TAG, "EventChannel: Flutter listening")
                    eventSink = events
                    sendEvent("status", "listening")
                }
                override fun onCancel(arguments: Any?) {
                    Log.d(TAG, "EventChannel: Flutter cancelled")
                    eventSink = null
                }
            })

        // Method channel — Flutter calls native methods
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CH_METHODS)
            .setMethodCallHandler { call, result ->
                Log.d(TAG, "MethodChannel: ${call.method}")
                try {
                    when (call.method) {

                        "init" -> {
                            checkPermissionAndInit()
                            result.success(null)
                        }

                        "stopVivoka" -> {
                            voiceManager.switchToFreeModel()
                            sendEvent("status", "stopped")
                            result.success(null)
                        }

                        "resumeVivoka" -> {
                            voiceManager.switchToGrammarModel()
                            sendEvent("status", "resumed")
                            result.success(null)
                        }

                        "release" -> {
                            voiceManager.listener = null
                            voiceManager.release()
                            sendEvent("status", "released")
                            result.success(null)
                        }

                        "setCallMode" -> {
                            val inCall = (call.arguments as? Boolean) ?: false
                            voiceManager.setCallMode(inCall)
                            sendEvent("status", "setCallMode")
                            result.success(null)
                        }

                        "toggleTorch" -> {
                            val enabled = (call.arguments as? Boolean) ?: false
                            toggleTorch(enabled)
                            sendEvent("status", "torch_${if (enabled) "on" else "off"}")
                            result.success(null)
                        }

                        else -> result.notImplemented()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "MethodChannel error: ${call.method}", t)
                    sendEvent("error", t.message ?: "unknown error")
                    result.error("METHOD_FAILED", t.message, null)
                }
            }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private val voiceManager: VoiceManager
        get() = VoiceManager.getInstance(applicationContext)

    /**
     * Check RECORD_AUDIO permission at runtime.
     * If already granted, initialise the SDK immediately.
     * Otherwise show the system permission dialog.
     */
    private fun checkPermissionAndInit() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "RECORD_AUDIO already granted")
                initVoiceManager()
            }
            else -> {
                Log.d(TAG, "Requesting RECORD_AUDIO")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQ_RECORD_AUDIO
                )
            }
        }
    }

    /**
     * Wire the [VoiceCommandListener] and start the SDK.
     * The [VoiceManager.init] call is idempotent — calling it when already
     * initialised is a no-op.
     */
    private fun initVoiceManager() {
        voiceManager.listener = object : VoiceCommandListener {
            override fun onCommand(command: String) {
                Log.d(TAG, "onCommand: '$command'")
                sendEvent("command", command)
            }
            override fun onSpeech(text: String) {
                Log.d(TAG, "onSpeech: '$text'")
                sendEvent("speech", text)
            }
            override fun onStatusChanged(status: String) {
                Log.d(TAG, "voiceStatus: $status")
                sendEvent("status", status)
            }
            override fun onError(message: String) {
                Log.e(TAG, "voiceError: $message")
                sendEvent("error", message)
            }
        }
        voiceManager.init()
    }

    private fun toggleTorch(enabled: Boolean) {
        lightController.action(
            listOf(spLightController.Controllable.FRONT_TORCH),
            if (enabled) spLightController.Action.ON else spLightController.Action.OFF
        )
    }

    /**
     * Thread-safe event dispatch to Flutter.
     * [EventChannel.EventSink.success] must be called on the main thread.
     */
    private fun sendEvent(type: String, text: String?) {
        // EventSink is always accessed on the main thread (Flutter binding guarantee).
        eventSink?.success(hashMapOf("type" to type, "text" to text))
    }

    companion object {
        private const val TAG                = "MainActivity"
        private const val FLUTTER_FRAGMENT_TAG = "flutter_fragment"
    }
}
