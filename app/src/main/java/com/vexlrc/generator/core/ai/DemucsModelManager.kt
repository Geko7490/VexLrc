package com.vexlrc.generator.core.ai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DemucsModelManager(context: Context) {
    private val directory = File(context.filesDir, "models/demucs").apply { mkdirs() }
    private val target = File(directory, "htdemucs_ft_vocals_fp16weights.onnx")
    fun modelFile(): File? = target.takeIf { it.exists() && it.length() > 120L * 1024 * 1024 }
    fun isInstalled() = modelFile() != null
    fun delete() { target.delete(); File(directory, target.name + ".download").delete() }

    suspend fun download(onProgress: suspend (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        modelFile()?.let { return@withContext it }
        val temp = File(directory, target.name + ".download")
        val connection = (URL("https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 120000; instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("Demucs server returned HTTP " + connection.responseCode)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 166L * 1024 * 1024
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024); var copied = 0L; var last = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); copied += n
                        if (System.currentTimeMillis() - last > 400) { onProgress(copied, total); last = System.currentTimeMillis() }
                    }
                    output.flush(); onProgress(copied, total)
                }
            }
            if (temp.length() < 120L * 1024 * 1024) error("Demucs model download is incomplete")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) error("Could not finalize Demucs model")
            target
        } catch (e: Exception) { temp.delete(); throw e }
        finally { connection.disconnect() }
    }
}
