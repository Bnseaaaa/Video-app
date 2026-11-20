package com.videoapp.vault

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class VideoItem(
    /** Khoá duy nhất: "m:<id MediaStore>" hoặc "f:<tên file>" cho bản chép cũ. */
    val id: String,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    /** Thời điểm video được thêm vào máy (ms). */
    val addedAt: Long,
    /** Tên thư mục chứa video, ví dụ "Camera", "Download". */
    val folder: String,
    /** Đường dẫn để hiển thị, ví dụ "DCIM/Camera/". */
    val path: String?,
    val mime: String?,
    val hidden: Boolean = false,
    /** Đã từng mở xem trong app (dùng cho Gacha: chỉ quay ra video chưa xem). */
    val watched: Boolean = false,
    /** Chỉ có với video chép vào app từ phiên bản 1.0. */
    val localFile: File? = null,
    /** Tên file gốc (kể cả khi đã đổi tên trong app). */
    val fileName: String = "",
) {
    /** Chuỗi dùng để tìm kiếm: gồm tên hiển thị + tên file, đã bỏ dấu và viết thường. */
    val searchKey: String = "$name $fileName".toSearchKey()

    val mediaId: Long? get() = id.takeIf { it.startsWith("m:") }?.substring(2)?.toLongOrNull()
}

/** Bỏ dấu tiếng Việt + viết thường, để gõ "du lich" vẫn khớp "Du lịch". */
fun String.toSearchKey(): String =
    java.text.Normalizer.normalize(lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd')

/** Khớp khi tên video chứa tất cả các từ trong ô tìm kiếm (không cần đúng thứ tự). */
fun VideoItem.matches(query: String): Boolean {
    val words = query.toSearchKey().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.all { it in searchKey }
}

const val LEGACY_FOLDER = "Bản chép trong app"

/**
 * Tự tìm toàn bộ video trong máy thông qua MediaStore (cơ sở dữ liệu media của Android),
 * gồm cả bộ nhớ trong và thẻ nhớ. App không chép hay sửa video gốc.
 * Tên đổi trong app và danh sách video bị ẩn được lưu riêng trong app.
 */
class VideoRepository(private val context: Context) {

    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("kho_video", Context.MODE_PRIVATE)
    private val legacyDir = File(context.filesDir, "videos")
    private val thumbDir = File(context.filesDir, "thumbs")

    val collection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    suspend fun scan(): List<VideoItem> = withContext(Dispatchers.IO) {
        val hidden = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        val watched = prefs.getStringSet(KEY_WATCHED, emptySet()).orEmpty()
        val result = ArrayList<VideoItem>()

        val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.RELATIVE_PATH
        else @Suppress("DEPRECATION") MediaStore.Video.Media.DATA
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            pathCol,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        runCatching {
            resolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC")
        }.getOrNull()?.use { c ->
            while (c.moveToNext()) {
                val mediaId = c.getLong(0)
                val key = "m:$mediaId"
                val fileName = c.getString(1) ?: "Video"
                val rawPath = c.getString(7)
                val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rawPath
                else rawPath?.substringBeforeLast('/')?.substringAfter("/storage/emulated/0/")
                val dateAdded = c.getLong(4)
                val dateModified = c.getLong(8)
                val latestTime = maxOf(dateAdded, dateModified) * 1000L
                result += VideoItem(
                    id = key,
                    uri = ContentUris.withAppendedId(collection, mediaId),
                    name = prefs.getString(KEY_NAME + key, null) ?: fileName.substringBeforeLast('.'),
                    sizeBytes = c.getLong(2),
                    durationMs = c.getLong(3),
                    addedAt = latestTime,
                    folder = c.getString(5) ?: path?.trimEnd('/')?.substringAfterLast('/') ?: "Khác",
                    path = path,
                    mime = c.getString(6),
                    hidden = key in hidden,
                    watched = key in watched,
                    fileName = fileName,
                )
            }
        }
        result += scanLegacy(hidden, watched)
        result
    }

    fun setHidden(item: VideoItem, hide: Boolean) {
        val set = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toMutableSet()
        if (hide) set += item.id else set -= item.id
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun markWatched(item: VideoItem) {
        val set = prefs.getStringSet(KEY_WATCHED, emptySet()).orEmpty().toMutableSet()
        if (set.add(item.id)) prefs.edit().putStringSet(KEY_WATCHED, set).apply()
    }

    /** Bắt đầu vòng Gacha mới: coi các video này là chưa xem. */
    fun resetWatched(items: List<VideoItem>) {
        val ids = items.mapTo(HashSet()) { it.id }
        val set = prefs.getStringSet(KEY_WATCHED, emptySet()).orEmpty().filterNot { it in ids }.toSet()
        prefs.edit().putStringSet(KEY_WATCHED, set).apply()
    }

    /** Đổi tên hiển thị trong app. File gốc giữ nguyên tên. */
    fun rename(item: VideoItem, newName: String): Boolean {
        val clean = newName.trim().take(120)
        if (clean.isEmpty()) return false
        prefs.edit().putString(KEY_NAME + item.id, clean).apply()
        return true
    }

    // ───────────── Ảnh thu nhỏ ─────────────

    /** Lấy ảnh thu nhỏ do Android tạo sẵn (nhanh, không phải tự giải mã video). */
    suspend fun thumbnail(item: VideoItem): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val mediaId = item.mediaId
            when {
                item.localFile != null -> legacyThumb(item.localFile)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> resolver.loadThumbnail(item.uri, Size(384, 384), null)
                mediaId != null -> @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(resolver, mediaId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                else -> null
            }
        }.getOrNull()
    }

    // ───────────── Video chép vào app từ phiên bản 1.0 ─────────────

    private fun scanLegacy(hidden: Set<String>, watched: Set<String>): List<VideoItem> =
        legacyDir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().map { f ->
            val key = "f:" + f.name
            VideoItem(
                id = key,
                uri = Uri.fromFile(f),
                name = prefs.getString(KEY_NAME + key, null) ?: f.nameWithoutExtension,
                sizeBytes = f.length(),
                durationMs = legacyDuration(f),
                addedAt = f.lastModified(),
                folder = LEGACY_FOLDER,
                path = null,
                mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()),
                hidden = key in hidden,
                watched = key in watched,
                localFile = f,
                fileName = f.name,
            )
        }

    private fun legacyDuration(f: File): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(f.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { release() }
        }
    }.getOrDefault(0L)

    private fun legacyThumb(f: File): Bitmap? {
        val cached = File(thumbDir, f.name + ".jpg")
        if (cached.exists()) return android.graphics.BitmapFactory.decodeFile(cached.absolutePath)
        val bmp = MediaMetadataRetriever().run {
            try {
                setDataSource(f.absolutePath)
                getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally { release() }
        } ?: return null
        thumbDir.mkdirs()
        FileOutputStream(cached).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return bmp
    }

    /** Xoá bản chép cũ trong app (video gốc trong máy không bị ảnh hưởng). */
    suspend fun deleteLegacy(item: VideoItem) = withContext(Dispatchers.IO) {
        val f = item.localFile ?: return@withContext
        f.delete()
        File(thumbDir, f.name + ".jpg").delete()
    }

    /** Lưu bản chép cũ ra Thư viện (Movies/KhoVideo). */
    suspend fun exportLegacy(item: VideoItem) = withContext(Dispatchers.IO) {
        val file = requireNotNull(item.localFile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, item.mime ?: "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/KhoVideo")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(
                resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ) { "Không tạo được file trong Thư viện" }
            try {
                resolver.openOutputStream(uri).use { out ->
                    requireNotNull(out)
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "KhoVideo")
            dir.mkdirs()
            var out = File(dir, file.name)
            var i = 1
            while (out.exists()) out = File(dir, "${file.nameWithoutExtension} (${i++}).${file.extension}")
            file.copyTo(out)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
        }
    }

    companion object {
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_WATCHED = "watched"
        private const val KEY_NAME = "name_"
    }
}
