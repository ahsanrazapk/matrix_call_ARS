package com.dropslabtechnology.matrixcall.matrix_call

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import com.vivoka.vsdk.Exception
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AsrAssetsExtractor(context: Context, assetsPath: String,
                         private val callback: IAssetsExtractorCallback
) {
    private val assetsPath: String = if (assetsPath.endsWith("/")) assetsPath else "$assetsPath/"
    private val assetManager: AssetManager = context.assets

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        executor.execute {
            try {
                copyFileOrDir("")
                mainHandler.post {
                    try {
                        callback.onCompleted()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: IOException) {
                Exception(e.message).printFormattedMessage()
            } finally {
                executor.shutdown()
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFileOrDir(path: String) {
        val assets = assetManager.list(path) ?: return

        if (assets.size == 0) {
            copyFile(path)
            return
        }

        val fullPath = assetsPath + path

        // Don't copy tts (vocalizer) data
        if (fullPath.contains("languages") || fullPath.contains("common")) {
            return
        }

        val dir = File(fullPath)
        if (!dir.exists() && !path.startsWith("images") && !path.startsWith("webkit")) {
            if (!dir.mkdirs()) {
                Exception("could not create dir: $fullPath").printFormattedMessage()
            }
        }

        for (asset in assets) {
            val p = if (path.isEmpty()) "" else "$path/"
            if (!path.startsWith("images") && !path.startsWith("webkit")) {
                copyFileOrDir(p + asset)
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(filename: String) {
        // Filters (same as your code)
        if (filename.contains("cache") && !filename.contains("liquid_config.json")) return
        if (filename.contains("config/") && !filename.contains("logger.json")) return
        if (filename.contains("data") && (filename.contains("languages") || filename.contains("common"))) return

        var `in`: InputStream? = null
        var out: OutputStream? = null

        try {
            `in` = assetManager.open(filename)

            val newFileName = assetsPath + filename
            val outFile = File(newFileName)
            val parent = outFile.getParentFile()
            if (parent != null && !parent.exists()) parent.mkdirs()

            out = FileOutputStream(outFile)

            val buffer = ByteArray(8 * 1024)
            var read: Int
            while ((`in`.read(buffer).also { read = it }) != -1) {
                out.write(buffer, 0, read)
            }
            out.flush()
        } finally {
            if (`in` != null) try {
                `in`.close()
            } catch (_: IOException) {
            }
            if (out != null) try {
                out.close()
            } catch (_: IOException) {
            }
        }
    }
}
