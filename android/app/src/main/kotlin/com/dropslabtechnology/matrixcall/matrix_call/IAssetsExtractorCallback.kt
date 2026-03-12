package com.dropslabtechnology.matrixcall.matrix_call

import com.vivoka.vsdk.Exception

fun interface IAssetsExtractorCallback {
    @Throws(Exception::class)
    fun onCompleted()
}