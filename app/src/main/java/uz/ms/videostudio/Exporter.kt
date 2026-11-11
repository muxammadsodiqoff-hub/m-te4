package uz.ms.videostudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File

/** Bo'laklarni bitta MP4 faylga yig'adi. Faqat asosiy (main) oqimdan chaqiriladi. */
@OptIn(UnstableApi::class)
class Exporter(private val context: Context) {

    private var transformer: Transformer? = null
    private val progressHolder = ProgressHolder()

    fun start(
        clips: List<Clip>,
        musicUri: Uri?,
        keepOriginalAudio: Boolean,
        text: String,
        textPos: TextPos,
        textSize: Float,
        settings: ExportSettings,
        outFile: File,
        onDone: (Result<File>) -> Unit,
    ) {
        val width = settings.width
        val height = settings.height
        val layout = if (settings.crop) {
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        } else {
            Presentation.LAYOUT_SCALE_TO_FIT
        }
        val textBitmap: Bitmap? = if (text.isNotBlank()) {
            renderText(text.trim(), textPos, textSize, width, height)
        } else {
            null
        }

        val noAudioProcessors: List<AudioProcessor> = emptyList()
        var anyClipAudio = false

        val items: List<EditedMediaItem> = clips.map { clip ->
            val videoEffects = ArrayList<Effect>()
            videoEffects.add(Presentation.createForWidthAndHeight(width, height, layout))
            if (textBitmap != null) {
                val overlay: TextureOverlay = BitmapOverlay.createStaticBitmapOverlay(textBitmap)
                videoEffects.add(OverlayEffect(ImmutableList.of(overlay)))
            }
            val effects = Effects(noAudioProcessors, videoEffects)

            if (clip.isImage) {
                val mediaItem = MediaItem.Builder()
                    .setUri(clip.uri)
                    .setMimeType(MimeTypes.IMAGE_JPEG)
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(clip.imageDurationMs.coerceAtLeast(500L) * 1000L)
                    .setFrameRate(30)
                    .setEffects(effects)
                    .build()
            } else {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs)
                    .setEndPositionMs(clip.endMs)
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(clip.uri)
                    .setClippingConfiguration(clipping)
                    .build()
                val removeAudio = clip.muted || !keepOriginalAudio || !clip.hasAudio
                if (!removeAudio) anyClipAudio = true
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(removeAudio)
                    .setEffects(effects)
                    .build()
            }
        }

        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence(items))
        if (musicUri != null) {
            val music = EditedMediaItem.Builder(MediaItem.fromUri(musicUri))
                .setRemoveVideo(true)
                .build()
            // isLooping = true: musiqa video uzunligiga moslab takrorlanadi yoki qirqiladi.
            sequences.add(EditedMediaItemSequence(listOf(music), true))
        }

        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(anyClipAudio || musicUri != null)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                transformer = null
                onDone(Result.success(outFile))
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                transformer = null
                onDone(Result.failure(exportException))
            }
        }

        val created = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()
        transformer = created
        created.start(composition, outFile.absolutePath)
    }

    /** 0..100 yoki hali noma'lum bo'lsa -1. */
    fun progress(): Int {
        val current = transformer ?: return -1
        val state = current.getProgress(progressHolder)
        return if (state == Transformer.PROGRESS_STATE_AVAILABLE) progressHolder.progress else -1
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    /** Matnni butun kadr o'lchamidagi shaffof rasmga chizadi; u har bir kadr ustiga qo'yiladi. */
    private fun renderText(
        text: String,
        pos: TextPos,
        sizeFraction: Float,
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val textSizePx = height * sizeFraction

        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.DEFAULT_BOLD
        }
        val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textSizePx
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            strokeWidth = textSizePx * 0.14f
            strokeJoin = Paint.Join.ROUND
        }

        val boxWidth = (width * 0.9f).toInt()
        val strokeLayout = StaticLayout.Builder.obtain(text, 0, text.length, stroke, boxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val fillLayout = StaticLayout.Builder.obtain(text, 0, text.length, fill, boxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

        val x = (width - boxWidth) / 2f
        val y = when (pos) {
            TextPos.TOP -> height * 0.08f
            TextPos.CENTER -> (height - fillLayout.height) / 2f
            TextPos.BOTTOM -> height * 0.92f - fillLayout.height
        }.coerceAtLeast(0f)

        canvas.save()
        canvas.translate(x, y)
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
