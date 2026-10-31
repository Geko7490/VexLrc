package com.vexlrc.generator.core.project

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Small atomic JSON project store. Audio remains in its original user-selected location. */
class ProjectStore(context: Context) {
    private val directory = File(context.filesDir, "projects").apply { mkdirs() }

    fun save(project: LrcProject) {
        val json = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("audioUri", project.audioUri)
            put("durationMs", project.durationMs)
            put("updatedAtMs", project.updatedAtMs)
            put("lyricsJson", project.lyricsJson)
        }
        val target = projectFile(project.id)
        val temp = File(directory, "${project.id}.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (target.exists() && !target.delete()) error("Could not replace project")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun list(): List<LrcProject> = directory.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull() }
        ?.sortedByDescending { it.updatedAtMs } ?: emptyList()

    fun load(id: String): LrcProject? = runCatching {
        projectFile(id).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let(::decode)
    }.getOrNull()

    fun delete(id: String): Boolean = projectFile(id).delete()

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
