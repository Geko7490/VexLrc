package com.vexlrc.generator.core.project

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** App-private JSON storage. AtomicFile protects the previous valid save from interrupted writes. */
class ProjectStore(context: Context) {
    private val directory = File(context.filesDir, "projects").apply { mkdirs() }

    @Synchronized
    fun save(project: LrcProject) {
        val json = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("audioUri", project.audioUri)
            put("durationMs", project.durationMs)
            put("updatedAtMs", project.updatedAtMs)
            put("lyricsJson", project.lyricsJson)
        }
        val atomic = AtomicFile(projectFile(project.id))
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) atomic.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun list(): List<LrcProject> = directory.listFiles { file -> file.name.endsWith(".json") }
        ?.mapNotNull { file -> runCatching { decode(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrNull() }
        ?.sortedByDescending { it.updatedAtMs } ?: emptyList()

    @Synchronized
    fun load(id: String): LrcProject? = runCatching {
        val file = projectFile(id)
        if (!file.exists() && !File(file.path + ".bak").exists()) null
        else decode(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }.getOrNull()

    @Synchronized
    fun delete(id: String): Boolean {
        val file = projectFile(id)
        val existed = file.exists() || File(file.path + ".bak").exists()
        AtomicFile(file).delete()
        return existed
    }

    private fun projectFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid project id" }
        return File(directory, "$id.json")
    }

    private fun decode(raw: String): LrcProject {
        val json = JSONObject(raw)
        return LrcProject(
            id = json.getString("id"),
            name = json.optString("name", "Untitled"),
            audioUri = json.optString("audioUri").takeIf { it.isNotBlank() && it != "null" },
            durationMs = json.optLong("durationMs"),
            updatedAtMs = json.optLong("updatedAtMs"),
            lyricsJson = json.optString("lyricsJson", "[]")
        )
    }
}

/** Keeps a compact, bounded history of successful LRC exports, including their exact contents. */
data class ExportRecord(
    val id: String,
    val fileName: String,
    val enhanced: Boolean,
    val createdAtMs: Long,
    val content: String
)

class ExportHistoryStore(context: Context) {
    private val file = File(context.filesDir, "export_history.json")
    private val atomic = AtomicFile(file)

    @Synchronized
    fun list(): List<ExportRecord> = runCatching {
        val raw = atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ExportRecord(
                id = item.getString("id"),
                fileName = item.optString("fileName", "lyrics.lrc"),
                enhanced = item.optBoolean("enhanced"),
                createdAtMs = item.optLong("createdAtMs"),
                content = item.optString("content")
            )
        }.sortedByDescending { it.createdAtMs }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(record: ExportRecord) {
        val records = (list().filterNot { it.id == record.id } + record)
            .sortedByDescending { it.createdAtMs }.take(50)
        val array = JSONArray()
        records.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("fileName", item.fileName)
                put("enhanced", item.enhanced)
                put("createdAtMs", item.createdAtMs)
                put("content", item.content)
            })
        }
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) atomic.failWrite(stream)
            throw error
        }
    }
}
