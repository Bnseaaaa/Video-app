package com.videoapp.vault

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    /** Tên file, ví dụ "IMG_20261118_101530.jpg". */
    val name: String,
    val sizeBytes: Long,
    /** Thời điểm chụp (ms); nếu ảnh không có thì lấy lúc thêm vào máy. */
    val takenAt: Long,
    /** Tên album (thư mục), ví dụ "Camera", "Screenshots". */
    val folder: String,
    /** Đường dẫn để hiển thị, ví dụ "DCIM/Camera/". */
    val path: String?,
    val width: Int,
    val height: Int,
    val mime: String?,
    /** Ổ chứa ảnh: "external_primary" = bộ nhớ trong, còn lại là thẻ nhớ / USB (vd "1a2b-3c4d"). */
    val volume: String = PRIMARY_VOLUME,
    /**
     * Mã nhận diện thư mục, không đổi khi rút/cắm lại thẻ nhớ: "<ổ>|<đường dẫn>".
     * Dùng để nhớ những thư mục người dùng đã chọn.
     */
    val dirKey: String = "",
) {
    val searchKey: String = name.toSearchKey()
    val onPrimary get() = volume == PRIMARY_VOLUME
}

const val PRIMARY_VOLUME = "external_primary"

/** Tách tên + đường dẫn từ [PhotoItem.dirKey] (dùng cho thư mục đang không kết nối). */
fun dirKeyPath(key: String): String = key.substringAfter('|')
fun dirKeyName(key: String): String = dirKeyPath(key).trimEnd('/').substringAfterLast('/').ifEmpty { "Thư mục" }
fun dirKeyVolume(key: String): String = key.substringBefore('|')

fun PhotoItem.matches(query: String): Boolean {
    val words = query.toSearchKey().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.all { it in searchKey }
}

/**
 * Tự tìm toàn bộ ảnh trong máy (bộ nhớ trong và thẻ nhớ) qua MediaStore.
 * Chỉ đọc để hiển thị, không sửa hay xoá ảnh gốc.
 */
class PhotoRepository(context: Context) {

    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("kho_video", Context.MODE_PRIVATE)

    /** Những thư mục người dùng chọn để hiện trong mục Ảnh (vẫn giữ khi thẻ nhớ đang rút ra). */
    fun selectedDirs(): Set<String> = prefs.getStringSet(KEY_DIRS, emptySet()).orEmpty().toSet()

    fun setSelected(key: String, on: Boolean): Set<String> {
        val set = selectedDirs().toMutableSet()
        if (on) set += key else set -= key
        prefs.edit().putStringSet(KEY_DIRS, set).apply()
        return set
    }

    val collection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    suspend fun scan(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathCol = if (q) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            pathCol,
            if (q) MediaStore.Images.Media.VOLUME_NAME else MediaStore.Images.Media._ID,
        )
        val result = ArrayList<PhotoItem>()
        runCatching {
            resolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
        }.getOrNull()?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val rawPath = c.getString(9)
                val path = if (q) rawPath else rawPath?.substringBeforeLast('/')?.substringAfter("/storage/emulated/0/")
                val bucket = c.getString(5)
                val volume: String
                val dirKey: String
                if (q) {
                    volume = c.getString(10) ?: PRIMARY_VOLUME
                    dirKey = "$volume|${rawPath ?: (bucket.orEmpty() + "/")}"
                } else {
                    // Android 9 trở xuống: đường dẫn đầy đủ, vd /storage/1A2B-3C4D/DCIM/Camera/x.jpg
                    val dir = rawPath?.substringBeforeLast('/').orEmpty()
                    volume = if (dir.startsWith("/storage/emulated/")) PRIMARY_VOLUME
                    else dir.removePrefix("/storage/").substringBefore('/').ifEmpty { PRIMARY_VOLUME }
                    val rel = if (volume == PRIMARY_VOLUME) dir.substringAfter("/storage/emulated/0/", dir)
                    else dir.substringAfter("/storage/$volume/", dir)
                    dirKey = "$volume|$rel/"
                }
                val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
                result += PhotoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(1) ?: "Ảnh",
                    sizeBytes = c.getLong(2),
                    takenAt = taken,
                    folder = bucket ?: path?.trimEnd('/')?.substringAfterLast('/') ?: "Khác",
                    path = if (q) path else dirKeyPath(dirKey),
                    width = c.getInt(6),
                    height = c.getInt(7),
                    mime = c.getString(8),
                    volume = volume,
                    dirKey = dirKey,
                )
            }
        }
        result
    }

    /** Ảnh thu nhỏ cho lưới (Android tạo sẵn, rất nhanh). */
    suspend fun thumbnail(item: PhotoItem): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolver.loadThumbnail(item.uri, Size(THUMB, THUMB), null)
            else @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(resolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }.getOrNull()
    }

    /** Ảnh đầy đủ để xem, thu nhỏ vừa đủ nét (cạnh dài tối đa [FULL_MAX]) để không tốn RAM, xoay đúng chiều. */
    suspend fun full(item: PhotoItem): Bitmap? = withContext(Dispatchers.IO) {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val long = max(w, h)
                if (long > FULL_MAX) {
                    val s = FULL_MAX.toFloat() / long
                    decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                }
            }
        }.getOrNull() else null
        // Dự phòng cho máy cũ hoặc định dạng ImageDecoder không đọc được
        modern ?: runCatching { legacyDecode(item.uri) }.getOrNull()
    }

    private fun legacyDecode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FULL_MAX) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }

    companion object {
        private const val KEY_DIRS = "photo_dirs"
        const val THUMB = 256
        const val FULL_MAX = 2560
    }
}

/** Tên dễ hiểu của ổ chứa: "Bộ nhớ trong", hoặc tên thẻ nhớ do máy đặt (vd "Thẻ SD SanDisk"). */
fun storageLabel(context: Context, volume: String): String {
    if (volume == PRIMARY_VOLUME) return "Bộ nhớ trong"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            context.getSystemService(StorageManager::class.java).storageVolumes
                .firstOrNull { it.mediaStoreVolumeName == volume }
                ?.getDescription(context)
                ?.let { return it }
        }
    }
    return "Thẻ nhớ"
}

data class PhotoState(
    /** Mọi ảnh tìm thấy trong máy (dùng cho màn hình chọn thư mục). */
    val all: List<PhotoItem> = emptyList(),
    /** Thư mục đã chọn, kể cả thư mục trên thẻ nhớ đang rút ra. */
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
) {
    /** Ảnh hiện trong mục Ảnh: chỉ những ảnh nằm trong thư mục đã chọn và đang kết nối. */
    val photos: List<PhotoItem> = all.filter { it.dirKey in selected }
    val presentDirs: Set<String> = all.mapTo(HashSet()) { it.dirKey }
    /** Thư mục đã chọn nhưng hiện không thấy (thường do thẻ nhớ đang rút ra). */
    val missingDirs: List<String> = selected.filterNot { it in presentDirs }.sorted()
}

class PhotoViewModel(app: Application) : AndroidViewModel(app) {

    val repo = PhotoRepository(app)
    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()
    private var scanJob: Job? = null

    /** Tự quét lại khi trong máy có ảnh mới / bị xoá (chụp ảnh, chụp màn hình, tải về...). */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh(debounceMs = 800)
    }

    init {
        app.contentResolver.registerContentObserver(repo.collection, true, observer)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }

    fun refresh(debounceMs: Long = 0) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _state.value = PhotoState(repo.scan(), repo.selectedDirs(), loading = false)
        }
    }

    fun setSelected(key: String, on: Boolean) {
        val set = repo.setSelected(key, on)
        _state.value = _state.value.copy(selected = set)
    }
}
