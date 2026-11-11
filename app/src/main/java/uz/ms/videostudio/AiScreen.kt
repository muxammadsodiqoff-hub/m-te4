package uz.ms.videostudio

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@Composable
fun AiScreen(vm: AiViewModel = viewModel()) {
    val context = LocalContext.current
    val busy = vm.phase != AiPhase.IDLE

    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModelCard(vm)
        PromptCard(vm)
        SettingsCard(vm)

        val message = vm.error
        if (message != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Xatolik", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text(message)
                    TextButton(onClick = { vm.clearError() }) { Text("Yopish") }
                }
            }
        }

        if (busy) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (vm.phase == AiPhase.LOADING) {
                        Text("Model xotiraga yuklanmoqda… ${vm.elapsedSec}s")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        val total = vm.totalSteps.coerceAtLeast(1)
                        Text("Qadam ${vm.step}/$total · ${vm.elapsedSec}s")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (vm.step.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = "Ilovani yopmang va boshqa ilovaga o'tmang.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = { vm.cancelGenerate() }) { Text("Bekor qilish") }
                }
            }
        } else {
            Button(
                onClick = { vm.generate() },
                enabled = vm.selectedModel != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Rasm yaratish")
            }
        }

        val image = vm.result
        if (image != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Image(
                        bitmap = image,
                        contentDescription = "Yaratilgan rasm",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Text(
                        text = vm.resultInfo,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    val uri = vm.resultUri
                    if (uri != null) {
                        TextButton(onClick = { shareImage(context, uri) }) { Text("Ulashish") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(vm: AiViewModel) {
    val busy = vm.phase != AiPhase.IDLE
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Model", fontWeight = FontWeight.Bold)

            for (file in vm.models) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = vm.selectedModel == file.name,
                        onClick = { vm.selectModel(file.name) },
                        enabled = !busy,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(file.length()), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.deleteModel(file) }, enabled = !busy) {
                        Text("O'chirish")
                    }
                }
            }

            val download = vm.download
            if (download != null) {
                Spacer(Modifier.height(6.dp))
                Text(download.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                if (download.total > 0L) {
                    LinearProgressIndicator(
                        progress = {
                            (download.downloaded.toFloat() / download.total.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val state = when (download.status) {
                    DownloadManager.STATUS_PAUSED -> " · internet kutilmoqda"
                    DownloadManager.STATUS_PENDING -> " · navbatda"
                    else -> ""
                }
                Text(
                    text = formatBytes(download.downloaded) + " / " + formatBytes(download.total) + state,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = { vm.cancelDownload() }) { Text("Yuklashni bekor qilish") }
            } else {
                if (vm.models.isEmpty()) {
                    Text(
                        text = "Rasm yaratish uchun model kerak. U bir marta yuklab olinadi (2.1 GB, " +
                            "Wi-Fi tavsiya qilinadi), keyin hammasi internetsiz ishlaydi.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Button(onClick = { vm.startDownload(ModelStore.DEFAULT_URL) }) {
                        Text("Modelni yuklab olish (2.1 GB)")
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = vm.customUrl,
                    onValueChange = { value -> vm.customUrl = value },
                    label = { Text("Boshqa model havolasi (.gguf / .safetensors)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { vm.startDownload(vm.customUrl) },
                    enabled = vm.customUrl.isNotBlank(),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text("Havoladan yuklash")
                }
            }
        }
    }
}

@Composable
private fun PromptCard(vm: AiViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Prompt", fontWeight = FontWeight.Bold)
            Text(
                text = "Inglizcha yozing — model boshqa tillarni tushunmaydi.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = vm.prompt,
                onValueChange = { value -> vm.prompt = value },
                label = { Text("Nima chizilsin?") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = vm.negative,
                onValueChange = { value -> vm.negative = value },
                label = { Text("Nima bo'lmasin? (CFG 1 dan katta bo'lsa ishlaydi)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(vm: AiViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Sozlamalar", fontWeight = FontWeight.Bold)

            Text(
                text = "O'lcham (kichigi tezroq)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                vm.sizes.forEachIndexed { index, size ->
                    FilterChip(
                        selected = vm.sizeIndex == index,
                        onClick = { vm.sizeIndex = index },
                        label = { Text(size.label) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tez rejim (LCM)")
                    Text(
                        text = "LCM modellari uchun yoqing. Oddiy SD 1.5 modeli bo'lsa o'chiring.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = vm.fast, onCheckedChange = { checked -> vm.updateFast(checked) })
            }

            Text(
                text = "Qadamlar: ${vm.steps}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Slider(
                value = vm.steps.toFloat(),
                onValueChange = { value -> vm.steps = value.roundToInt() },
                valueRange = if (vm.fast) 2f..12f else 8f..40f,
            )

            Text(
                text = "CFG: " + "%.1f".format(vm.cfg) + " (1.0 eng tez; kattaroq — promptga yaqinroq, 2 barobar sekin)",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = vm.cfg,
                onValueChange = { value -> vm.cfg = (value * 2f).roundToInt() / 2f },
                valueRange = 1f..10f,
            )

            Text(
                text = "Protsessor oqimlari: ${vm.threads}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (count in listOf(2, 4, 6, 8)) {
                    FilterChip(
                        selected = vm.threads == count,
                        onClick = { vm.threads = count },
                        label = { Text("$count") },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = vm.seedText,
                onValueChange = { value -> vm.seedText = value.filter { ch -> ch.isDigit() }.take(10) },
                label = { Text("Seed (bo'sh bo'lsa tasodifiy)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}

private fun shareImage(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, "Ulashish"))
    } catch (e: Exception) {
        Toast.makeText(context, "Mos ilova topilmadi", Toast.LENGTH_SHORT).show()
    }
}
