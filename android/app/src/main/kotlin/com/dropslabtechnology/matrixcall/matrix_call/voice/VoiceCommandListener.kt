package com.dropslabtechnology.matrixcall.matrix_call.voice

/**
 * Callback interface for voice recognition results.
 * Implementations receive callbacks on the main thread.
 */
interface VoiceCommandListener {
    /** Called when a wake-word + command phrase is detected (grammar model). */
    fun onCommand(command: String)

    /** Called when a free-speech utterance is recognised (free model). */
    fun onSpeech(text: String)

    /** Called when the SDK status changes (for diagnostics / UI). */
    fun onStatusChanged(status: String)

    /** Called when an unrecoverable error occurs. */
    fun onError(message: String)
}
