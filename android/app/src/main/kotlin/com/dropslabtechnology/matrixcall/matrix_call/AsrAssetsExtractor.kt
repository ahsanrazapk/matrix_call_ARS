package com.dropslabtechnology.matrixcall.matrix_call

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vivoka.vsdk.Exception
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Copies Vivoka ASR model assets from the APK to the app's internal storage.
 *
 * Improvements over the original:
 *  - Skips extraction entirely if a stamp file exists (avoids re-copying on
 *    every cold start, which was a significant delay).
 *  - Uses a version string derived from the app's `versionCode` so assets are
 *    refreshed automatically after an APK update.
 *  - Calls the [callback] on the main thread whether extraction ran or was skipped.
 */
class AsrAssetsExtractor(
    context: Context,
    private val assetsPath: String,
    private val callback: IAssetsExtractorCallback
) {
    private val appContext: Context    = context.applicationContext
    private val assetManager: AssetManager = appContext.assets
    private val destPath: String       = if (assetsPath.endsWith("/")) assetsPath else "$assetsPath/"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AsrAssetsExtractor").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        executor.execute {
            try {
                if (isUpToDate()) {
                    Log.d(TAG, "Assets already extracted and up-to-date, skipping copy")
                } else {
                    Log.d(TAG, "Extracting ASR assets to $destPath")
                    copyFileOrDir("")
                    writeStamp()
                    Log.d(TAG, "Asset extraction complete")
                }
                mainHandler.post {
                    try { callback.onCompleted() } catch (_: Exception) {}
                }
            } catch (e: IOException) {
                Log.e(TAG, "Asset extraction failed: ${e.message}", e)
                // Still fire callback so the caller can handle gracefully
                mainHandler.post {
                    try { callback.onCompleted() } catch (_: Exception) {}
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    // ── stamp file ────────────────────────────────────────────────────────────

    private fun stampFile() = File(destPath, STAMP_FILENAME)

    private fun currentVersion(): String {
        return try {
            val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            @Suppress("DEPRECATION")
            pi.versionCode.toString()
        } catch (_: Exception) {
            "1"
        }
    }

    private fun isUpToDate(): Boolean {
        val stamp = stampFile()
        if (!stamp.exists()) return false
        return try { stamp.readText().trim() == currentVersion() } catch (_: Exception) { false }
    }

    private fun writeStamp() {
        try {
            stampFile().writeText(currentVersion())
        } catch (e: IOException) {
            Log.w(TAG, "Could not write stamp: ${e.message}")
        }
    }

    // ── recursive asset copy ──────────────────────────────────────────────────

    @Throws(IOException::class)
    private fun copyFileOrDir(path: String) {
        val assets = assetManager.list(path) ?: return

        if (assets.isEmpty()) {
            copyFile(path)
            return
        }

        val fullPath = destPath + path

        // Skip TTS (vocalizer) data directories
        if (fullPath.contains("languages") || fullPath.contains("common")) return

        val dir = File(fullPath)
        if (!dir.exists() && !path.startsWith("images") && !path.startsWith("webkit")) {
            if (!dir.mkdirs()) {
                Log.w(TAG, "Could not create directory: $fullPath")
            }
        }

        for (asset in assets) {
            val childPath = if (path.isEmpty()) asset else "$path/$asset"
            if (!path.startsWith("images") && !path.startsWith("webkit")) {
                copyFileOrDir(childPath)
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(filename: String) {
        // Apply the same content filters as before
        if (filename.contains("cache") && !filename.contains("liquid_config.json")) return
        if (filename.contains("config/") && !filename.contains("logger.json")) return
        if (filename.contains("data") &&
            (filename.contains("languages") || filename.contains("common"))) return

        var input: InputStream? = null
        var output: OutputStream? = null
        try {
            input = assetManager.open(filename)
            val outFile = File(destPath + filename)
            outFile.parentFile?.mkdirs()
            output = FileOutputStream(outFile)

            val buffer = ByteArray(8 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
            output.flush()
        } finally {
            try { input?.close()  } catch (_: IOException) {}
            try { output?.close() } catch (_: IOException) {}
        }
    }

    companion object {
        private const val TAG            = "AsrAssetsExtractor"
        private const val STAMP_FILENAME = ".vsdk_extracted"
    }
}
