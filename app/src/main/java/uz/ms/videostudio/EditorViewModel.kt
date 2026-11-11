package uz.ms.videostudio

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val clips = mutableStateListOf<Clip>()

    var loading by mutableStateOf(false)
        private set

    var musicUri by mutableStateOf<Uri?>(null)
        private set
    var musicName by mutableStateOf("")
        private set
    var keepOriginalAudio by mutableStateOf(true)

    var text by mutableStateOf("")
    var textPos by mutableStateOf(TextPos.BOTTOM)
    var textSize by mutableStateOf(0.05f)

    var aspect by mutableStateOf(Aspect.PORTRAIT)
    var shortSide by mutableStateOf(720)
    var crop by mutableStateOf(false)

    var exportState by mutableStateOf<ExportState>(ExportState.Idle)
        private set

    private var nextId = 1L
    private var exporter: Exporter? = null
    private var progressJob: Job? = null
    private var currentOut: File? = null

    val totalMs: Long
        get() = clips.sumOf { it.lengthMs }

    fun addClips(uris: List<Uri>) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            loading = true
            for (uri in uris) {
                val id = nextId++
                val clip = withContext(Dispatchers.IO) { loadClip(context, uri, id) }
                if (clip != null) clips.add(clip)
            }
            loading = false
        }
    }

    fun updateClip(updated: Clip) {
        val index = clips.indexOfFirst { it.id == updated.id }
        if (index >= 0) clips[index] = updated
    }

    fun moveClip(index: Int, delta: Int) {
        val target = index + delta
        if (index !in clips.indices || target !in clips.indices) return
        val item = clips.removeAt(index)
        clips.add(target, item)
    }

    fun removeClip(id: Long) {
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return
        val removed = clips.removeAt(index)
        if (removed.isImage) {
            removed.uri.path?.let { File(it).delete() }
        }
    }

    fun chooseMusic(uri: Uri) {
        musicUri = uri
        musicName = displayName(getApplication<Application>(), uri)
    }

    fun clearMusic() {
        musicUri = null
        musicName = ""
    }

    fun export() {
        if (clips.isEmpty() || exportState is ExportState.Running) return
        val context = getApplication<Application>()
        val outFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val settings = ExportSettings(aspect = aspect, shortSide = shortSide, crop = crop)
        val worker = Exporter(context)
        exporter = worker
        currentOut = outFile
        exportState = ExportState.Running(0)

        try {
            worker.start(
                clips = clips.toList(),
                musicUri = musicUri,
                keepOriginalAudio = keepOriginalAudio,
                text = text,
                textPos = textPos,
                textSize = textSize,
                settings = settings,
                outFile = outFile,
            ) { result ->
                progressJob?.cancel()
                exporter = null
                val file = result.getOrNull()
                if (file != null) {
                    viewModelScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            val uri = saveToGallery(context, file)
                            file.delete()
                            uri
                        }
                        exportState = if (saved != null) {
                            ExportState.Done(saved)
                        } else {
                            ExportState.Failed("Video tayyor bo'ldi, lekin galereyaga saqlab bo'lmadi.")
                        }
                    }
                } else {
                    outFile.delete()
                    exportState = ExportState.Failed(describe(result.exceptionOrNull()))
                }
            }
        } catch (e: Exception) {
            exporter = null
            outFile.delete()
            exportState = ExportState.Failed(describe(e))
            return
        }

        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(300)
                val percent = exporter?.progress() ?: -1
                if (percent >= 0 && exportState is ExportState.Running) {
                    exportState = ExportState.Running(percent)
                }
            }
        }
    }

    fun cancelExport() {
        progressJob?.cancel()
        exporter?.cancel()
        exporter = null
        currentOut?.delete()
        currentOut = null
        exportState = ExportState.Idle
    }

    fun dismissExport() {
        if (exportState !is ExportState.Running) exportState = ExportState.Idle
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun describe(error: Throwable?): String {
        if (error == null) return "Noma'lum xatolik"
        val code = (error as? androidx.media3.transformer.ExportException)?.errorCodeName
        val detail = error.cause?.message ?: error.message ?: error.javaClass.simpleName
        return if (code != null) "$code\n$detail" else detail
    }

    override fun onCleared() {
        progressJob?.cancel()
        exporter?.cancel()
        super.onCleared()
    }
}
