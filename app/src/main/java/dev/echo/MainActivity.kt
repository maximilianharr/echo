package dev.echo

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.log10

enum class State { Idle, Recording, Paused, Sending }

class Echo(private val app: Application) : AndroidViewModel(app) {
    val prefs = prefs(app)
    var configured by mutableStateOf(prefs.contains("pat"))
    var editing by mutableStateOf(false)
    var validating by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var state by mutableStateOf(State.Idle)
    var pending by mutableIntStateOf(0)
    var days by mutableStateOf(prefs.getStringSet("days", emptySet())!!.toSet())
    var syncing by mutableStateOf(false)
    var level by mutableFloatStateOf(0f) // recording loudness, 0..1

    private val rec = File(app.filesDir, "rec.opus")
    private var recorder: MediaRecorder? = null
    private val audio = app.getSystemService(AudioManager::class.java)
    private var meter: Job? = null
    private var stamp = ""

    init {
        schedule(app)
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosByTagFlow("sync").collect {
                pending = outbox(app).walk().filter { it.isFile }.map { it.nameWithoutExtension }.toSet().size
            }
        }
    }

    private fun storeDays(d: Set<String>) {
        days = d
        prefs.edit().putStringSet("days", d).apply()
    }

    fun save(repo: String, pat: String, locale: String, remind: Boolean, remindAt: Int) = viewModelScope.launch {
        validating = true
        error = withContext(Dispatchers.IO) { validate(repo, pat) }
        validating = false
        if (error == null) {
            val repoChanged = repo != prefs.getString("repo", null)
            prefs.edit().putString("repo", repo).putString("pat", pat).putString("locale", locale)
                .putBoolean("remind", remind).putInt("remindAt", remindAt).apply()
            schedule(app)
            configured = true
            editing = false
            if (repoChanged) sync()
        }
    }

    /** Rebuilds [days] from GitHub plus entries still waiting in the outbox. */
    fun sync() = viewModelScope.launch {
        syncing = true
        try {
            val remote = withContext(Dispatchers.IO) {
                listDays(prefs.getString("repo", "")!!, prefs.getString("pat", "")!!)
            }
            storeDays(remote + outbox(app).walk().filter { it.isFile }.map { it.name.take(8) })
            error = null
        } catch (e: IOException) {
            error = "Sync failed: ${e.message}"
        }
        syncing = false
    }

    fun record() {
        stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        app.startForegroundService(Intent(app, RecordService::class.java))
        // Bluetooth headset mics are only reachable via SCO (call audio path).
        audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?.let { audio.setCommunicationDevice(it) }
        recorder = MediaRecorder(app).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioChannels(1)
            setAudioEncodingBitRate(24_000)
            setAudioSamplingRate(48_000)
            setOutputFile(rec)
            prepare()
            start()
        }
        state = State.Recording
        startMeter()
    }

    private fun startMeter() {
        meter = viewModelScope.launch {
            while (true) {
                val amp = recorder!!.maxAmplitude
                // map -50..0 dBFS to 0..1
                level = if (amp <= 0) 0f else ((20 * log10(amp / 32767.0) + 50) / 50).toFloat().coerceIn(0f, 1f)
                delay(50)
            }
        }
    }

    fun pause() {
        recorder!!.pause()
        meter?.cancel()
        level = 0f
        state = State.Paused
    }

    fun resume() {
        recorder!!.resume()
        state = State.Recording
        startMeter()
    }

    /** Finalizes the recording; false if no audio was captured. */
    private fun stop(): Boolean {
        meter?.cancel()
        level = 0f
        val ok = try {
            recorder!!.stop()
            true
        } catch (_: RuntimeException) { // stopped before any audio was captured
            rec.delete()
            false
        }
        recorder!!.release()
        recorder = null
        audio.clearCommunicationDevice()
        app.stopService(Intent(app, RecordService::class.java))
        return ok
    }

    override fun onCleared() {
        if (recorder != null) stop() // app closed while recording
    }

    fun discard() {
        stop()
        rec.delete()
        state = State.Idle
    }

    fun send() = viewModelScope.launch {
        if (!stop()) {
            state = State.Idle
            return@launch
        }
        state = State.Sending
        val text = transcribe(app, rec, prefs.getString("locale", "de-DE")!!)
        File(outbox(app), "journals/audio").mkdirs()
        rec.renameTo(File(outbox(app), "journals/audio/$stamp.opus"))
        if (text != null) {
            File(outbox(app), "journals/$stamp.md")
                .writeText("# ${stamp.substring(0, 4)} ${stamp.substring(4, 6)} ${stamp.substring(6, 8)}\n\n$text\n")
            SyncWorker.enqueue(app, "journals/$stamp.md")
        }
        SyncWorker.enqueue(app, "journals/audio/$stamp.opus")
        storeDays(days + stamp.take(8))
        state = State.Idle
    }
}

private val Caramel = Color(0xFFF3DCB0)
private val Brown = Color(0xFF3E2415)
private val Night = Color(0xFF23160D)

/** Two-colour theme: caramel on brown (dark) or brown on caramel (light). */
private fun scheme(dark: Boolean): ColorScheme {
    val bg = if (dark) Night else Caramel
    val fg = if (dark) Caramel else Brown
    fun tint(alpha: Float) = fg.copy(alpha = alpha).compositeOver(bg)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = fg, onPrimary = bg,
        primaryContainer = tint(0.15f), onPrimaryContainer = fg,
        secondaryContainer = tint(0.15f), onSecondaryContainer = fg,
        background = bg, onBackground = fg,
        surface = bg, onSurface = fg, onSurfaceVariant = tint(0.7f),
        surfaceContainerHigh = tint(0.08f), surfaceContainerHighest = tint(0.12f),
        outline = tint(0.5f), outlineVariant = tint(0.2f),
    )
}

class MainActivity : ComponentActivity() {
    private val vm: Echo by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = scheme(isSystemInDarkTheme())) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().padding(16.dp)) {
                        if (!vm.configured || vm.editing) Setup(vm) else Record(vm)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Setup(vm: Echo) {
    val ctx = LocalContext.current
    var repo by remember { mutableStateOf(vm.prefs.getString("repo", "")!!) }
    var pat by remember { mutableStateOf(vm.prefs.getString("pat", "")!!) }
    var locale by remember { mutableStateOf(vm.prefs.getString("locale", "de-DE")!!) }
    var remind by remember { mutableStateOf(vm.prefs.getBoolean("remind", false)) }
    var remindAt by remember { mutableIntStateOf(vm.prefs.getInt("remindAt", 20 * 60)) }
    var picking by remember { mutableStateOf(false) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { remind = it }
    BackHandler(vm.configured) { vm.editing = false }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Setup", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(repo, { repo = it.trim() }, Modifier.fillMaxWidth(), label = { Text("GitHub repo (owner/repo)") }, singleLine = true)
        OutlinedTextField(
            pat, { pat = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Fine-grained PAT") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("de-DE", "en-US").forEach {
                FilterChip(locale == it, { locale = it }, { Text(it) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily reminder", Modifier.weight(1f))
            if (remind) TextButton({ picking = true }) { Text("%02d:%02d".format(remindAt / 60, remindAt % 60)) }
            Switch(remind, {
                if (!it) remind = false
                else if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) remind = true
                else notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        if (picking) {
            val time = rememberTimePickerState(remindAt / 60, remindAt % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = false },
                confirmButton = { TextButton({ remindAt = time.hour * 60 + time.minute; picking = false }) { Text("OK") } },
                dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
                text = { TimePicker(time) },
            )
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.save(repo, pat, locale, remind, remindAt) }, enabled = !vm.validating && repo.isNotEmpty() && pat.isNotEmpty()) {
                Text(if (vm.validating) "Checking…" else "Save")
            }
            if (vm.configured) {
                OutlinedButton({ vm.sync() }, enabled = !vm.syncing) { Text(if (vm.syncing) "Syncing…" else "Sync") }
                TextButton({ vm.editing = false }) { Text("Cancel") }
            }
        }
    }
}

/** Last 53 weeks, one column per week (Monday on top); filled cell = at least one entry that day. */
@Composable
fun Heatmap(days: Set<String>) {
    val color = MaterialTheme.colorScheme.onBackground
    val today = LocalDate.now()
    val start = today.with(DayOfWeek.MONDAY).minusWeeks(52)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE))) {
        Canvas(Modifier.size(width = (53 * 12 - 2).dp, height = (7 * 12 - 2).dp)) {
            val cell = Size(10.dp.toPx(), 10.dp.toPx())
            val step = 12.dp.toPx()
            var d = start
            while (!d.isAfter(today)) {
                val filled = d.format(DateTimeFormatter.BASIC_ISO_DATE) in days
                drawRoundRect(
                    if (filled) color else color.copy(alpha = 0.12f),
                    Offset(ChronoUnit.WEEKS.between(start, d) * step, (d.dayOfWeek.value - 1) * step),
                    cell,
                    CornerRadius(2.dp.toPx()),
                )
                d = d.plusDays(1)
            }
        }
    }
}

/** Round icon button with a label below; [level] (0..1) scales it and grows a halo behind it. */
@Composable
fun RoundButton(label: String, size: Dp, onClick: () -> Unit, level: Float = 0f, content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick, Modifier.size(size)
                .graphicsLayer { scaleX = 1 + 0.15f * level; scaleY = scaleX }
                .drawBehind {
                    val inner = this.size.minDimension / 2
                    val r = inner * (1 + 1.2f * level)
                    if (level > 0) drawCircle(
                        Brush.radialGradient(0f to color.copy(alpha = 0.5f), inner / r to color.copy(alpha = 0.5f), 1f to Color.Transparent, radius = r),
                        r,
                    )
                },
            shape = CircleShape, color = color,
        ) { Box(contentAlignment = Alignment.Center) { content() } }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Record(vm: Echo) {
    val ctx = LocalContext.current
    val level by animateFloatAsState(vm.level, label = "level")
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) vm.record()
    }
    Box(Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.End) {
            IconButton({ vm.error = null; vm.editing = true }) { Icon(Icons.Filled.Settings, "Settings") }
            Heatmap(vm.days)
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val onPrimary = MaterialTheme.colorScheme.onPrimary
            when (vm.state) {
                State.Idle -> RoundButton("Record", 96.dp, {
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.record()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Box(Modifier.size(32.dp).background(onPrimary, CircleShape)) }
                State.Recording -> RoundButton("Pause", 96.dp, { vm.pause() }, level) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) { Box(Modifier.size(10.dp, 28.dp).background(onPrimary, RoundedCornerShape(3.dp))) }
                    }
                }
                State.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton("Discard", 72.dp, { vm.discard() }) { Icon(Icons.Filled.Close, null) }
                    RoundButton("Continue", 96.dp, { vm.resume() }) { Box(Modifier.size(32.dp).background(onPrimary, CircleShape)) }
                    RoundButton("Send", 72.dp, { vm.send() }) { Icon(Icons.AutoMirrored.Filled.Send, null) }
                }
                State.Sending -> {
                    CircularProgressIndicator()
                    Text("Transcribing…", Modifier.padding(top = 8.dp))
                }
            }
        }
        if (vm.pending > 0) Text(
            "${vm.pending} ${if (vm.pending == 1) "entry" else "entries"} waiting to sync",
            Modifier.align(Alignment.BottomCenter),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
