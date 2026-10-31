package com.vexlrc.generator.core.ai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WhisperModelInfo(
    val id: String,
    val title: String,
    val fileName: String,
    val approximateBytes: Long,
    val description: String
)

class WhisperModelManager(context: Context) {
    private val directory = File(context.filesDir, "models/whisper").apply { mkdirs() }

    val catalog = listOf(
        WhisperModelInfo("tiny", "Whisper Tiny", "ggml-tiny.bin", 75L * 1024 * 1024, "Fastest; good for testing"),
        WhisperModelInfo("base", "Whisper Base", "ggml-base.bin", 142L * 1024 * 1024, "Balanced multilingual model"),
        WhisperModelInfo("small", "Whisper Small", "ggml-small.bin", 466L * 1024 * 1024, "Higher quality; slower and memory-heavy")
    )

    fun fileFor(id: String): File? = catalog.firstOrNull { it.id == id }?.let { File(directory, it.fileName) }
    fun isInstalled(id: String): Boolean = fileFor(id)?.let { it.exists() && it.length() > 16L * 1024 * 1024 } == true
    fun installedIds(): Set<String> = catalog.filter { isInstalled(it.id) }.map { it.id }.toSet()

    fun delete(id: String): Boolean = fileFor(id)?.let { !it.exists() || it.delete() } ?: false

    suspend fun download(id: String, onProgress: suspend (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val model = catalog.firstOrNull { it.id == id } ?: error("Unknown Whisper model")
        val target = File(directory, model.fileName)
        if (target.exists() && target.length() > 16L * 1024 * 1024) return@withContext target
        val temp = File(directory, model.fileName + ".download")
        val connection = (URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${model.fileName}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("Model server returned HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: model.approximateBytes
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 350L) {
                            onProgress(copied, total)
                            lastUpdate = now
                        }
                    }
                    output.flush()
                    onProgress(copied, total)
                }
            }
            if (temp.length() < model.approximateBytes * 0.75) error("Downloaded file is smaller than expected; download was rejected")
            if (target.exists() && !target.delete()) error("Could not replace old model")
            if (!temp.renameTo(target)) error("Could not finalize model file")
            target
        } catch (error: Exception) {
            temp.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
