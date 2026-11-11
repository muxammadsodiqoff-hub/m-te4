package uz.ms.videostudio

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap

/** Montaj lentasidagi bitta bo'lak: video yoki rasm. */
data class Clip(
    val id: Long,
    val uri: Uri,
    val isImage: Boolean,
    /** Video uchun asl davomiylik (ms). Rasm uchun 0. */
    val sourceDurationMs: Long,
    val startMs: Long,
    val endMs: Long,
    /** Rasm ekranda turadigan vaqt (ms). */
    val imageDurationMs: Long,
    val muted: Boolean,
    val hasAudio: Boolean,
    val thumb: ImageBitmap?,
) {
    val lengthMs: Long
        get() = if (isImage) imageDurationMs else (endMs - startMs).coerceAtLeast(0L)
}

enum class Aspect(val label: String) {
    PORTRAIT("9:16"),
    LANDSCAPE("16:9"),
    SQUARE("1:1"),
}

enum class TextPos(val label: String) {
    TOP("Tepa"),
    CENTER("O'rta"),
    BOTTOM("Past"),
}

data class ExportSettings(
    val aspect: Aspect,
    /** Qisqa tomon piksellarda: 720 yoki 1080. */
    val shortSide: Int,
    /** true: kadr to'ldiriladi (chetlari qirqiladi); false: sig'diriladi (qora hoshiya). */
    val crop: Boolean,
) {
    val width: Int
        get() = when (aspect) {
            Aspect.PORTRAIT, Aspect.SQUARE -> shortSide
            Aspect.LANDSCAPE -> even(shortSide * 16 / 9)
        }

    val height: Int
        get() = when (aspect) {
            Aspect.LANDSCAPE, Aspect.SQUARE -> shortSide
            Aspect.PORTRAIT -> even(shortSide * 16 / 9)
        }

    private fun even(v: Int): Int = if (v % 2 == 0) v else v + 1
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Int) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}
