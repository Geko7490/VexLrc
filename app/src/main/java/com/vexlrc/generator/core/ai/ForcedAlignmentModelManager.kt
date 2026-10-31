package com.vexlrc.generator.core.ai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AlignmentModelInfo(val id: String, val title: String, val approximateBytes: Long, val description: String)

class ForcedAlignmentModelManager(context: Context) {
    private val directory = File(context.filesDir, "models/aligner").apply { mkdirs() }
    val catalog = listOf(
        AlignmentModelInfo("tr", "Turkish CTC Aligner", 356L * 1024 * 1024, "Turkish transcript alignment • Parseh / XLS-R"),
        AlignmentModelInfo("en", "English CTC Aligner", 356L * 1024 * 1024, "English transcript alignment • Parseh / XLS-R")
    )
    private fun base(id: String) = "https://huggingface.co/parseh/aligner-$id/resolve/main/"
    fun modelFile(id: String): File? = catalog.firstOrNull { it.id == id }?.let { File(directory, "$id-model.int8.onnx") }
    fun vocabFile(id: String): File? = catalog.firstOrNull { it.id == id }?.let { File(directory, "$id-vocab.json") }
    fun isInstalled(id: String) = modelFile(id)?.let { it.exists() && it.length() > 100L * 1024 * 1024 } == true &&
        vocabFile(id)?.let { it.exists() && it.length() > 100L } == true
    fun installedIds() = catalog.filter { isInstalled(it.id) }.map { it.id }.toSet()
    fun delete(id: String) { modelFile(id)?.delete(); vocabFile(id)?.delete(); File(directory, "$id-model.download").delete() }

    suspend fun download(id: String, onProgress: suspend (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        require(catalog.any { it.id == id }) { "Unknown alignment language" }
        downloadOne(base(id) + "model.int8.onnx", modelFile(id)!!, 100L * 1024 * 1024, onProgress)
        downloadOne(base(id) + "vocab.json", vocabFile(id)!!, 100L, onProgress)
    }

    private suspend fun downloadOne(url: String, target: File, minimum: Long, onProgress: suspend (Long, Long) -> Unit) {
        if (target.exists() && target.length() > minimum) return
        val temp = File(target.parentFile, target.name + ".download")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 120000; instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("Model server returned HTTP " + connection.responseCode)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: minimum
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024); var countTotal = 0L; var last = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); countTotal += count
                        if (System.currentTimeMillis() - last > 400) { onProgress(countTotal, total); last = System.currentTimeMillis() }
                    }
                    output.flush(); onProgress(countTotal, total)
                }
            }
            if (temp.length() <= minimum) error("Downloaded model file is incomplete")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) error("Could not finalize downloaded model")
        } catch (e: Exception) { temp.delete(); throw e }
        finally { connection.disconnect() }
    }
}
