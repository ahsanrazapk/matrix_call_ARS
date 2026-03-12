package com.dropslabtechnology.matrixcall.matrix_call

interface CommandCallback {
    fun getCommandSlot(cmd: String?)
    fun getSpeechSlot(cmd: String?)
}
