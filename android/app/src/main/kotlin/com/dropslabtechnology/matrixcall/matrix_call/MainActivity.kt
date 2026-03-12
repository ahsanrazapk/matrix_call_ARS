package com.dropslabtechnology.matrixcall.matrix_call

import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.arspectra.lightcontrol.spLightController
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : AppCompatActivity() {

    private val METHODS = "vivoka_sdk_flutter/methods"
    private val EVENTS = "vivoka_sdk_flutter/events"
    private val FLUTTER_ENGINE_ID = "main_engine"
    private val FLUTTER_CONTAINER_ID = 10001

    private var eventSink: EventChannel.EventSink? = null
    private var vm: VoiceManager? = null

    private lateinit var lightController: spLightController
    private lateinit var flutterEngine: FlutterEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this).apply { id = FLUTTER_CONTAINER_ID }
        setContentView(container)

        lightController = spLightController(this)

        val cached = FlutterEngineCache.getInstance().get(FLUTTER_ENGINE_ID)
        flutterEngine = if (cached != null) {
            cached
        } else {
            FlutterEngine(this).also { engine ->
                engine.dartExecutor.executeDartEntrypoint(
                    DartExecutor.DartEntrypoint.createDefault()
                )
                FlutterEngineCache.getInstance().put(FLUTTER_ENGINE_ID, engine)
            }
        }
        if (supportFragmentManager.findFragmentByTag("flutter_fragment") == null) {
            val flutterFragment = FlutterFragment
                .withCachedEngine(FLUTTER_ENGINE_ID)
                .build<FlutterFragment>()

            supportFragmentManager
                .beginTransaction()
                .replace(FLUTTER_CONTAINER_ID, flutterFragment, "flutter_fragment")
                .commitNow()
        }

        setupChannels()
    }

    private fun setupChannels() {
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENTS)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    sendEvent("status", "listening")
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHODS)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {

                        "init" -> {
                            if (vm == null) {
                                vm = VoiceManager(applicationContext).also { manager ->
                                    manager.commandCallback = object : CommandCallback {
                                        override fun getCommandSlot(cmd: String?) {
                                            sendEvent("command", cmd)
                                        }

                                        override fun getSpeechSlot(cmd: String?) {
                                            sendEvent("speech", cmd)
                                        }
                                    }
                                }
                                sendEvent("status", "initialized")
                            } else {
                                sendEvent("status", "already_initialized")
                            }
                            result.success(null)
                        }

                        "stopVivoka" -> {
                            vm?.stopVivoka()
                            sendEvent("status", "stopped")
                            result.success(null)
                        }

                        "resumeVivoka" -> {
                            vm?.resumeVivoka()
                            sendEvent("status", "resumed")
                            result.success(null)
                        }

                        "release" -> {
                            vm?.commandCallback = null
                            vm?.release()
                            vm = null
                            sendEvent("status", "released")
                            result.success(null)
                        }

                        "setCallMode" -> {
                            val inCall = (call.arguments as? Boolean) ?: false
                            vm?.setCallMode(inCall)
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
                    Log.e("VivokaMainActivity", "Method failed: ${call.method}", t)
                    sendEvent("error", t.message ?: "failed")
                    result.error("METHOD_FAILED", t.message, null)
                }
            }
    }

    private fun toggleTorch(enabled: Boolean) {
        lightController.action(
            listOf(spLightController.Controllable.FRONT_TORCH),
            if (enabled) spLightController.Action.ON else spLightController.Action.OFF
        )
    }

    private fun sendEvent(type: String, text: String?) {
        eventSink?.success(hashMapOf("type" to type, "text" to text))
    }

}