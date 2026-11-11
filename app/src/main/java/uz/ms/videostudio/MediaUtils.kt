package uz.ms.videostudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

private const val MAX_IMAGE_SIDE = 2048
private const val THUMB_SIDE = 200

/** Tanlangan faylni o'qib, montaj bo'lagiga aylantiradi. Xato bo'lsa null. */
fun loadClip(context: Context, uri: Uri, id: Long): Clip? {
    val type = context.contentResolver.getType(uri) ?: ""
    return try {
        if (type.startsWith("image/")) loadImageClip(context, uri, id) else loadVideoClip(context, uri, id)
    } catch (e: Exception) {
        null
    }
}

private fun loadVideoClip(context: Context, uri: Uri, id: Long): Clip? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val duration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        if (duration <= 0L) return null
        val hasAudio = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        val frame: Bitmap? = try {
            retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                THUMB_SIDE,
                THUMB_SIDE,
            )
        } catch (e: Exception) {
            null
        }
        return Clip(
            id = id,
            uri = uri,
            isImage = false,
            sourceDurationMs = duration,
            startMs = 0L,
            endMs = duration,
            imageDurationMs = 0L,
            muted = false,
            hasAudio = hasAudio,
            thumb = frame?.asImageBitmap(),
        )
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            // e'tiborsiz
        }
    }
}

/**
 * Rasmni kichraytirib (eng katta tomoni 2048 px), to'g'ri burab, kesh papkaga JPEG qilib saqlaydi.
 * Shunda juda katta rasmlar ham eksportda muammo bermaydi.
 */
private fun loadImageClip(context: Context, uri: Uri, id: Long): Clip? {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded: Bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    val rotation = try {
        resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (e: Exception) {
        0f
    }

    val upright: Bitmap = if (rotation != 0f) {
        val matrix = Matrix().apply { postRotate(rotation) }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    } else {
        decoded
    }

    val file = File(context.cacheDir, "img_${id}_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { upright.compress(Bitmap.CompressFormat.JPEG, 92, it) }

    val scale = THUMB_SIDE.toFloat() / max(upright.width, upright.height).toFloat()
    val thumb = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            upright,
            max(1, (upright.width * scale).toInt()),
            max(1, (upright.height * scale).toInt()),
            true,
        )
    } else {
        upright
    }

    return Clip(
        id = id,
        uri = Uri.fromFile(file),
        isImage = true,
        sourceDurationMs = 0L,
        startMs = 0L,
        endMs = 0L,
        imageDurationMs = 3000L,
        muted = true,
        hasAudio = false,
        thumb = thumb.asImageBitmap(),
    )
}

fun displayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    } catch (e: Exception) {
        // e'tiborsiz
    }
    return "Musiqa"
}

/** Tayyor videoni galereyaga (Movies/VideoStudio) ko'chiradi. */
fun saveToGallery(context: Context, file: File): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "VideoStudio_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoStudio")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: return null
    return try {
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("Chiqish fayli ochilmadi")
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        uri
    } catch (e: Exception) {
        try {
            resolver.delete(uri, null, null)
        } catch (ignored: Exception) {
            // e'tiborsiz
        }
        null
    }
}

fun formatTime(ms: Long): String {
    val totalTenths = (ms.coerceAtLeast(0L) + 50L) / 100L
    val minutes = totalTenths / 600L
    val seconds = (totalTenths / 10L) % 60L
    val tenths = totalTenths % 10L
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}
