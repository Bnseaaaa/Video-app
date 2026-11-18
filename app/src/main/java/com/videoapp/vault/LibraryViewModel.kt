package com.videoapp.vault

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val videos: List<VideoItem> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    val repo = VideoRepository(app)
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private var scanJob: Job? = null

    /** Tự quét lại khi trong máy có video mới / bị xoá (quay video, tải về, ...). */
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
            val list = repo.scan()
            _state.update { it.copy(videos = list, loading = false) }
        }
    }

    fun markWatched(item: VideoItem) {
        if (item.watched) return
        repo.markWatched(item)
        // Cập nhật ngay trên màn hình, không cần quét lại cả máy
        _state.update { st -> st.copy(videos = st.videos.map { if (it.id == item.id) it.copy(watched = true) else it }) }
    }

    fun resetWatched(items: List<VideoItem>) {
        repo.resetWatched(items)
        val ids = items.mapTo(HashSet()) { it.id }
        _state.update { st -> st.copy(videos = st.videos.map { if (it.id in ids) it.copy(watched = false) else it }) }
    }

    fun setHidden(item: VideoItem, hide: Boolean) {
        repo.setHidden(item, hide)
        _state.update { it.copy(message = if (hide) "Đã ẩn \"${item.name}\"" else "Đã bỏ ẩn \"${item.name}\"") }
        refresh()
    }

    fun rename(item: VideoItem, newName: String) {
        val ok = repo.rename(item, newName)
        _state.update { it.copy(message = if (ok) "Đã đổi tên" else "Tên không hợp lệ") }
        refresh()
    }

    fun deleteLegacy(item: VideoItem) = viewModelScope.launch {
        repo.deleteLegacy(item)
        _state.update { it.copy(message = "Đã xoá bản chép \"${item.name}\"") }
        refresh()
    }

    fun exportLegacy(item: VideoItem) = viewModelScope.launch {
        _state.update { it.copy(message = "Đang lưu \"${item.name}\"...") }
        val msg = runCatching { repo.exportLegacy(item) }
            .fold({ "Đã lưu vào Thư viện (Movies/KhoVideo)" }, { "Lưu thất bại: ${it.message}" })
        _state.update { it.copy(message = msg) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
