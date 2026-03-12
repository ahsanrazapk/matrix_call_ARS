package com.dropslabtechnology.matrixcall.matrix_call

import android.app.Application
import android.util.Log
import com.dropslabtechnology.matrixcall.matrix_call.voice.VoiceManager

/**
 * Custom Application class.
 *
 * Responsibilities:
 *  - Obtain the [VoiceManager] singleton early so it is ready before any
 *    Activity creates its Flutter engine.
 *
 * NOTE: We do NOT call [VoiceManager.init] here because we don't yet hold
 * the RECORD_AUDIO runtime permission.  The permission check happens in
 * [MainActivity]; once granted it calls [VoiceManager.init].
 */
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created — VoiceManager singleton pre-warmed")
        // Pre-warm the singleton so the Application context is captured correctly
        // before any Activity context is passed.
        VoiceManager.getInstance(this)
    }

    companion object {
        private const val TAG = "MyApplication"
    }
}
