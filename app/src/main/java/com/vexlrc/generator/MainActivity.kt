package com.vexlrc.generator

import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vexlrc.generator.core.lrc.LrcFormatter
import com.vexlrc.generator.core.lrc.LrcValidator
import com.vexlrc.generator.core.ai.AudioPcmDecoder
import com.vexlrc.generator.core.ai.WhisperModelManager
import com.vexlrc.generator.core.ai.WhisperNative
import com.vexlrc.generator.core.ai.WhisperTokenTiming
import com.vexlrc.generator.core.ai.ForcedAlignmentModelManager
import com.vexlrc.generator.core.ai.ForcedAlignmentEngine
import com.vexlrc.generator.core.ai.DemucsModelManager
import com.vexlrc.generator.core.ai.DemucsVocalSeparator
import com.vexlrc.generator.core.project.ExportHistoryStore
import com.vexlrc.generator.core.project.ExportRecord
import com.vexlrc.generator.core.project.LrcProject
import com.vexlrc.generator.core.project.ProjectStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File
import com.vexlrc.generator.core.lrc.LyricLine
import com.vexlrc.generator.core.lrc.LyricWord
import com.vexlrc.generator.core.waveform.WaveformAudioDecoder
import com.vexlrc.generator.core.waveform.WaveformEditor
import com.vexlrc.generator.core.waveform.WaveformMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Purple = Color(0xFFA855F7)
private val Bg = Color(0xFF0F0F13)
private val SurfaceDark = Color(0xFF1C1B23)
private val Muted = Color(0xFFAAA5B4)
private data class WordEdit(val text: String, val timeMs: Long)
private data class LineEdit(val id: Int, val timeMs: Long, val text: String, val words: List<WordEdit> = emptyList(), val wordsOpen: Boolean = false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VexLrcApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VexLrcApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf("Home") }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf("No audio selected") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var waveform by remember { mutableStateOf<List<Float>>(emptyList()) }
    var waveformLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var enhanced by remember { mutableStateOf(false) }
    var nextId by remember { mutableIntStateOf(2) }
    var lines by remember { mutableStateOf(listOf(LineEdit(1, 0L, "Start writing your lyrics here"))) }
    val projectStore = remember { ProjectStore(context.applicationContext) }
    val exportStore = remember { ExportHistoryStore(context.applicationContext) }
    val whisperModels = remember { WhisperModelManager(context.applicationContext) }
    val alignerModels = remember { ForcedAlignmentModelManager(context.applicationContext) }
    val demucsModels = remember { DemucsModelManager(context.applicationContext) }
    val demucsSeparator = remember { DemucsVocalSeparator(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var installedModels by remember { mutableStateOf(whisperModels.installedIds()) }
    var installedAligners by remember { mutableStateOf(alignerModels.installedIds()) }
    var demucsInstalled by remember { mutableStateOf(demucsModels.isInstalled()) }
    var aiAudioUri by remember { mutableStateOf<Uri?>(null) }
    var aiAudioFile by remember { mutableStateOf<File?>(null) }
    var selectedWhisperModel by remember { mutableStateOf("tiny") }
    var selectedWhisperLanguage by remember { mutableStateOf("auto") }
    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var modelDownloadProgress by remember { mutableFloatStateOf(0f) }
    var aiBusy by remember { mutableStateOf(false) }
    var currentProjectId by remember { mutableStateOf<String?>(null) }
    var projectName by remember { mutableStateOf("Untitled project") }
    var projects by remember { mutableStateOf(emptyList<LrcProject>()) }
    var exportHistory by remember { mutableStateOf(emptyList<ExportRecord>()) }
    var pendingExportContent by remember { mutableStateOf("") }
    var pendingExportName by remember { mutableStateOf("lyrics.lrc") }
    var pendingExportEnhanced by remember { mutableStateOf(false) }
    var renameProjectId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            player?.release()
            player = null
            audioUri = uri
            aiAudioUri = null
            aiAudioFile = null
            audioName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
            playing = false
            position = 0L
            duration = 0L
            status = "New project created"
            projectName = audioName.substringBeforeLast('.', audioName).ifBlank { "Untitled project" }
            currentProjectId = UUID.randomUUID().toString()
            lines = listOf(LineEdit(1, 0L, ""))
            nextId = 2
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            runCatching {
                val stream = context.contentResolver.openOutputStream(uri) ?: error("Cannot open output")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(pendingExportContent) }
                exportStore.add(ExportRecord(UUID.randomUUID().toString(), pendingExportName, pendingExportEnhanced, System.currentTimeMillis(), pendingExportContent))
                exportHistory = exportStore.list()
            }.onSuccess { status = if (pendingExportEnhanced) "Enhanced LRC exported and saved to history" else "Standard LRC exported and saved to history" }
                .onFailure { status = "Export failed: " + (it.localizedMessage ?: "unknown error") }
        }
    }

    LaunchedEffect(Unit) {
        projects = withContext(Dispatchers.IO) { projectStore.list() }
        exportHistory = withContext(Dispatchers.IO) { exportStore.list() }
        installedModels = withContext(Dispatchers.IO) { whisperModels.installedIds() }
        installedAligners = withContext(Dispatchers.IO) { alignerModels.installedIds() }
        demucsInstalled = withContext(Dispatchers.IO) { demucsModels.isInstalled() }
        val recent = projects.firstOrNull()
        if (recent != null) {
            currentProjectId = recent.id
            projectName = recent.name
            audioUri = recent.audioUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            audioName = recent.audioUri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast('/') } ?: "Audio unavailable"
            duration = recent.durationMs
            lines = decodeLines(recent.lyricsJson)
            nextId = (lines.maxOfOrNull { it.id } ?: 0) + 1
            status = "Restored your last project"
        }
    }

    LaunchedEffect(audioUri, lines, duration, projectName, currentProjectId) {
        val id = currentProjectId ?: return@LaunchedEffect
        val uri = audioUri ?: return@LaunchedEffect
        delay(650L)
        val project = LrcProject(id, projectName.ifBlank { "Untitled project" }, uri.toString(), duration, System.currentTimeMillis(), encodeLines(lines))
        runCatching {
            withContext(Dispatchers.IO) { projectStore.save(project) }
            projects = withContext(Dispatchers.IO) { projectStore.list() }
            status = "Project saved"
        }.onFailure { status = "Project save failed: " + (it.localizedMessage ?: "storage error") }
    }

    LaunchedEffect(audioUri) {
        val selectedUri = audioUri
        waveform = emptyList()
        if (selectedUri != null) {
            waveformLoading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    val audioDuration = try {
                        retriever.setDataSource(context, selectedUri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        runCatching { retriever.release() }
                    }
                    val peaks = WaveformAudioDecoder.decode(context.applicationContext, selectedUri, 1400)
                    audioDuration to peaks
                }
                if (result.first > 0L) duration = result.first
                waveform = result.second
                status = if (waveform.isNotEmpty()) "Waveform ready" else "Audio loaded; waveform is empty"
            } catch (error: Exception) {
                waveform = emptyList()
                status = "Waveform unavailable: " + (error.localizedMessage ?: "decoder error")
            } finally {
                waveformLoading = false
            }
        }
    }

    LaunchedEffect(player, playing) {
        while (playing && player != null) {
            runCatching {
                position = player!!.currentPosition.toLong()
                duration = player!!.duration.toLong().coerceAtLeast(0L)
            }
            delay(100L)
        }
    }
    DisposableEffect(Unit) {
        onDispose { player?.release(); player = null }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Bg, surface = SurfaceDark, onBackground = Color.White, onSurface = Color.White)) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                listOf("Home", "Studio", "Models", "Settings").forEach { item ->
                    val icon = when (item) {
                        "Home" -> Icons.Default.Home
                        "Studio" -> Icons.Default.LibraryMusic
                        "Models" -> Icons.Default.Download
                        else -> Icons.Default.Settings
                    }
                    NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(icon, contentDescription = item) }, label = { Text(item) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(42.dp).background(Purple, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null) }
                    Column { Text("VEX LRC", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold); Text("GENERATOR • LOCAL AI", color = Muted, fontSize = 10.sp) }
                }
                when (tab) {
                    "Home" -> {
                        Text("Turn any song into synchronized lyrics.", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Create standard and word-level Enhanced LRC files on your device.", color = Muted)
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.AudioFile, null, tint = Purple)
                                Text("Start a new project", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(audioName, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Button(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose audio file") }
                                OutlinedButton(onClick = { tab = "Studio" }, modifier = Modifier.fillMaxWidth()) { Text("Open LRC Studio") }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { projects = projectStore.list(); tab = "Projects" }, modifier = Modifier.weight(1f)) { Text("Projects") }
                                    OutlinedButton(onClick = { exportHistory = exportStore.list(); tab = "History" }, modifier = Modifier.weight(1f)) { Text("Export history") }
                                }
                            }
                        }
                        StatusCard(status)
                        Text("Whisper models can be downloaded in Model Center for offline Turkish and English transcription.", color = Muted, fontSize = 12.sp)
                    }
                    "Studio" -> {
                        Text("LRC Studio", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text(projectName + " • " + audioName, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (currentProjectId == null) "No active project" else "Auto-save enabled", color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { projects = projectStore.list(); tab = "Projects" }) { Text("Project library") }
                        }
                        Surface(color = SurfaceDark, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) { Text("Load audio") }
                                    OutlinedButton(onClick = {
                                        val uri = audioUri
                                        if (uri == null) status = "Choose an audio file first"
                                        else runCatching {
                                            if (playing) { player?.pause(); playing = false }
                                            else {
                                                if (player == null) {
                                                    player = MediaPlayer().apply {
                                                        setDataSource(context, uri)
                                                        setOnCompletionListener { playing = false; position = 0L }
                                                        prepare()
                                                    }
                                                    duration = player?.duration?.toLong() ?: 0L
                                                }
                                                if ((player?.currentPosition ?: 0) >= (player?.duration ?: 0)) player?.seekTo(0)
                                                player?.start()
                                                playing = true
                                            }
                                        }.onFailure { status = "Playback error: " + (it.localizedMessage ?: "unknown error") }
                                    }, modifier = Modifier.weight(1f)) {
                                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                                        Text(if (playing) "Pause" else "Play")
                                    }
                                }
                                Text(clock(position) + " / " + clock(duration), color = Muted, fontFamily = FontFamily.Monospace)
                                Slider(
                                    value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                                    onValueChange = { position = it.toLong() },
                                    onValueChangeFinished = { player?.seekTo(position.toInt()) },
                                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                                    enabled = player != null
                                )
                                if (waveformLoading) {
                                    Text("Analyzing audio waveform…", color = Muted, fontSize = 12.sp)
                                } else if (waveform.isNotEmpty() && duration > 0L) {
                                    WaveformEditor(
                                        peaks = waveform,
                                        durationMs = duration,
                                        playheadMs = position,
                                        markers = lines.map { WaveformMarker(it.id, it.timeMs) },
                                        onSeek = { time ->
                                            position = time
                                            player?.seekTo(time.toInt())
                                        },
                                        onMoveMarker = { markerId, time ->
                                            lines = lines.map { line -> if (line.id == markerId) line.copy(timeMs = time) else line }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { player?.let { it.seekTo((it.currentPosition - 5000).coerceAtLeast(0)); position = it.currentPosition.toLong() } }, modifier = Modifier.weight(1f)) { Text("−5 sec") }
                                    OutlinedButton(onClick = { player?.let { it.seekTo((it.currentPosition + 5000).coerceAtMost(it.duration)); position = it.currentPosition.toLong() } }, modifier = Modifier.weight(1f)) { Text("+5 sec") }
                                }
                                Divider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Lyrics", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text(lines.size.toString() + " lines", color = Muted, fontSize = 12.sp)
                                }
                                Button(onClick = {
                                    val now = player?.currentPosition?.toLong() ?: position
                                    lines = lines + LineEdit(nextId++, now, "")
                                }, modifier = Modifier.fillMaxWidth()) { Text("+ Add line at current time") }
                                lines.forEachIndexed { index, line ->
                                    LineCard(
                                        line = line, index = index,
                                        onText = { value -> lines = lines.toMutableList().also { it[index] = it[index].copy(text = value, words = emptyList()) } },
                                        onCapture = { lines = lines.toMutableList().also { it[index] = it[index].copy(timeMs = player?.currentPosition?.toLong() ?: position) } },
                                        onNudge = { delta -> lines = lines.toMutableList().also { it[index] = it[index].copy(timeMs = (it[index].timeMs + delta).coerceAtLeast(0L)) } },
                                        onDelete = { lines = lines.toMutableList().also { it.removeAt(index) } },
                                        onToggle = {
                                            val current = lines[index]
                                            val words = if (current.words.isNotEmpty()) current.words else current.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { WordEdit(it, current.timeMs) }
                                            lines = lines.toMutableList().also { it[index] = current.copy(words = words, wordsOpen = !current.wordsOpen) }
                                        },
                                        onWordText = { wi, value ->
                                            val words = line.words.toMutableList()
                                            words[wi] = words[wi].copy(text = value)
                                            lines = lines.toMutableList().also { it[index] = line.copy(words = words) }
                                        },
                                        onWordCapture = { wi ->
                                            val words = line.words.toMutableList()
                                            words[wi] = words[wi].copy(timeMs = player?.currentPosition?.toLong() ?: position)
                                            lines = lines.toMutableList().also { it[index] = line.copy(words = words) }
                                        },
                                        onAddWord = {
                                            lines = lines.toMutableList().also { it[index] = line.copy(words = line.words + WordEdit("", player?.currentPosition?.toLong() ?: position), wordsOpen = true) }
                                        },
                                        onDeleteWord = { wi ->
                                            lines = lines.toMutableList().also { it[index] = line.copy(words = line.words.filterIndexed { i, _ -> i != wi }) }
                                        }
                                    )
                                }
                                Divider()
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF211B2B))) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("LOCAL AI TRANSCRIPTION", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("Whisper.cpp • Turkish / English • offline after model download", color = Muted, fontSize = 12.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("auto" to "Auto", "tr" to "Türkçe", "en" to "English").forEach { (code, label) ->
                                                FilterChip(selected = selectedWhisperLanguage == code, onClick = { selectedWhisperLanguage = code }, label = { Text(label) })
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                val selectedUri = aiAudioUri ?: audioUri
                                                val modelFile = whisperModels.fileFor(selectedWhisperModel)
                                                if (selectedUri == null) {
                                                    status = "Choose an audio file first"
                                                } else if (modelFile == null || !whisperModels.isInstalled(selectedWhisperModel)) {
                                                    status = "Download a Whisper model in Model Center first"
                                                    tab = "Models"
                                                } else {
                                                    coroutineScope.launch {
                                                        aiBusy = true
                                                        status = "Decoding audio for Whisper…"
                                                        try {
                                                            val pcm = withContext(Dispatchers.IO) { AudioPcmDecoder.decode(context.applicationContext, selectedUri) }
                                                            status = "Whisper is transcribing…"
                                                            val raw = withContext(Dispatchers.Default) {
                                                                WhisperNative.transcribe(
                                                                    modelFile.absolutePath,
                                                                    pcm,
                                                                    selectedWhisperLanguage,
                                                                    Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                                                                )
                                                            }
                                                            val result = WhisperNative.parseResult(raw)
                                                            if (result.segments.isEmpty()) error("Whisper returned no speech segments")
                                                            val firstId = nextId
                                                            val generated = result.segments.mapIndexed { index, segment ->
                                                                LineEdit(firstId + index, segment.startMs, segment.text.trim(), tokenTimingsToWords(segment.tokens))
                                                            }
                                                            lines = generated
                                                            nextId = firstId + generated.size
                                                            status = "Transcription complete • ${generated.size} segments"
                                                        } catch (error: Exception) {
                                                            status = "Whisper failed: " + (error.localizedMessage ?: "unknown error")
                                                        } finally {
                                                            aiBusy = false
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !aiBusy,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (aiBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            else Icon(Icons.Default.AutoAwesome, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (aiBusy) "Processing audio…" else "Transcribe with Whisper")
                                        }
                                        Text("Whisper token timestamps are experimental. For accurate word-level timing, run the transcript-conditioned aligner below.", color = Muted, fontSize = 10.sp)
                                        Divider()
                                        Text("VOCAL SEPARATION", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("Demucs HTDemucs FT • local ONNX inference • 166 MB model", color = Muted, fontSize = 11.sp)
                                        Button(
                                            onClick = {
                                                val source = audioUri
                                                val model = demucsModels.modelFile()
                                                if (source == null) status = "Choose an audio file first"
                                                else if (model == null) { status = "Download the Demucs model in Model Center"; tab = "Models" }
                                                else coroutineScope.launch {
                                                    aiBusy = true
                                                    try {
                                                        status = "Decoding audio for vocal separation…"
                                                        val pcm = withContext(Dispatchers.IO) { AudioPcmDecoder.decode(context.applicationContext, source) }
                                                        status = "Demucs is separating vocals…"
                                                        val file = withContext(Dispatchers.Default) { demucsSeparator.separate(pcm, model) }
                                                        aiAudioFile = file
                                                        aiAudioUri = Uri.fromFile(file)
                                                        status = "Vocal stem ready. Whisper will use it for transcription."
                                                    } catch (e: Exception) { status = "Demucs failed: " + (e.localizedMessage ?: "unknown error") }
                                                    finally { aiBusy = false }
                                                }
                                            },
                                            enabled = !aiBusy && demucsInstalled,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(if (aiAudioUri != null) "Vocal stem ready • separate again" else "Separate vocals with Demucs") }
                                        if (aiAudioUri != null) {
                                            TextButton(onClick = { aiAudioUri = null; aiAudioFile = null }) { Text("Use original audio") }
                                        }
                                        Divider()
                                        Text("FORCED ALIGNMENT", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("Align the current lyric transcript to the selected audio. Choose Turkish or English above.", color = Muted, fontSize = 11.sp)
                                        Button(
                                            onClick = {
                                                val source = aiAudioUri ?: audioUri
                                                val language = if (selectedWhisperLanguage == "en") "en" else "tr"
                                                val model = alignerModels.modelFile(language)
                                                val vocab = alignerModels.vocabFile(language)
                                                if (source == null) status = "Choose an audio file first"
                                                else if (lines.none { it.text.isNotBlank() }) status = "Enter or transcribe lyrics first"
                                                else if (model == null || vocab == null || language !in installedAligners) { status = "Download the ${language.uppercase()} aligner in Model Center"; tab = "Models" }
                                                else coroutineScope.launch {
                                                    aiBusy = true
                                                    try {
                                                        status = "Preparing audio for forced alignment…"
                                                        val pcm = withContext(Dispatchers.IO) { AudioPcmDecoder.decode(context.applicationContext, source) }
                                                        val transcript = lines.joinToString(" ") { it.text.trim() }.trim()
                                                        status = "Running CTC Viterbi alignment…"
                                                        val words = withContext(Dispatchers.Default) { ForcedAlignmentEngine.align(context.applicationContext, model, vocab, pcm, transcript) }
                                                        var cursor = 0
                                                        lines = lines.map { line ->
                                                            val count = line.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                                                            val aligned = words.drop(cursor).take(count)
                                                            cursor += count
                                                            line.copy(words = aligned.map { WordEdit(it.text, it.startMs) })
                                                        }
                                                        status = "Forced alignment complete • ${words.size} words. Review before export."
                                                    } catch (e: Exception) { status = "Alignment failed: " + (e.localizedMessage ?: "unknown error") }
                                                    finally { aiBusy = false }
                                                }
                                            },
                                            enabled = !aiBusy && (selectedWhisperLanguage == "tr" || selectedWhisperLanguage == "en"),
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(if (aiBusy) "Processing…" else "Align current lyrics") }
                                    }
                                }
                                val issues = LrcValidator.validate(toLyricModels(lines), duration.takeIf { it > 0L })
                                Text("Timestamp validation", fontWeight = FontWeight.Bold)
                                Surface(color = if (issues.isEmpty()) Color(0xFF17251F) else Color(0xFF30231B), shape = RoundedCornerShape(10.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(if (issues.isEmpty()) "No timestamp issues found" else "${issues.size} issue(s) to review", color = if (issues.isEmpty()) Color(0xFF8DE0B0) else Color(0xFFFFC078), fontWeight = FontWeight.SemiBold)
                                        issues.take(5).forEach { Text("Line ${it.lineIndex + 1}: ${it.message}", color = Muted, fontSize = 11.sp) }
                                        if (issues.size > 5) Text("and ${issues.size - 5} more…", color = Muted, fontSize = 11.sp)
                                    }
                                }
                                Text("Export format", fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = !enhanced, onClick = { enhanced = false })
                                    Text("Standard")
                                    Spacer(Modifier.width(12.dp))
                                    RadioButton(selected = enhanced, onClick = { enhanced = true })
                                    Text("Enhanced")
                                }
                                Button(onClick = {
                                    pendingExportContent = makeLrc(lines, enhanced)
                                    pendingExportEnhanced = enhanced
                                    pendingExportName = if (enhanced) "lyrics.enhanced.lrc" else "lyrics.lrc"
                                    exporter.launch(pendingExportName)
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (enhanced) "Export Enhanced LRC" else "Export Standard LRC")
                                }
                            }
                        }
                        StatusCard(status)
                    }
                    "Projects" -> {
                        Text("Project Library", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Projects are stored privately on this device.", color = Muted)
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Create project from audio") }
                        if (projects.isEmpty()) StatusCard("No saved projects yet.")
                        projects.forEach { project ->
                            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(project.name, fontWeight = FontWeight.Bold)
                                    Text("Duration: ${project.durationMs / 1000}s • ${decodeLines(project.lyricsJson).size} lyric lines", color = Muted, fontSize = 11.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(onClick = {
                                            runCatching {
                                                val saved = projectStore.load(project.id) ?: error("Project not found")
                                                currentProjectId = saved.id
                                                projectName = saved.name
                                                audioUri = saved.audioUri?.let(Uri::parse)
                                                audioName = saved.audioUri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast('/') } ?: "Audio unavailable"
                                                duration = saved.durationMs
                                                lines = decodeLines(saved.lyricsJson)
                                                nextId = (lines.maxOfOrNull { it.id } ?: 0) + 1
                                                tab = "Studio"
                                                status = "Project opened"
                                            }.onFailure { status = "Open failed: " + (it.localizedMessage ?: "error") }
                                        }, modifier = Modifier.weight(1f)) { Text("Open") }
                                        OutlinedButton(onClick = { renameProjectId = project.id; renameValue = project.name }) { Text("Rename") }
                                        OutlinedButton(onClick = {
                                            projectStore.delete(project.id)
                                            if (currentProjectId == project.id) currentProjectId = null
                                            projects = projectStore.list()
                                            status = "Project deleted"
                                        }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                    "History" -> {
                        Text("Export History", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("The last 50 successful exports are kept on this device.", color = Muted)
                        if (exportHistory.isEmpty()) StatusCard("No exports yet.")
                        exportHistory.forEach { record ->
                            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(record.fileName, fontWeight = FontWeight.Bold)
                                    Text((if (record.enhanced) "Enhanced LRC" else "Standard LRC") + " • " + record.content.length + " characters", color = Muted, fontSize = 11.sp)
                                    OutlinedButton(onClick = {
                                        pendingExportContent = record.content
                                        pendingExportEnhanced = record.enhanced
                                        pendingExportName = record.fileName
                                        exporter.launch(record.fileName)
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Export again") }
                                }
                            }
                        }
                    }
                    "Models" -> {
                        Text("Model Center", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Download models once, then run supported inference locally without sending audio to a server.", color = Muted)
                        Text("WHISPER • SPEECH TO TEXT", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        whisperModels.catalog.forEach { model ->
                            val installed = model.id in installedModels
                            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(model.title, fontWeight = FontWeight.Bold)
                                            Text(model.description, color = Muted, fontSize = 11.sp)
                                            Text("About ${model.approximateBytes / (1024 * 1024)} MB", color = Muted, fontSize = 10.sp)
                                        }
                                        if (installed) Text("INSTALLED", color = Color(0xFF8DE0B0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (downloadingModelId == model.id) {
                                        LinearProgressIndicator(progress = { modelDownloadProgress }, modifier = Modifier.fillMaxWidth())
                                        Text("Downloading… ${(modelDownloadProgress * 100).toInt()}%", color = Muted, fontSize = 11.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (installed) {
                                            OutlinedButton(onClick = { selectedWhisperModel = model.id }, modifier = Modifier.weight(1f)) {
                                                Text(if (selectedWhisperModel == model.id) "Selected" else "Use this model")
                                            }
                                            TextButton(onClick = {
                                                whisperModels.delete(model.id)
                                                installedModels = whisperModels.installedIds()
                                                if (selectedWhisperModel == model.id) selectedWhisperModel = "tiny"
                                                status = "${model.title} deleted"
                                            }) { Text("Delete") }
                                        } else {
                                            Button(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        downloadingModelId = model.id
                                                        modelDownloadProgress = 0f
                                                        status = "Downloading ${model.title}…"
                                                        try {
                                                            whisperModels.download(model.id) { downloaded, total ->
                                                                withContext(Dispatchers.Main) {
                                                                    modelDownloadProgress = (downloaded.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                                                }
                                                            }
                                                            installedModels = whisperModels.installedIds()
                                                            selectedWhisperModel = model.id
                                                            status = "${model.title} downloaded and ready"
                                                        } catch (error: Exception) {
                                                            status = "Model download failed: " + (error.localizedMessage ?: "network error")
                                                        } finally {
                                                            downloadingModelId = null
                                                        }
                                                    }
                                                },
                                                enabled = downloadingModelId == null,
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("Download model") }
                                        }
                                    }
                                }
                            }
                        }
                        Divider()
                        Text("FORCED ALIGNMENT MODELS", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Transcript-conditioned CTC/Viterbi models • Turkish and English", color = Muted, fontSize = 11.sp)
                        alignerModels.catalog.forEach { model ->
                            val installed = model.id in installedAligners
                            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(model.title, fontWeight = FontWeight.Bold)
                                    Text(model.description, color = Muted, fontSize = 11.sp)
                                    Text(if (installed) "INSTALLED" else "About 356 MB", color = if (installed) Color(0xFF8DE0B0) else Muted, fontSize = 10.sp)
                                    if (installed) OutlinedButton(onClick = {
                                        alignerModels.delete(model.id)
                                        installedAligners = alignerModels.installedIds()
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Delete model") }
                                    else Button(onClick = {
                                        coroutineScope.launch {
                                            downloadingModelId = "align-${model.id}"
                                            modelDownloadProgress = 0f
                                            try {
                                                alignerModels.download(model.id) { done, total ->
                                                    withContext(Dispatchers.Main) { modelDownloadProgress = (done.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f) }
                                                }
                                                installedAligners = alignerModels.installedIds()
                                                status = "${model.title} installed"
                                            } catch (e: Exception) { status = "Aligner download failed: " + (e.localizedMessage ?: "error") }
                                            finally { downloadingModelId = null }
                                        }
                                    }, enabled = downloadingModelId == null, modifier = Modifier.fillMaxWidth()) { Text("Download aligner") }
                                }
                            }
                        }
                        Divider()
                        Text("VOCAL SEPARATION", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("Demucs HTDemucs FT Vocals", fontWeight = FontWeight.Bold)
                                Text("166 MB ONNX model. Produces a vocal stem for transcription; current input pipeline downmixes to mono before inference.", color = Muted, fontSize = 11.sp)
                                Text(if (demucsInstalled) "INSTALLED" else "About 166 MB", color = if (demucsInstalled) Color(0xFF8DE0B0) else Muted, fontSize = 10.sp)
                                if (demucsInstalled) OutlinedButton(onClick = {
                                    demucsModels.delete()
                                    demucsInstalled = false
                                    aiAudioUri = null
                                    aiAudioFile = null
                                }, modifier = Modifier.fillMaxWidth()) { Text("Delete model") }
                                else Button(onClick = {
                                    coroutineScope.launch {
                                        downloadingModelId = "demucs"
                                        modelDownloadProgress = 0f
                                        try {
                                            demucsModels.download { done, total ->
                                                withContext(Dispatchers.Main) { modelDownloadProgress = (done.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f) }
                                            }
                                            demucsInstalled = true
                                            status = "Demucs model installed"
                                        } catch (e: Exception) { status = "Demucs download failed: " + (e.localizedMessage ?: "error") }
                                        finally { downloadingModelId = null }
                                    }
                                }, enabled = downloadingModelId == null, modifier = Modifier.fillMaxWidth()) { Text("Download Demucs model") }
                            }
                        }
                        Divider()
                        Text("WORD TIMING", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        ModelCard("Whisper token timestamps", "Experimental token-level timestamps are included with transcription. Review and correct them in Studio.", "Available with Whisper • experimental")
                        ModelCard("Dedicated Forced Alignment", "A separate transcript-conditioned aligner is not bundled yet. Whisper token timing must not be treated as guaranteed forced alignment.", "Not integrated")
                        Divider()
                        Text("VOCAL SEPARATION", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        ModelCard("Demucs", "ONNX vocal-stem extraction with chunked overlap-add is integrated. Output can be used as Whisper input; test performance on device.", "Integrated • device test needed")
                        StatusCard(status)
                    }
                    else -> {
                        Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        ModelCard("Privacy", "Audio processing stays on device", "Offline-first")
                        ModelCard("App version", "VEX LRC Generator", "1.1.0 • Editor upgrade")
                    }
                }
            }
        }
        if (renameProjectId != null) {
            AlertDialog(
                onDismissRequest = { renameProjectId = null },
                title = { Text("Rename project") },
                text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, singleLine = true, label = { Text("Project name") }) },
                confirmButton = {
                    TextButton(onClick = {
                        val id = renameProjectId
                        if (id != null && renameValue.isNotBlank()) {
                            val saved = projectStore.load(id)
                            if (saved != null) {
                                projectStore.save(saved.copy(name = renameValue.trim(), updatedAtMs = System.currentTimeMillis()))
                                if (currentProjectId == id) projectName = renameValue.trim()
                                projects = projectStore.list()
                            }
                        }
                        renameProjectId = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renameProjectId = null }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun LineCard(
    line: LineEdit, index: Int, onText: (String) -> Unit, onCapture: () -> Unit,
    onNudge: (Long) -> Unit, onDelete: () -> Unit, onToggle: () -> Unit,
    onWordText: (Int, String) -> Unit, onWordCapture: (Int) -> Unit,
    onAddWord: () -> Unit, onDeleteWord: (Int) -> Unit
) {
    Surface(color = Color(0xFF282431), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LINE " + (index + 1), color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(clock(line.timeMs), fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDelete) { Text("✕", color = Color(0xFFFF7777)) }
            }
            OutlinedTextField(value = line.text, onValueChange = onText, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Enter lyric line") })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onCapture, modifier = Modifier.weight(1f)) { Text("Set to playhead", fontSize = 11.sp) }
                OutlinedButton(onClick = { onNudge(-100L) }) { Text("−0.1s") }
                OutlinedButton(onClick = { onNudge(100L) }) { Text("+0.1s") }
            }
            TextButton(onClick = onToggle) { Text(if (line.wordsOpen) "Hide word timing" else "Edit word timing") }
            if (line.wordsOpen) {
                line.words.forEachIndexed { wi, word ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(value = word.text, onValueChange = { onWordText(wi, it) }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Word " + (wi + 1)) })
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(clock(word.timeMs), color = Muted, fontSize = 10.sp)
                            TextButton(onClick = { onWordCapture(wi) }, contentPadding = PaddingValues(2.dp)) { Text("Set now", fontSize = 10.sp) }
                        }
                        IconButton(onClick = { onDeleteWord(wi) }) { Text("−") }
                    }
                }
                OutlinedButton(onClick = onAddWord, modifier = Modifier.fillMaxWidth()) { Text("+ Add word") }
                Text("Play the song and tap Set now for each word.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

private fun toLyricModels(lines: List<LineEdit>): List<LyricLine> = lines.map { line ->
    val words = if (line.words.isNotEmpty()) line.words.map { LyricWord(it.text, it.timeMs) }
    else line.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { LyricWord(it, line.timeMs) }
    LyricLine(line.timeMs, words)
}

private fun makeLrc(lines: List<LineEdit>, enhanced: Boolean): String {
    val models = toLyricModels(lines)
    return if (enhanced) LrcFormatter.enhanced(models) else LrcFormatter.standard(models)
}

private fun encodeLines(lines: List<LineEdit>): String {
    val array = JSONArray()
    lines.forEach { line ->
        val words = JSONArray()
        line.words.forEach { word -> words.put(JSONObject().put("text", word.text).put("timeMs", word.timeMs)) }
        array.put(JSONObject().put("id", line.id).put("timeMs", line.timeMs).put("text", line.text).put("words", words))
    }
    return array.toString()
}

private fun tokenTimingsToWords(tokens: List<WhisperTokenTiming>): List<WordEdit> {
    val result = mutableListOf<WordEdit>()
    val current = StringBuilder()
    var startMs = 0L
    var endMs = 0L
    fun flush() {
        val word = current.toString().trim()
        if (word.isNotEmpty()) result += WordEdit(word, startMs)
        current.clear()
    }
    tokens.forEach { token ->
        val value = token.text.replace("▁", " ")
        val parts = Regex("(?<=\\s)|(?=\\s)").split(value)
        parts.forEach { part ->
            if (part.isBlank()) {
                flush()
            } else {
                if (current.isEmpty()) startMs = token.startMs
                current.append(part)
                endMs = maxOf(endMs, token.endMs)
            }
        }
    }
    flush()
    return result
}

private fun decodeLines(raw: String): List<LineEdit> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val wordArray = item.optJSONArray("words") ?: JSONArray()
        val words = (0 until wordArray.length()).map { wi ->
            val word = wordArray.getJSONObject(wi)
            WordEdit(word.optString("text"), word.optLong("timeMs"))
        }
        LineEdit(item.optInt("id", index + 1), item.optLong("timeMs"), item.optString("text"), words)
    }
}.getOrDefault(listOf(LineEdit(1, 0L, "")))

private fun clock(ms: Long): String {
    val value = ms.coerceAtLeast(0L)
    return "%02d:%02d.%02d".format(value / 60000, (value % 60000) / 1000, (value % 1000) / 10)
}

@Composable
private fun StatusCard(message: String) {
    Surface(color = Color(0xFF211B2B), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Purple, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Text(message, color = Color(0xFFD8CBE7), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ModelCard(title: String, description: String, state: String) {
    Surface(color = SurfaceDark, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, fontSize = 13.sp)
            Text(state, color = Purple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
