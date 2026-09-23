package dev.echo

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class State { Idle, Recording, Stopped, Sending }

class Echo(private val app: Application) : AndroidViewModel(app) {
    val prefs = prefs(app)
    var configured by mutableStateOf(prefs.contains("pat"))
    var editing by mutableStateOf(false)
    var validating by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var state by mutableStateOf(State.Idle)
    var pending by mutableIntStateOf(0)

    private val rec = File(app.filesDir, "rec.m4a")
    private var recorder: MediaRecorder? = null
    private var stamp = ""

    init {
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosByTagFlow("sync").collect {
                pending = outbox(app).walk().filter { it.isFile }.map { it.nameWithoutExtension }.toSet().size
            }
        }
    }

    fun save(repo: String, pat: String, locale: String) = viewModelScope.launch {
        validating = true
        error = withContext(Dispatchers.IO) { validate(repo, pat) }
        validating = false
        if (error == null) {
            prefs.edit().putString("repo", repo).putString("pat", pat).putString("locale", locale).apply()
            configured = true
            editing = false
        }
    }

    fun record() {
        stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        recorder = MediaRecorder(app).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(rec)
            prepare()
            start()
        }
        state = State.Recording
    }

    fun stop() {
        state = try {
            recorder!!.stop()
            State.Stopped
        } catch (_: RuntimeException) { // stopped before any audio was captured
            rec.delete()
            State.Idle
        }
        recorder!!.release()
        recorder = null
    }

    fun discard() {
        rec.delete()
        state = State.Idle
    }

    fun send() = viewModelScope.launch {
        state = State.Sending
        val text = transcribe(app, rec, prefs.getString("locale", "de-DE")!!)
        File(outbox(app), "journals/audio").mkdirs()
        rec.renameTo(File(outbox(app), "journals/audio/$stamp.m4a"))
        if (text != null) {
            File(outbox(app), "journals/$stamp.md")
                .writeText("# ${stamp.substring(0, 4)} ${stamp.substring(4, 6)} ${stamp.substring(6, 8)}\n\n$text\n")
            SyncWorker.enqueue(app, "journals/$stamp.md")
        }
        SyncWorker.enqueue(app, "journals/audio/$stamp.m4a")
        state = State.Idle
    }
}

class MainActivity : ComponentActivity() {
    private val vm: Echo by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().padding(16.dp)) {
                        if (!vm.configured || vm.editing) Setup(vm) else Record(vm)
                    }
                }
            }
        }
    }
}

@Composable
fun Setup(vm: Echo) {
    var repo by remember { mutableStateOf(vm.prefs.getString("repo", "")!!) }
    var pat by remember { mutableStateOf(vm.prefs.getString("pat", "")!!) }
    var locale by remember { mutableStateOf(vm.prefs.getString("locale", "de-DE")!!) }
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
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.save(repo, pat, locale) }, enabled = !vm.validating && repo.isNotEmpty() && pat.isNotEmpty()) {
                Text(if (vm.validating) "Checking…" else "Save")
            }
            if (vm.configured) TextButton({ vm.editing = false }) { Text("Cancel") }
        }
    }
}

@Composable
fun Record(vm: Echo) {
    val ctx = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) vm.record()
    }
    Box(Modifier.fillMaxSize()) {
        TextButton({ vm.error = null; vm.editing = true }, Modifier.align(Alignment.TopEnd)) { Text("Settings") }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            when (vm.state) {
                State.Idle -> Button({
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.record()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Record") }
                State.Recording -> Button({ vm.stop() }) { Text("Stop") }
                State.Stopped -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton({ vm.discard() }) { Text("Discard") }
                    Button({ vm.send() }) { Text("Send") }
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
