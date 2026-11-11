package uz.ms.videostudio

import android.app.Application
import android.app.DownloadManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AiPhase { IDLE, LOADING, GENERATING }

data class AiSize(val width: Int, val height: Int) {
    val label: String
        get() = if (width == height) "$width" else "$width×$height"
}

class AiViewModel(app: Application) : AndroidViewModel(app), SdNative.ProgressListener {

    val sizes = listOf(
        AiSize(256, 256),
        AiSize(384, 384),
        AiSize(512, 512),
        AiSize(384, 640),
        AiSize(640, 384),
    )

    // --- modellar ---
    val models = mutableStateListOf<File>()
    var selectedModel by mutableStateOf<String?>(null)
        private set
    var download by mutableStateOf<ModelStore.DownloadStatus?>(null)
        private set
    var customUrl by mutableStateOf("")

    // --- sozlamalar ---
    var prompt by mutableStateOf("")
    var negative by mutableStateOf("")
    var sizeIndex by mutableStateOf(1)
    var fast by mutableStateOf(true)
        private set
    var steps by mutableStateOf(6)
    var cfg by mutableStateOf(1.0f)
    var seedText by mutableStateOf("")
    var threads by mutableStateOf(4)

    // --- holat ---
    var phase by mutableStateOf(AiPhase.IDLE)
        private set
    var step by mutableStateOf(0)
        private set
    var totalSteps by mutableStateOf(0)
        private set
    var elapsedSec by mutableStateOf(0)
        private set
    var result by mutableStateOf<ImageBitmap?>(null)
        private set
    var resultUri by mutableStateOf<Uri?>(null)
        private set
    var resultInfo by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "sd-worker", 64L * 1024L * 1024L)
    }
    private val worker = executor.asCoroutineDispatcher()

    private var loadedPath: String? = null
    private var loadedThreads = 0
    private var cancelRequested = false
    private var downloadJob: Job? = null
    private var timerJob: Job? = null

    private sealed interface Outcome {
        class Ok(val bitmap: Bitmap, val uri: Uri?) : Outcome
        class Fail(val message: String) : Outcome
    }

    init {
        refreshModels()
        if (ModelStore.activeDownloadId(app) >= 0) watchDownload()
    }

    private fun context(): Application = getApplication<Application>()

    fun refreshModels() {
        val found = ModelStore.list(context())
        models.clear()
        models.addAll(found)
        val saved = ModelStore.selectedName(context())
        selectedModel = when {
            found.any { it.name == saved } -> saved
            found.isNotEmpty() -> found.first().name
            else -> null
        }
    }

    fun selectModel(name: String) {
        selectedModel = name
        ModelStore.select(context(), name)
    }

    fun deleteModel(file: File) {
        if (phase != AiPhase.IDLE) return
        val path = file.absolutePath
        viewModelScope.launch {
            withContext(worker) {
                if (loadedPath == path) {
                    try {
                        SdNative.release()
                    } catch (t: Throwable) {
                        // e'tiborsiz
                    }
                    loadedPath = null
                }
                file.delete()
            }
            refreshModels()
        }
    }

    fun updateFast(enabled: Boolean) {
        fast = enabled
        if (enabled) {
            steps = 6
            cfg = 1.0f
        } else {
            steps = 20
            cfg = 7.0f
        }
    }

    // --- yuklab olish ---

    fun startDownload(url: String) {
        if (download != null) return
        val problem = ModelStore.startDownload(context(), url.trim())
        if (problem != null) {
            error = problem
            return
        }
        error = null
        watchDownload()
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        ModelStore.cancelDownload(context())
        download = null
    }

    private fun watchDownload() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            while (isActive) {
                val status = ModelStore.queryDownload(context())
                if (status == null) {
                    download = null
                    break
                }
                if (status.status == DownloadManager.STATUS_SUCCESSFUL) {
                    val name = ModelStore.finishDownload(context())
                    download = null
                    refreshModels()
                    if (name != null) {
                        selectModel(name)
                    } else {
                        error = "Yuklangan faylni saqlab bo'lmadi"
                    }
                    break
                }
                if (status.status == DownloadManager.STATUS_FAILED) {
                    ModelStore.cancelDownload(context())
                    download = null
                    error = "Yuklab olish to'xtadi (kod ${status.reason}). Internetni tekshirib, qayta urinib ko'ring."
                    break
                }
                download = status
                delay(1000)
            }
        }
    }

    // --- rasm yaratish ---

    fun generate() {
        if (phase != AiPhase.IDLE) return
        val modelName = selectedModel
        val modelFile = models.firstOrNull { it.name == modelName }
        if (modelFile == null) {
            error = "Avval modelni yuklab oling"
            return
        }
        val promptText = prompt.trim()
        if (promptText.isEmpty()) {
            error = "Prompt yozing"
            return
        }
        val size = sizes[sizeIndex.coerceIn(0, sizes.size - 1)]
        val seed = seedText.trim().toLongOrNull() ?: Random.nextLong(1L, 2_000_000_000L)
        val negativeText = negative.trim()
        val stepCount = steps
        val cfgScale = cfg
        val lcm = fast
        val threadCount = threads
        val path = modelFile.absolutePath
        val needLoad = loadedPath != path || loadedThreads != threadCount

        error = null
        cancelRequested = false
        step = 0
        totalSteps = stepCount
        elapsedSec = 0
        phase = if (needLoad) AiPhase.LOADING else AiPhase.GENERATING
        val startedAt = System.currentTimeMillis()

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
            }
        }

        viewModelScope.launch {
            val outcome: Outcome = withContext(worker) {
                try {
                    if (needLoad) {
                        loadedPath = null
                        if (!SdNative.load(path, threadCount)) {
                            return@withContext Outcome.Fail(
                                SdNative.lastError().ifBlank { "Modelni ochib bo'lmadi" },
                            )
                        }
                        loadedPath = path
                        loadedThreads = threadCount
                    }
                    mainHandler.post { if (phase == AiPhase.LOADING) phase = AiPhase.GENERATING }
                    val data = SdNative.generate(
                        promptText,
                        negativeText,
                        size.width,
                        size.height,
                        stepCount,
                        cfgScale,
                        seed,
                        lcm,
                        this@AiViewModel,
                    )
                    if (data == null || data.size < 3) {
                        return@withContext Outcome.Fail(
                            SdNative.lastError().ifBlank { "Rasm yaratilmadi" },
                        )
                    }
                    val width = data[0]
                    val height = data[1]
                    if (width <= 0 || height <= 0 || data.size < 2 + width * height) {
                        return@withContext Outcome.Fail("Dvigatel noto'g'ri natija qaytardi")
                    }
                    val bitmap = Bitmap.createBitmap(
                        data, 2, width, width, height, Bitmap.Config.ARGB_8888,
                    )
                    val uri = ModelStore.saveImage(context(), bitmap)
                    Outcome.Ok(bitmap, uri)
                } catch (t: Throwable) {
                    Outcome.Fail(t.message ?: t.javaClass.simpleName)
                }
            }

            timerJob?.cancel()
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
            when (outcome) {
                is Outcome.Ok -> {
                    result = outcome.bitmap.asImageBitmap()
                    resultUri = outcome.uri
                    resultInfo = "${size.label} · $stepCount qadam · ${seconds}s · seed $seed" +
                        if (outcome.uri != null) "\nGalereyaga saqlandi: Pictures/VideoStudio" else ""
                }

                is Outcome.Fail -> {
                    if (!cancelRequested) error = outcome.message
                }
            }
            phase = AiPhase.IDLE
        }
    }

    fun cancelGenerate() {
        if (phase == AiPhase.IDLE) return
        cancelRequested = true
        try {
            SdNative.cancel()
        } catch (t: Throwable) {
            // e'tiborsiz
        }
    }

    fun clearError() {
        error = null
    }

    override fun onProgress(step: Int, steps: Int, secondsPerStep: Float) {
        mainHandler.post {
            if (phase == AiPhase.GENERATING) {
                this.step = step
                this.totalSteps = steps
            }
        }
    }

    override fun onCleared() {
        downloadJob?.cancel()
        timerJob?.cancel()
        try {
            SdNative.cancel()
        } catch (t: Throwable) {
            // e'tiborsiz
        }
        executor.execute {
            try {
                SdNative.release()
            } catch (t: Throwable) {
                // e'tiborsiz
            }
        }
        executor.shutdown()
        super.onCleared()
    }
}
