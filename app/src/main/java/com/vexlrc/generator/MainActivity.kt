package com.vexlrc.generator

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp

private val VexPurple = Color(0xFFA855F7)
private val VexBackground = Color(0xFF0F0F13)
private val VexSurface = Color(0xFF1C1B23)
private val VexMuted = Color(0xFFAAA5B4)

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
    var selectedTab by remember { mutableStateOf("Home") }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf("No audio selected") }
    var lyrics by remember { mutableStateOf("[00:00.00]Start writing your lyrics here") }
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var status by remember { mutableStateOf("Ready for your first project") }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            audioUri = uri
            audioName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
            status = "Audio loaded locally"
            player?.release()
            player = null
            isPlaying = false
        }
    }
    val lrcExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(lyrics) }
            }.onSuccess { status = "LRC exported successfully" }
                .onFailure { status = "Export failed: ${it.localizedMessage}" }
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release(); player = null }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VexPurple,
            secondary = Color(0xFF22C55E),
            background = VexBackground,
            surface = VexSurface,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = VexBackground,
            bottomBar = {
                NavigationBar(containerColor = VexSurface, contentColor = Color.White) {
                    listOf("Home", "Studio", "Models", "Settings").forEach { tab ->
                        val icon = when (tab) {
                            "Home" -> Icons.Default.Home
                            "Studio" -> Icons.Default.LibraryMusic
                            "Models" -> Icons.Default.Download
                            else -> Icons.Default.Settings
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(icon, contentDescription = tab) },
                            label = { Text(tab) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = VexPurple,
                                selectedTextColor = VexPurple,
                                indicatorColor = Color(0xFF38234D),
                                unselectedIconColor = VexMuted,
                                unselectedTextColor = VexMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(42.dp).background(VexPurple, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                    }
                    Column {
                        Text("VEX LRC", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text("GENERATOR  •  LOCAL AI", fontSize = 10.sp, color = VexMuted, letterSpacing = 1.5.sp)
                    }
                }

                when (selectedTab) {
                    "Home" -> {
                        Text("Turn any song into synchronized lyrics.", fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
                        Text("Create standard LRC and word-level Enhanced LRC directly on your device.", color = VexMuted, fontSize = 14.sp)
                        Card(colors = CardDefaults.cardColors(containerColor = VexSurface), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.AudioFile, tint = VexPurple, modifier = Modifier.size(30.dp), contentDescription = null)
                                Text("Start a new project", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(audioName, color = VexMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.AudioFile, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Choose audio file")
                                }
                                OutlinedButton(onClick = { selectedTab = "Studio" }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Open LRC Studio")
                                }
                            }
                        }
                        StatusCard(status)
                        Text("Planned local AI pipeline", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        listOf("1  Audio preprocessing", "2  Whisper transcription", "3  Word alignment", "4  LRC / Enhanced LRC export").forEach {
                            Surface(color = VexSurface, shape = RoundedCornerShape(12.dp)) {
                                Text(it, Modifier.fillMaxWidth().padding(14.dp), color = Color.White)
                            }
                        }
                    }
                    "Studio" -> {
                        Text("LRC Studio", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Edit timestamps and lyrics, then export an LRC file.", color = VexMuted)
                        Surface(color = VexSurface, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(audioName, color = VexMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) { Text("Load audio") }
                                    OutlinedButton(
                                        onClick = {
                                            val uri = audioUri
                                            if (uri == null) status = "Choose an audio file first"
                                            else runCatching {
                                                if (isPlaying) {
                                                    player?.pause()
                                                    isPlaying = false
                                                } else {
                                                    if (player == null) {
                                                        player = MediaPlayer().apply {
                                                            setDataSource(context, uri)
                                                            setOnCompletionListener { isPlaying = false }
                                                            prepare()
                                                        }
                                                    }
                                                    player?.start()
                                                    isPlaying = true
                                                }
                                            }.onFailure { status = "Playback error: ${it.localizedMessage}" }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                                        Text(if (isPlaying) "Pause" else "Play")
                                    }
                                }
                                Text("Lyrics editor", fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = lyrics,
                                    onValueChange = { lyrics = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                                    placeholder = { Text("[00:12.40]Your lyrics...") },
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = VexPurple,
                                        unfocusedBorderColor = Color(0xFF514B5B),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                                Button(onClick = { lrcExporter.launch("lyrics.lrc") }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Export .LRC")
                                }
                                Text("Enhanced LRC word timing and automatic AI alignment are planned for the next implementation stages.", color = VexMuted, fontSize = 12.sp)
                            }
                        }
                        StatusCard(status)
                    }
                    "Models" -> {
                        Text("Model Center", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Local AI models will be downloaded and managed on this device.", color = VexMuted)
                        ModelCard("Whisper.cpp", "Speech-to-text transcription", "Integration pending")
                        ModelCard("Forced Alignment", "Word-level timestamps", "Integration pending")
                        ModelCard("Demucs", "Optional vocal separation", "Optional • integration pending")
                        StatusCard("No model is bundled in this initial build.")
                    }
                    else -> {
                        Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        ModelCard("Privacy", "Audio processing stays on device", "Offline-first")
                        ModelCard("App version", "VEX LRC Generator", "1.0.0 • Initial foundation")
                        Text("AI model downloads, licenses and device compatibility checks will be added before enabling inference.", color = VexMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    Surface(color = Color(0xFF211B2B), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(VexPurple, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Text(message, color = Color(0xFFD8CBE7), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ModelCard(title: String, description: String, state: String) {
    Surface(color = VexSurface, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(description, color = VexMuted, fontSize = 13.sp)
            Text(state, color = VexPurple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
