package uz.ms.videostudio

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** AI modellar fayllari: ro'yxat, yuklab olish (tizim DownloadManager orqali), o'chirish. */
object ModelStore {

    /** DreamShaper 7 + LCM (SD 1.5 asosida, 2.1 GB, litsenziya: CreativeML OpenRAIL-M). */
    const val DEFAULT_URL =
        "https://huggingface.co/darkmaniac7/TokForge-DreamShaper-LCM-GGUF/resolve/main/dreamshaper-7-lcm-f16.gguf"

    private const val PREFS = "ai_models"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_NAME = "download_name"
    private const val KEY_SELECTED = "selected"
    private const val PART = ".part"
    private val EXTENSIONS = listOf(".gguf", ".safetensors", ".ckpt")

    data class DownloadStatus(
        val name: String,
        val downloaded: Long,
        val total: Long,
        /** DownloadManager.STATUS_* */
        val status: Int,
        val reason: Int,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        val models = File(base, "models")
        if (!models.exists()) models.mkdirs()
        return models
    }

    fun list(context: Context): List<File> {
        val files = dir(context)?.listFiles() ?: return emptyList()
        return files
            .filter { file ->
                file.isFile && EXTENSIONS.any { ext -> file.name.lowercase().endsWith(ext) }
            }
            .sortedBy { it.name.lowercase() }
    }

    fun selectedName(context: Context): String? = prefs(context).getString(KEY_SELECTED, null)

    fun select(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_SELECTED, name).apply()
    }

    fun fileNameFromUrl(url: String): String {
        val last = Uri.parse(url).lastPathSegment ?: "model.gguf"
        val clean = last.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (EXTENSIONS.any { clean.lowercase().endsWith(it) }) clean else "$clean.gguf"
    }

    fun activeDownloadId(context: Context): Long = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)

    /** Yuklab olishni boshlaydi. Xato bo'lsa matnini qaytaradi, muvaffaqiyatda null. */
    fun startDownload(context: Context, url: String): String? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return "Havola https:// bilan boshlanishi kerak"
        }
        if (dir(context) == null) return "Xotira mavjud emas"
        val name = fileNameFromUrl(url)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            File(dir(context), name + PART).delete()
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("Video Studio — AI modeli")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, null, "models/$name$PART")
            val id = manager.enqueue(request)
            prefs(context).edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_DOWNLOAD_NAME, name)
                .apply()
            null
        } catch (e: Exception) {
            e.message ?: "Yuklab olishni boshlab bo'lmadi"
        }
    }

    fun queryDownload(context: Context): DownloadStatus? {
        val id = activeDownloadId(context)
        if (id < 0) return null
        val name = prefs(context).getString(KEY_DOWNLOAD_NAME, null) ?: return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return DownloadStatus(
                        name = name,
                        downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        ),
                        total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        ),
                        status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                        ),
                        reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // pastda "topilmadi" sifatida qaytadi
        }
        // Yuklab olish yozuvi yo'qolgan (masalan, foydalanuvchi bildirishnomadan bekor qilgan).
        return DownloadStatus(name, 0L, 0L, DownloadManager.STATUS_FAILED, 0)
    }

    /** Muvaffaqiyatli yuklangan faylni yakuniy nomiga o'tkazadi. Tayyor fayl nomini qaytaradi. */
    fun finishDownload(context: Context): String? {
        val name = prefs(context).getString(KEY_DOWNLOAD_NAME, null)
        clearDownload(context)
        if (name == null) return null
        val folder = dir(context) ?: return null
        val part = File(folder, name + PART)
        val target = File(folder, name)
        if (!part.exists()) return null
        if (target.exists()) target.delete()
        return if (part.renameTo(target)) name else null
    }

    fun cancelDownload(context: Context) {
        val id = activeDownloadId(context)
        val name = prefs(context).getString(KEY_DOWNLOAD_NAME, null)
        if (id >= 0) {
            try {
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.remove(id)
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
        clearDownload(context)
        if (name != null) dir(context)?.let { File(it, name + PART).delete() }
    }

    private fun clearDownload(context: Context) {
        prefs(context).edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_NAME).apply()
    }

    /** Yaratilgan rasmni galereyaga (Pictures/VideoStudio) PNG qilib saqlaydi. */
    fun saveImage(context: Context, bitmap: Bitmap): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AI_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VideoStudio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: throw IllegalStateException("Chiqish fayli ochilmadi")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
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
}
