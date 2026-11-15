package com.alinawaz.pixelclip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val sharedUrlState = mutableStateOf<String?>(null)

val MODES = listOf("Funny", "Study", "Motivational", "Viral")
val CLIP_COUNTS = listOf(3, 5, 10)
val CLIP_LENGTHS = listOf(30, 60, 90)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrlState.value = extractUrl(intent)
        setContent { PixelClipApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUrl(intent)?.let { sharedUrlState.value = it }
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            return Regex("https?://\\S+").find(text)?.value
        }
        return null
    }
}

@Composable
fun PixelClipApp() {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var engineReady by remember { mutableStateOf(Engine.ready) }
    var engineMsg by remember { mutableStateOf("STARTING ENGINE...") }

    LaunchedEffect(Unit) {
        try {
            Engine.init(context.applicationContext)
            engineReady = true
            engineMsg = "ENGINE READY"
        } catch (e: Exception) {
            engineMsg = "ENGINE ERROR"
        }
        launch {
            try {
                Engine.update(context.applicationContext)
            } catch (e: Exception) {
                // An update failing (e.g. no internet yet) is not fatal.
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Px.Bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Px.PanelDark)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(22.dp).background(Px.Yellow).border(3.dp, Px.Ink),
                contentAlignment = Alignment.Center
            ) {
                Text("✂", fontSize = 10.sp, color = Px.Ink)
            }
            Spacer(Modifier.width(10.dp))
            Text("PIXELCLIP", fontFamily = PixelHead, fontSize = 15.sp, color = Px.Yellow)
            Spacer(Modifier.weight(1f))
            Text(
                engineMsg,
                fontFamily = PixelBody,
                fontSize = 14.sp,
                color = if (engineReady) Px.Green else Px.Yellow
            )
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(Px.Ink))

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ClipScreen(engineReady)
                1 -> ClipsScreen()
                else -> SetupScreen()
            }
        }

        Box(Modifier.fillMaxWidth().height(3.dp).background(Px.Ink))
        Row(
            Modifier.fillMaxWidth().background(Px.PanelDark).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PixelButton(
                "CLIP", Modifier.weight(1f),
                color = if (tab == 0) Px.Green else Px.Panel,
                textColor = if (tab == 0) Px.Ink else Px.Paper
            ) { tab = 0 }
            PixelButton(
                "CLIPS", Modifier.weight(1f),
                color = if (tab == 1) Px.Green else Px.Panel,
                textColor = if (tab == 1) Px.Ink else Px.Paper
            ) { tab = 1 }
            PixelButton(
                "SETUP", Modifier.weight(1f),
                color = if (tab == 2) Px.Green else Px.Panel,
                textColor = if (tab == 2) Px.Ink else Px.Paper
            ) { tab = 2 }
        }
    }
}

@Composable
fun ClipScreen(engineReady: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var url by remember { mutableStateOf("") }
    var modeIdx by remember { mutableIntStateOf(loadDefaultMode(context)) }
    var countIdx by remember { mutableIntStateOf(loadDefaultCount(context)) }
    var lengthIdx by remember { mutableIntStateOf(loadDefaultLength(context)) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var picks by remember { mutableStateOf<List<ClipPick>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var cutting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    val shared = sharedUrlState.value
    LaunchedEffect(shared) {
        if (!shared.isNullOrBlank()) {
            url = shared
            sharedUrlState.value = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PASTE A YOUTUBE LINK", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)

        PixelPanel(color = Px.PanelDark, modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                textStyle = TextStyle(fontFamily = PixelBody, fontSize = 20.sp, color = Px.Paper),
                cursorBrush = SolidColor(Px.Yellow),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (url.isBlank()) {
                        Text("https://youtube.com/...", fontFamily = PixelBody, fontSize = 20.sp, color = Px.Grey)
                    }
                    inner()
                }
            )
        }

        PixelButton("PASTE", Modifier.fillMaxWidth(), color = Px.Blue) {
            clipboard.getText()?.text?.let { text ->
                url = Regex("https?://\\S+").find(text)?.value ?: text
            }
        }

        Text("MODE", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
        MODES.forEachIndexed { i, mode ->
            OptionRow(mode.uppercase(), modeNote(mode), modeIdx == i) { modeIdx = i }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CLIPS", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
                CLIP_COUNTS.forEachIndexed { i, n ->
                    OptionRow("$n", "", countIdx == i) { countIdx = i }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LENGTH", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
                CLIP_LENGTHS.forEachIndexed { i, n ->
                    OptionRow("${n}s", "", lengthIdx == i) { lengthIdx = i }
                }
            }
        }

        PixelButton(
            "★ FIND BEST PARTS", Modifier.fillMaxWidth(),
            enabled = engineReady && url.isNotBlank() && !busy && !cutting
        ) {
            val apiKey = loadApiKey(context)
            if (apiKey.isBlank()) {
                error = "Add your Gemini API key in the SETUP tab first - it is free."
                return@PixelButton
            }
            busy = true
            cutting = false
            error = null
            savedMsg = null
            picks = emptyList()
            info = null
            progress = 0f
            val targetUrl = url.trim()
            val mode = MODES[modeIdx]
            val count = CLIP_COUNTS[countIdx]
            val length = CLIP_LENGTHS[lengthIdx]
            scope.launch {
                try {
                    status = "READING VIDEO..."
                    val vInfo = Engine.fetchInfo(targetUrl)
                    info = vInfo
                    status = "GETTING CAPTIONS..."
                    val vtt = Engine.downloadSubtitles(context.applicationContext, targetUrl)
                    val segs = withContext(Dispatchers.IO) { Transcript.parseVtt(vtt) }
                    if (segs.isEmpty()) throw NoCaptionsException()
                    segments = segs
                    val windows = Transcript.windows(segs)
                    val raw = mutableListOf<RawPick>()
                    windows.forEachIndexed { i, window ->
                        status = "AI READING PART ${i + 1}/${windows.size}..."
                        raw += GeminiClient.findMoments(apiKey, mode, window)
                    }
                    if (raw.isEmpty()) {
                        error = "The AI found no strong moments in this video. Try another mode or another video."
                        status = ""
                    } else {
                        picks = ClipPlanner.plan(
                            raw = raw,
                            segments = segs,
                            videoDurationSec = vInfo.durationSec.toDouble(),
                            count = count,
                            targetLengthSec = length.toDouble()
                        )
                        status = if (picks.isEmpty()) {
                            "The AI found no strong moments in this video. Try another mode or another video."
                        } else {
                            "FOUND ${picks.size} PARTS - CHECK THEM, THEN PRESS CUT"
                        }
                        if (picks.isEmpty()) {
                            error = status
                            status = ""
                        }
                    }
                } catch (e: Exception) {
                    error = cleanError(e)
                    status = ""
                }
                busy = false
            }
        }

        info?.let { v ->
            if (picks.isNotEmpty() || busy) {
                PixelPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(v.title, fontFamily = PixelBody, fontSize = 21.sp, color = Px.Paper)
                        val meta = listOfNotNull(
                            v.uploader,
                            if (v.durationSec > 0) formatDuration(v.durationSec) else null
                        ).joinToString("  -  ")
                        if (meta.isNotBlank()) {
                            Text(meta, fontFamily = PixelBody, fontSize = 16.sp, color = Px.Grey)
                        }
                    }
                }
            }
        }

        picks.forEachIndexed { i, pick ->
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${i + 1}. ${pick.title}",
                        fontFamily = PixelBody, fontSize = 20.sp, color = Px.Paper
                    )
                    Text(
                        "${formatClock(pick.start)} - ${formatClock(pick.end)}  -  ${(pick.end - pick.start).toInt()}s  -  score ${pick.score}/10",
                        fontFamily = PixelBody, fontSize = 16.sp, color = Px.Yellow
                    )
                    if (pick.reason.isNotBlank()) {
                        Text(pick.reason, fontFamily = PixelBody, fontSize = 16.sp, color = Px.Grey)
                    }
                }
            }
        }

        if (picks.isNotEmpty()) {
            PixelButton(
                "✂ CUT ${picks.size} CLIPS", Modifier.fillMaxWidth(),
                enabled = engineReady && !busy && !cutting
            ) {
                cutting = true
                error = null
                savedMsg = null
                progress = 0f
                val targetUrl = url.trim()
                val vInfo = info
                val segs = segments
                val toCut = picks
                scope.launch {
                    var done = 0
                    try {
                        toCut.forEachIndexed { i, pick ->
                            status = "CUTTING CLIP ${i + 1}/${toCut.size}: ${pick.title}"
                            val file = Engine.downloadSection(
                                context.applicationContext, targetUrl, i + 1, pick.start, pick.end
                            ) { p, line ->
                                if (p >= 0) progress = p / 100f
                                if (line.isNotBlank()) status =
                                    "CLIP ${i + 1}/${toCut.size}: " + line.take(70)
                            }
                            val name = file.name
                            val size = file.length()
                            val uri = Engine.saveToGallery(context.applicationContext, file)

                            var srtUri = ""
                            val srtText = Transcript.buildSrt(segs, pick.start, pick.end)
                            if (srtText.isNotBlank()) {
                                val srtFile = File(
                                    context.cacheDir,
                                    name.substringBeforeLast('.') + ".srt"
                                )
                                withContext(Dispatchers.IO) { srtFile.writeText(srtText) }
                                try {
                                    srtUri = Engine.saveSrtToGallery(
                                        context.applicationContext, srtFile
                                    ).toString()
                                } catch (e: Exception) {
                                    // The clip itself is saved; a missing sidecar is not fatal.
                                }
                            }

                            Store.add(
                                context.applicationContext,
                                SavedClip(
                                    title = pick.title,
                                    uri = uri.toString(),
                                    srtUri = srtUri,
                                    fileName = name,
                                    sizeBytes = size,
                                    dateMs = System.currentTimeMillis(),
                                    sourceUrl = targetUrl,
                                    sourceTitle = vInfo?.title ?: "",
                                    durationSec = (pick.end - pick.start).toInt()
                                )
                            )
                            done++
                            progress = done.toFloat() / toCut.size
                        }
                        status = ""
                        savedMsg = "DONE! $done clips saved to Movies > PixelClip. " +
                            "Open them from the CLIPS tab."
                    } catch (e: Exception) {
                        error = cleanError(e)
                        status = ""
                        if (done > 0) {
                            savedMsg = "$done clip(s) were saved before the error - see the CLIPS tab."
                        }
                    }
                    cutting = false
                }
            }
        }

        if (busy || cutting) {
            PixelProgress(progress, Modifier.fillMaxWidth())
        }
        if (status.isNotBlank()) {
            Text(status, fontFamily = PixelBody, fontSize = 16.sp, color = Px.Yellow)
        }
        error?.let {
            PixelPanel(color = Px.DarkRed, modifier = Modifier.fillMaxWidth()) {
                Text("ERROR: $it", fontFamily = PixelBody, fontSize = 17.sp, color = Px.Paper)
            }
        }
        savedMsg?.let {
            PixelPanel(color = Px.DarkGreen, modifier = Modifier.fillMaxWidth()) {
                Text(it, fontFamily = PixelBody, fontSize = 17.sp, color = Px.Paper)
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            "Long YouTube video in - short vertical clips out.\nWorks on videos that have English captions.",
            fontFamily = PixelBody, fontSize = 16.sp, color = Px.Grey
        )
    }
}

@Composable
fun OptionRow(label: String, note: String, isSelected: Boolean, onClick: () -> Unit) {
    PixelPanel(
        color = if (isSelected) Px.DarkGreen else Px.Panel,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(if (isSelected) Px.Green else Px.PanelDark)
                    .border(2.dp, Px.Ink)
            )
            Spacer(Modifier.width(10.dp))
            Text(label, fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
            if (note.isNotBlank()) {
                Spacer(Modifier.weight(1f))
                Text(note, fontFamily = PixelBody, fontSize = 16.sp, color = Px.Grey)
            }
        }
    }
}

fun modeNote(mode: String): String = when (mode) {
    "Funny" -> "jokes + reactions"
    "Study" -> "key explanations"
    "Motivational" -> "strong lines"
    else -> "hooks + shares"
}

@Composable
fun ClipsScreen() {
    val context = LocalContext.current
    var items by remember { mutableStateOf(Store.load(context)) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("YOUR CLIPS", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
        if (items.isEmpty()) {
            PixelPanel(Modifier.fillMaxWidth()) {
                Text(
                    "Nothing here yet. Clip a video and the finished clips will show up here.",
                    fontFamily = PixelBody, fontSize = 18.sp, color = Px.Grey
                )
            }
        }
        items.forEach { item ->
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, fontFamily = PixelBody, fontSize = 20.sp, color = Px.Paper)
                    if (item.sourceTitle.isNotBlank()) {
                        Text(
                            "from: ${item.sourceTitle}",
                            fontFamily = PixelBody, fontSize = 15.sp, color = Px.Grey
                        )
                    }
                    Text(
                        "${formatDate(item.dateMs)}  -  ${formatSize(item.sizeBytes)}  -  ${item.durationSec}s",
                        fontFamily = PixelBody, fontSize = 15.sp, color = Px.Grey
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PixelButton("OPEN", Modifier.weight(1f), color = Px.Blue) {
                            openClip(context, item)
                        }
                        PixelButton("SHARE", Modifier.weight(1f), color = Px.Yellow) {
                            shareClip(context, item)
                        }
                        PixelButton("DEL", Modifier.weight(1f), color = Px.Red) {
                            deleteClip(context, item)
                            Store.remove(context, item)
                            items = Store.load(context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(loadApiKey(context)) }
    var keyMsg by remember { mutableStateOf("") }
    var modeIdx by remember { mutableIntStateOf(loadDefaultMode(context)) }
    var countIdx by remember { mutableIntStateOf(loadDefaultCount(context)) }
    var lengthIdx by remember { mutableIntStateOf(loadDefaultLength(context)) }
    var updateMsg by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GEMINI API KEY", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
        PixelPanel(color = Px.PanelDark, modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                textStyle = TextStyle(fontFamily = PixelBody, fontSize = 20.sp, color = Px.Paper),
                cursorBrush = SolidColor(Px.Yellow),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (apiKey.isBlank()) {
                        Text("paste your free key...", fontFamily = PixelBody, fontSize = 20.sp, color = Px.Grey)
                    }
                    inner()
                }
            )
        }
        PixelButton("SAVE KEY", Modifier.fillMaxWidth(), color = Px.Blue) {
            saveApiKey(context, apiKey.trim())
            keyMsg = "KEY SAVED - it stays only on this phone."
        }
        if (keyMsg.isNotBlank()) {
            Text(keyMsg, fontFamily = PixelBody, fontSize = 16.sp, color = Px.Yellow)
        }
        Text(
            "Free key: open aistudio.google.com, sign in, tap \"Get API key\". No card needed.",
            fontFamily = PixelBody, fontSize = 16.sp, color = Px.Grey
        )

        Text("DEFAULT MODE", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
        MODES.forEachIndexed { i, mode ->
            OptionRow(mode.uppercase(), modeNote(mode), modeIdx == i) {
                modeIdx = i
                saveDefaultMode(context, i)
            }
        }

        Text("DEFAULT CLIPS + LENGTH", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CLIP_COUNTS.forEachIndexed { i, n ->
                    OptionRow("$n", "", countIdx == i) {
                        countIdx = i
                        saveDefaultCount(context, i)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CLIP_LENGTHS.forEachIndexed { i, n ->
                    OptionRow("${n}s", "", lengthIdx == i) {
                        lengthIdx = i
                        saveDefaultLength(context, i)
                    }
                }
            }
        }

        Text("DOWNLOAD ENGINE", fontFamily = PixelHead, fontSize = 10.sp, color = Px.Paper)
        PixelButton(
            "UPDATE ENGINE", Modifier.fillMaxWidth(),
            enabled = !updating && Engine.ready
        ) {
            updating = true
            updateMsg = "CHECKING FOR UPDATES..."
            scope.launch {
                updateMsg = try {
                    "ENGINE: " + Engine.update(context.applicationContext)
                } catch (e: Exception) {
                    "UPDATE FAILED: " + (e.message ?: "error").take(80)
                }
                updating = false
            }
        }
        if (updateMsg.isNotBlank()) {
            Text(updateMsg, fontFamily = PixelBody, fontSize = 16.sp, color = Px.Yellow)
        }

        PixelPanel(Modifier.fillMaxWidth()) {
            Text(
                "PixelClip v1.0\nEngine: yt-dlp (open source)\nAI: Gemini - your own free key\nClips save to Movies > PixelClip\nNeeds English captions on the video\nNo ads - no tracking - no account",
                fontFamily = PixelBody, fontSize = 17.sp, color = Px.Paper
            )
        }
    }
}

fun openClip(context: Context, item: SavedClip) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.uri), "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // The file may have been deleted from the gallery already.
    }
}

fun shareClip(context: Context, item: SavedClip) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share clip"))
    } catch (e: Exception) {
        // Nothing to share if the file is gone.
    }
}

fun deleteClip(context: Context, item: SavedClip) {
    deleteUri(context, item.uri)
    if (item.srtUri.isNotBlank()) deleteUri(context, item.srtUri)
}

fun deleteUri(context: Context, uriString: String) {
    try {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null)
        } else {
            uri.path?.let { File(it).delete() }
        }
    } catch (e: Exception) {
        // Already gone - the list entry is removed anyway.
    }
}

// --- Preferences (SharedPreferences "pixelclip") ---

private fun prefs(context: Context) =
    context.getSharedPreferences("pixelclip", Context.MODE_PRIVATE)

fun loadApiKey(context: Context): String = prefs(context).getString("gemini_key", "") ?: ""

fun saveApiKey(context: Context, key: String) {
    prefs(context).edit().putString("gemini_key", key).apply()
}

fun loadDefaultMode(context: Context): Int = prefs(context).getInt("default_mode", 0)

fun saveDefaultMode(context: Context, value: Int) {
    prefs(context).edit().putInt("default_mode", value).apply()
}

fun loadDefaultCount(context: Context): Int = prefs(context).getInt("default_count", 1)

fun saveDefaultCount(context: Context, value: Int) {
    prefs(context).edit().putInt("default_count", value).apply()
}

fun loadDefaultLength(context: Context): Int = prefs(context).getInt("default_length", 1)

fun saveDefaultLength(context: Context, value: Int) {
    prefs(context).edit().putInt("default_length", value).apply()
}

// --- Formatting + errors ---

fun formatClock(sec: Double): String {
    val total = sec.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDuration(sec: Int): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)

fun formatSize(bytes: Long): String =
    if (bytes <= 0) "?" else "%.1f MB".format(bytes / 1024.0 / 1024.0)

fun formatDate(ms: Long): String =
    SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(Date(ms))

fun cleanError(e: Exception): String {
    if (e is NoCaptionsException) return e.message ?: "No captions on this video."
    val msg = e.message ?: "Something went wrong"
    val line = msg.lines().firstOrNull { it.contains("ERROR", ignoreCase = true) }
    var text = (line ?: msg).trim()
    // yt-dlp messages already start with "ERROR:" and the UI adds its own prefix.
    while (text.startsWith("ERROR:", ignoreCase = true)) {
        text = text.removeRange(0, "ERROR:".length).trim()
    }
    return text.take(160)
}
