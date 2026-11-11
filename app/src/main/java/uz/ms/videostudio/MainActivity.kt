package uz.ms.videostudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF9C8CFF),
                    secondary = Color(0xFF6C4DF5),
                    background = Color(0xFF121218),
                    surface = Color(0xFF1B1B24),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    EditorScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.addClips(uris)
    }
    val pickMusic = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) vm.chooseMusic(uri)
    }

    val running = vm.exportState is ExportState.Running
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Video Studio") }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            pickMedia.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("+ Video / rasm")
                    }
                    Button(
                        onClick = { vm.export() },
                        enabled = vm.clips.isNotEmpty() && !running && !vm.loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Eksport " + formatTime(vm.totalMs))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.clips.isEmpty() && !vm.loading) {
                item(key = "empty") {
                    Text(
                        text = "Boshlash uchun pastdagi \"+ Video / rasm\" tugmasi orqali " +
                            "galereyadan video yoki rasm tanlang.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            itemsIndexed(vm.clips, key = { _, item -> item.id }) { index, item ->
                ClipCard(
                    index = index,
                    count = vm.clips.size,
                    item = item,
                    onChange = { vm.updateClip(it) },
                    onMove = { delta -> vm.moveClip(index, delta) },
                    onRemove = { vm.removeClip(item.id) },
                )
            }
            if (vm.loading) {
                item(key = "loading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Fayllar o'qilmoqda…")
                    }
                }
            }
            item(key = "music") {
                MusicSection(vm = vm, onPick = { pickMusic.launch("audio/*") })
            }
            item(key = "text") { TextSection(vm) }
            item(key = "format") { FormatSection(vm) }
        }
    }

    ExportDialog(vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipCard(
    index: Int,
    count: Int,
    item: Clip,
    onChange: (Clip) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val thumb = item.thumb
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF33333F)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. " + if (item.isImage) "Rasm" else "Video",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatTime(item.lengthMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Yuqoriga")
                }
                IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Pastga")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "O'chirish")
                }
            }

            if (item.isImage) {
                Text(
                    text = "Ekranda turish vaqti: " + formatTime(item.imageDurationMs),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Slider(
                    value = item.imageDurationMs / 1000f,
                    onValueChange = { seconds ->
                        onChange(item.copy(imageDurationMs = (seconds * 1000f).toLong()))
                    },
                    valueRange = 1f..10f,
                )
            } else {
                if (item.sourceDurationMs > 1000L) {
                    Text(
                        text = "Kesish: " + formatTime(item.startMs) + " – " + formatTime(item.endMs),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    RangeSlider(
                        value = item.startMs.toFloat()..item.endMs.toFloat(),
                        onValueChange = { range ->
                            val start = range.start.toLong().coerceIn(0L, item.sourceDurationMs)
                            val end = range.endInclusive.toLong().coerceIn(0L, item.sourceDurationMs)
                            if (end - start >= 300L) {
                                onChange(item.copy(startMs = start, endMs = end))
                            }
                        },
                        valueRange = 0f..item.sourceDurationMs.toFloat(),
                    )
                }
                if (item.hasAudio) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.muted,
                            onCheckedChange = { checked -> onChange(item.copy(muted = checked)) },
                        )
                        Text("Bu videoning ovozini o'chirish")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun MusicSection(vm: EditorViewModel, onPick: () -> Unit) {
    SectionCard(title = "Musiqa") {
        if (vm.musicUri == null) {
            OutlinedButton(onClick = onPick) { Text("Musiqa tanlash") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = vm.musicName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.clearMusic() }) { Text("Olib tashlash") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = vm.keepOriginalAudio,
                onCheckedChange = { checked -> vm.keepOriginalAudio = checked },
            )
            Text("Videolarning asl ovozini saqlash")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSection(vm: EditorViewModel) {
    SectionCard(title = "Matn (butun video ustida)") {
        OutlinedTextField(
            value = vm.text,
            onValueChange = { value -> vm.text = value },
            label = { Text("Matn yozing") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pos in TextPos.values()) {
                FilterChip(
                    selected = vm.textPos == pos,
                    onClick = { vm.textPos = pos },
                    label = { Text(pos.label) },
                )
            }
        }
        Text(
            text = "Matn o'lchami",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
            value = vm.textSize,
            onValueChange = { value -> vm.textSize = value },
            valueRange = 0.03f..0.09f,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatSection(vm: EditorViewModel) {
    SectionCard(title = "Format") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (aspect in Aspect.values()) {
                FilterChip(
                    selected = vm.aspect == aspect,
                    onClick = { vm.aspect = aspect },
                    label = { Text(aspect.label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = vm.shortSide == 720,
                onClick = { vm.shortSide = 720 },
                label = { Text("720p") },
            )
            FilterChip(
                selected = vm.shortSide == 1080,
                onClick = { vm.shortSide = 1080 },
                label = { Text("1080p") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !vm.crop,
                onClick = { vm.crop = false },
                label = { Text("Sig'dirish") },
            )
            FilterChip(
                selected = vm.crop,
                onClick = { vm.crop = true },
                label = { Text("To'ldirish (qirqib)") },
            )
        }
    }
}

@Composable
private fun ExportDialog(vm: EditorViewModel) {
    val context = LocalContext.current
    when (val state = vm.exportState) {
        is ExportState.Idle -> Unit

        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Eksport qilinmoqda") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${state.progress}% — ilovani yopmang")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.cancelExport() }) { Text("Bekor qilish") }
            },
        )

        is ExportState.Done -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("Tayyor") },
            text = { Text("Video galereyaga saqlandi: Movies/VideoStudio") },
            confirmButton = {
                TextButton(onClick = { openVideo(context, state.uri) }) { Text("Ochish") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { shareVideo(context, state.uri) }) { Text("Ulashish") }
                    TextButton(onClick = { vm.dismissExport() }) { Text("Yopish") }
                }
            },
        )

        is ExportState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("Xatolik") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = { vm.dismissExport() }) { Text("Yopish") }
            },
        )
    }
}

private fun openVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launch(context, intent)
}

private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launch(context, Intent.createChooser(send, "Ulashish"))
}

private fun launch(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Mos ilova topilmadi", Toast.LENGTH_SHORT).show()
    }
}
