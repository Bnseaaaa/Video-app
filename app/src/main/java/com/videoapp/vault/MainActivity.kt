package com.videoapp.vault

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: LibraryViewModel by viewModels()
    private val photoVm: PhotoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        StreakStore.init(this)
        setContent { KhoVideoTheme { AppRoot(vm, photoVm) } }
    }
}

// ───────────────────────────── Hai mục: Video / Ảnh ─────────────────────────────

private enum class Tab(val label: String, val icon: ImageVector) {
    VIDEO("Video", Icons.Rounded.VideoLibrary),
    PHOTO("Ảnh", Icons.Rounded.PhotoLibrary),
}

/** Khung chính: thanh chuyển giữa mục Video và mục Ảnh (hai mục tách biệt, mỗi mục có thư mục, tìm kiếm riêng). */
@Composable
private fun AppRoot(vm: LibraryViewModel, photoVm: PhotoViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kho_video", Context.MODE_PRIVATE) }
    // Mở lại đúng mục lần trước đang dùng
    var tab by rememberSaveable { mutableStateOf(Tab.entries.getOrElse(prefs.getInt("tab", 0)) { Tab.VIDEO }) }
    val holder = rememberSaveableStateHolder()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; prefs.edit().putInt("tab", t.ordinal).apply() },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // Giữ nguyên trạng thái từng mục (thư mục đang mở, ô tìm kiếm...) khi chuyển qua lại
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.VIDEO -> LibraryScreen(vm)
                    Tab.PHOTO -> PhotoScreen(photoVm)
                }
            }
        }
    }
}

// ───────────────────────────── Quyền truy cập ─────────────────────────────

internal enum class Access { NONE, PARTIAL, FULL }

/** Video và ảnh xin quyền riêng (Android 13+ tách quyền "Video" và "Ảnh"). */
internal enum class MediaKind(val permission33: String, val askedKey: String) {
    VIDEO(Manifest.permission.READ_MEDIA_VIDEO, "asked"),
    PHOTO(Manifest.permission.READ_MEDIA_IMAGES, "asked_photo"),
}

internal fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

internal fun currentAccess(ctx: Context, kind: MediaKind = MediaKind.VIDEO): Access = when {
    Build.VERSION.SDK_INT >= 33 && granted(ctx, kind.permission33) -> Access.FULL
    Build.VERSION.SDK_INT < 33 && granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
    Build.VERSION.SDK_INT >= 34 && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
    else -> Access.NONE
}

internal fun requiredPermissions(kind: MediaKind = MediaKind.VIDEO): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(kind.permission33, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(kind.permission33)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    )
}

// ───────────────────────────── Màn hình chính ─────────────────────────────

private enum class SortMode(val label: String) { NEWEST("Mới nhất"), NAME("Tên A–Z"), SIZE("Dung lượng"), LENGTH("Thời lượng") }
private enum class FolderSort(val label: String) { RECENT("Mới cập nhật"), NAME("Tên A–Z"), COUNT("Nhiều video nhất"), SIZE("Dung lượng") }

private const val FILTER_HIDDEN = "\u0000hidden"

/** Một thư mục trên màn hình chính. */
private class FolderInfo(
    val key: String,
    val name: String,
    val videos: List<VideoItem>,
) {
    val count get() = videos.size
    val size = videos.sumOf { it.sizeBytes }
    val cover = videos.maxBy { it.addedAt }
    val latestAt = cover.addedAt
    val path = videos.firstNotNullOfOrNull { it.path }
}

private val LocalRepo = staticCompositionLocalOf<VideoRepository> { error("no repo") }

private fun List<VideoItem>.sortedBy(mode: SortMode) = when (mode) {
    SortMode.NEWEST -> sortedByDescending { it.addedAt }
    SortMode.NAME -> sortedBy { it.name.lowercase() }
    SortMode.SIZE -> sortedByDescending { it.sizeBytes }
    SortMode.LENGTH -> sortedByDescending { it.durationMs }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryScreen(vm: LibraryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var access by remember { mutableStateOf(currentAccess(context)) }
    val prefs = remember { context.getSharedPreferences("kho_video", Context.MODE_PRIVATE) }
    var query by rememberSaveable { mutableStateOf("") }          // tìm ở màn hình chính (toàn máy)
    var folderQuery by rememberSaveable { mutableStateOf("") }    // tìm trong thư mục đang mở
    var sort by rememberSaveable { mutableStateOf(prefs.getString("video_sort", null)?.let { value -> SortMode.entries.firstOrNull { it.name == value } } ?: SortMode.NEWEST) }
    var folderSort by rememberSaveable { mutableStateOf(prefs.getString("folder_sort", null)?.let { value -> FolderSort.entries.firstOrNull { it.name == value } } ?: FolderSort.RECENT) }
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("video_grid_mode", false)) }
    var showWidgets by rememberSaveable { mutableStateOf(prefs.getBoolean("show_widgets", true)) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    LaunchedEffect(sort) { prefs.edit().putString("video_sort", sort.name).apply() }
    LaunchedEffect(folderSort) { prefs.edit().putString("folder_sort", folderSort.name).apply() }
    var singleDeleteFor by remember { mutableStateOf<VideoItem?>(null) }
    var confirmDeleteMulti by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }
    var renameFor by remember { mutableStateOf<VideoItem?>(null) }
    var deleteLegacyFor by remember { mutableStateOf<VideoItem?>(null) }
    var pendingExport by remember { mutableStateOf<VideoItem?>(null) }
    val homeGrid = rememberLazyGridState()
    val streak by StreakStore.state.collectAsStateWithLifecycle()
    var streakOpen by rememberSaveable { mutableStateOf(false) }
    var streakMsgs by remember { mutableStateOf(emptyList<String>()) }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            vm.refresh()
        }
    }

    fun deleteVideos(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val legacy = items.filter { it.localFile != null }
        val media = items.filter { it.localFile == null }
        legacy.forEach { vm.deleteLegacy(it) }
        if (media.isEmpty()) {
            vm.refresh()
            return
        }
        val uris = media.map { it.uri }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            var recoverable: IntentSender? = null
            for (m in media) {
                try {
                    context.contentResolver.delete(m.uri, null, null)
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
                        recoverable = e.userAction.actionIntent.intentSender
                        break
                    }
                } catch (_: Exception) {}
            }
            if (recoverable != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(recoverable).build())
            } else {
                vm.refresh()
            }
        }
    }
    // Tự mở bảng chuỗi lửa (một lần cho mỗi lần đứt chuỗi) để hỏi có dùng lửa xanh không
    var gapShown by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(streak.gap?.firstDay) {
        val g = streak.gap ?: return@LaunchedEffect
        if (gapShown != g.firstDay) { gapShown = g.firstDay; streakOpen = true }
    }
    LaunchedEffect(streakMsgs) {
        streakMsgs.forEach { snackbar.showSnackbar(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean("asked", true).apply()
        access = currentAccess(context)
        vm.refresh()
    }
    val requestAccess = {
        val activity = context as Activity
        val blocked = prefs.getBoolean("asked", false) && access == Access.NONE &&
            !activity.shouldShowRequestPermissionRationale(requiredPermissions()[0])
        // Người dùng đã từ chối hẳn: Android không hiện hộp thoại nữa, phải mở Cài đặt
        if (blocked) openAppSettings(context) else permissionLauncher.launch(requiredPermissions())
    }

    // Kiểm tra lại quyền + quét lại mỗi khi quay về app (có thể vừa đổi quyền trong Cài đặt)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = currentAccess(context)
        vm.refresh()
        streakMsgs = StreakStore.refresh()
    }

    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val item = pendingExport
        pendingExport = null
        if (ok && item != null) vm.exportLegacy(item)
    }
    fun export(item: VideoItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pendingExport = item
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else vm.exportLegacy(item)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val visible = remember(state.videos) { state.videos.filterNot { it.hidden } }
    val resumeId = prefs.getString("last_playback_id", null)
    val resumeItem = remember(state.videos, resumeId) { state.videos.firstOrNull { it.id == resumeId } }
    val resumePosition = resumeId?.let { prefs.getLong("playback_pos_$it", 0L) } ?: 0L
    val hiddenVideos = remember(state.videos) { state.videos.filter { it.hidden } }
    val folders = remember(visible, folderSort) {
        visible.groupBy { it.folder }.map { (name, list) -> FolderInfo(name, name, list) }.let { list ->
            when (folderSort) {
                FolderSort.RECENT -> list.sortedByDescending { it.latestAt }
                FolderSort.NAME -> list.sortedBy { it.name.lowercase() }
                FolderSort.COUNT -> list.sortedByDescending { it.count }
                FolderSort.SIZE -> list.sortedByDescending { it.size }
            }
        }
    }
    val hiddenFolder = remember(hiddenVideos) {
        hiddenVideos.takeIf { it.isNotEmpty() }?.let { FolderInfo(FILTER_HIDDEN, "Đã ẩn", it) }
    }
    val current: FolderInfo? = openFolder?.let { key ->
        if (key == FILTER_HIDDEN) hiddenFolder else folders.firstOrNull { it.key == key }
    }
    // Thư mục đang mở không còn video nào (vd vừa ẩn hết) → quay về màn hình chính
    LaunchedEffect(openFolder, current, state.loading) {
        if (openFolder != null && current == null && !state.loading) openFolder = null
    }
    val closeFolder = { openFolder = null; folderQuery = ""; selectionMode = false; selectedIds = emptySet() }
    BackHandler(enabled = selectionMode || openFolder != null) {
        if (selectionMode) {
            selectionMode = false
            selectedIds = emptySet()
        } else {
            closeFolder()
        }
    }

    val searching = query.isNotBlank()
    val searchResults = remember(state.videos, query, sort) {
        // Tìm trong TOÀN BỘ video của máy, kể cả video đã ẩn
        if (query.isBlank()) emptyList() else state.videos.filter { it.matches(query) }.sortedBy(sort)
    }
    val folderVideos = remember(current, folderQuery, sort) {
        current?.videos.orEmpty().filter { folderQuery.isBlank() || it.matches(folderQuery) }.sortedBy(sort)
    }

    /** Phát video; danh sách đang hiện (thư mục đang mở / kết quả tìm kiếm) dùng cho nút video trước / sau. */
    fun play(item: VideoItem, queue: List<VideoItem>? = null) {
        vm.markWatched(item)
        val list = queue ?: when {
            current != null -> folderVideos
            searching -> searchResults
            else -> listOf(item)
        }
        PlayerActivity.start(context, item, list)
    }

    // Gacha: null = đóng; GACHA_ALL = toàn máy; còn lại = key thư mục
    var gachaScope by rememberSaveable { mutableStateOf<String?>(null) }

    @Composable
    fun VideoItemCell(item: VideoItem, modifier: Modifier) {
        val isSelected = item.id in selectedIds
        val onToggleSelect = {
            selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
        }
        val onClick = {
            if (selectionMode) onToggleSelect() else play(item)
        }
        val onLongClick = {
            if (!selectionMode) {
                selectionMode = true
                selectedIds = setOf(item.id)
            } else {
                onToggleSelect()
            }
        }
        val onMore = {
            if (selectionMode) onToggleSelect() else sheetFor = item
        }
        Box(modifier) {
            if (gridMode) {
                VideoTile(item, onClick, onLongClick, onMore, selectionMode, isSelected)
            } else {
                VideoRow(item, onClick, onLongClick, onMore, selectionMode, isSelected)
            }
        }
    }

    CompositionLocalProvider(LocalRepo provides vm.repo) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, shape = RoundedCornerShape(14.dp)) } },
        ) { padding ->
            val contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            )
            AnimatedContent(
                targetState = current,
                contentKey = { it?.key },
                transitionSpec = {
                    val forward = targetState != null
                    (slideInHorizontally { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
                },
                label = "screen",
            ) { folder ->
                if (folder == null) {
                    // ─────────── Màn hình chính: danh sách thư mục ───────────
                    LazyVerticalGrid(
                        state = homeGrid,
                        columns = if (gridMode) GridCells.Adaptive(140.dp) else GridCells.Fixed(1),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(if (gridMode) 12.dp else 8.dp),
                    ) {
                        val hasVideos = state.videos.isNotEmpty()
                        if (selectionMode && searching) {
                            stickyHeader(key = "selection_header_search") {
                                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp)) {
                                    SelectionHeader(
                                        selectedCount = selectedIds.size,
                                        totalCount = searchResults.size,
                                        onClose = { selectionMode = false; selectedIds = emptySet() },
                                        onSelectAll = {
                                            val allSelected = searchResults.isNotEmpty() && searchResults.all { it.id in selectedIds }
                                            selectedIds = if (allSelected) selectedIds - searchResults.map { it.id }.toSet()
                                            else selectedIds + searchResults.map { it.id }.toSet()
                                        },
                                        onDelete = { confirmDeleteMulti = true },
                                    )
                                }
                            }
                        }
                        if (!selectionMode || !searching) fullLine("header") {
                            if (selectionMode && searching) {
                                SelectionHeader(
                                    selectedCount = selectedIds.size,
                                    totalCount = searchResults.size,
                                    onClose = { selectionMode = false; selectedIds = emptySet() },
                                    onSelectAll = {
                                        val allSelected = searchResults.isNotEmpty() && searchResults.all { it.id in selectedIds }
                                        selectedIds = if (allSelected) selectedIds - searchResults.map { it.id }.toSet()
                                        else selectedIds + searchResults.map { it.id }.toSet()
                                    },
                                    onDelete = { confirmDeleteMulti = true },
                                )
                            } else if (searching) {
                                Header(
                                    title = "Kho Video",
                                    subtitle = "Kết quả tìm kiếm",
                                    showActions = true,
                                    sortOptions = SortMode.entries.map { it.label }, selectedSort = sort.ordinal,
                                    onSort = { sort = SortMode.entries[it] },
                                    gridMode = gridMode,
                                    onToggle = {
                                        gridMode = !gridMode
                                        prefs.edit().putBoolean("video_grid_mode", gridMode).apply()
                                    },
                                    showWidgets = showWidgets,
                                    onSelectMode = if (searchResults.isNotEmpty()) {
                                        { selectionMode = true }
                                    } else null,
                                )
                            } else {
                                Header(
                                    title = "Kho Video",
                                    subtitle = if (hasVideos) "${folders.size} thư mục · ${visible.size} video" else "Tất cả video trong máy",
                                    showActions = hasVideos,
                                    sortOptions = FolderSort.entries.map { it.label }, selectedSort = folderSort.ordinal,
                                    onSort = { folderSort = FolderSort.entries[it] },
                                    gridMode = gridMode,
                                    onToggle = {
                                        gridMode = !gridMode
                                        prefs.edit().putBoolean("video_grid_mode", gridMode).apply()
                                    },
                                    showWidgets = showWidgets,
                                    onToggleWidgets = if (hasVideos) {
                                        {
                                            showWidgets = !showWidgets
                                            prefs.edit().putBoolean("show_widgets", showWidgets).apply()
                                        }
                                    } else null,
                                )
                            }
                        }
                        if (access == Access.NONE) {
                            fullLine("perm") { PermissionPrompt(onAllow = requestAccess) }
                            return@LazyVerticalGrid
                        }
                        if (!searching && showWidgets) fullLine("streak") { StreakCard(streak) { streakOpen = true } }
                        if (!searching && resumeItem != null && resumePosition > 0L) {
                            fullLine("continue") {
                                ContinueWatchingCard(resumeItem!!, resumePosition) { play(resumeItem!!) }
                            }
                        }
                        if (access == Access.PARTIAL) {
                            fullLine("partial") { PartialAccessBanner(onAllowAll = requestAccess) }
                        }
                        if (hasVideos) {
                            if (showWidgets) fullLine("stats") { StatsCard(visible, folders.size) }
                            if (!searching && showWidgets) {
                                fullLine("gacha") {
                                    GachaCard(visible, scopeLabel = "toàn bộ máy") { gachaScope = GACHA_ALL }
                                }
                            }
                            fullLine("search") { SearchField(query, "Tìm video trong toàn bộ máy") { query = it } }
                        }
                        when {
                            state.loading -> fullLine("loading") {
                                Box(Modifier.fillMaxWidth().padding(top = 80.dp), Alignment.Center) { CircularProgressIndicator() }
                            }
                            !hasVideos -> fullLine("empty") { EmptyState() }
                            searching -> {
                                fullLine("results") { SearchSummary(searchResults.size, state.videos.size) }
                                if (searchResults.isEmpty()) fullLine("nores") { NoResults(query) }
                                items(searchResults, key = { it.id }) { VideoItemCell(it, Modifier.animateItem()) }
                            }
                            else -> {
                                items(folders, key = { "folder:" + it.key }) { f ->
                                    Box(Modifier.animateItem()) {
                                        if (gridMode) FolderTile(f) { openFolder = f.key } else FolderRow(f) { openFolder = f.key }
                                    }
                                }
                                hiddenFolder?.let { f ->
                                    item(key = "folder:hidden") {
                                        Box(Modifier.animateItem()) {
                                            if (gridMode) FolderTile(f) { openFolder = f.key } else FolderRow(f) { openFolder = f.key }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ─────────── Bên trong một thư mục ───────────
                    LazyVerticalGrid(
                        columns = if (gridMode) GridCells.Adaptive(144.dp) else GridCells.Fixed(1),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(if (gridMode) 10.dp else 8.dp),
                    ) {
                        if (selectionMode) {
                            stickyHeader(key = "selection_header_folder") {
                                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp)) {
                                    SelectionHeader(
                                        selectedCount = selectedIds.size,
                                        totalCount = folderVideos.size,
                                        onClose = { selectionMode = false; selectedIds = emptySet() },
                                        onSelectAll = {
                                            val allSelected = folderVideos.isNotEmpty() && folderVideos.all { it.id in selectedIds }
                                            selectedIds = if (allSelected) selectedIds - folderVideos.map { it.id }.toSet()
                                            else selectedIds + folderVideos.map { it.id }.toSet()
                                        },
                                        onDelete = { confirmDeleteMulti = true },
                                    )
                                }
                            }
                        }
                        if (!selectionMode) fullLine("header") {
                            if (selectionMode) {
                                SelectionHeader(
                                    selectedCount = selectedIds.size,
                                    totalCount = folderVideos.size,
                                    onClose = { selectionMode = false; selectedIds = emptySet() },
                                    onSelectAll = {
                                        val allSelected = folderVideos.isNotEmpty() && folderVideos.all { it.id in selectedIds }
                                        selectedIds = if (allSelected) selectedIds - folderVideos.map { it.id }.toSet()
                                        else selectedIds + folderVideos.map { it.id }.toSet()
                                    },
                                    onDelete = { confirmDeleteMulti = true },
                                )
                            } else {
                                Header(
                                    title = folder.name,
                                    subtitle = "${folder.count} video · ${Formatter.formatShortFileSize(context, folder.size)}",
                                    onBack = closeFolder,
                                    showActions = true,
                                    sortOptions = SortMode.entries.map { it.label }, selectedSort = sort.ordinal,
                                    onSort = { sort = SortMode.entries[it] },
                                    gridMode = gridMode,
                                    onToggle = {
                                        gridMode = !gridMode
                                        prefs.edit().putBoolean("video_grid_mode", gridMode).apply()
                                    },
                                    showWidgets = showWidgets,
                                    onToggleWidgets = {
                                        showWidgets = !showWidgets
                                        prefs.edit().putBoolean("show_widgets", showWidgets).apply()
                                    },
                                    onSelectMode = if (folderVideos.isNotEmpty()) {
                                        { selectionMode = true }
                                    } else null,
                                )
                            }
                        }
                        folder.path?.let { p ->
                            fullLine("path") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(6.dp))
                                    Text(p, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (showWidgets) {
                            fullLine("gacha") {
                                GachaCard(folder.videos, scopeLabel = "thư mục này", compact = true) { gachaScope = folder.key }
                            }
                        }
                        fullLine("search") { SearchField(folderQuery, "Tìm trong ${folder.name}") { folderQuery = it } }
                        if (folderVideos.isEmpty()) fullLine("nores") { NoResults(folderQuery) }
                        items(folderVideos, key = { it.id }) { VideoItemCell(it, Modifier.animateItem()) }
                    }
                }
            }
        }

        if (streakOpen) {
            StreakDialog(streak, onMessages = { streakMsgs = it }, onDismiss = { streakOpen = false })
        }

        gachaScope?.let { scope ->
            val pool = when (scope) {
                GACHA_ALL -> visible
                FILTER_HIDDEN -> hiddenVideos
                else -> visible.filter { it.folder == scope }
            }
            GachaDialog(
                title = if (scope == GACHA_ALL) "Gacha toàn bộ máy" else "Gacha · ${if (scope == FILTER_HIDDEN) "Đã ẩn" else scope}",
                pool = pool,
                onPlay = { play(it, listOf(it)); gachaScope = null },
                onResetRound = { vm.resetWatched(pool) },
                onDismiss = { gachaScope = null },
            )
        }

        sheetFor?.let { item ->
            ActionSheet(
                item = item,
                onDismiss = { sheetFor = null },
                onPlay = { play(item) },
                onRename = { renameFor = item },
                onDelete = { singleDeleteFor = item },
                onToggleHidden = { vm.setHidden(item, !item.hidden) },
                onExport = { export(item) },
                onDeleteLegacy = { deleteLegacyFor = item },
            )
        }
    }

    if (confirmDeleteMulti) {
        AlertDialog(
            onDismissRequest = { confirmDeleteMulti = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Xoá ${selectedIds.size} video đã chọn?") },
            text = { Text("Các video này sẽ bị xoá vĩnh viễn khỏi thiết bị.") },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = state.videos.filter { it.id in selectedIds }
                        deleteVideos(toDelete)
                        confirmDeleteMulti = false
                        selectionMode = false
                        selectedIds = emptySet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteMulti = false }) { Text("Huỷ") } },
        )
    }

    singleDeleteFor?.let { item ->
        AlertDialog(
            onDismissRequest = { singleDeleteFor = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Xoá video này?") },
            text = { Text("\"${item.name}\" sẽ bị xoá vĩnh viễn khỏi thiết bị.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteVideos(listOf(item))
                        singleDeleteFor = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { singleDeleteFor = null }) { Text("Huỷ") } },
        )
    }

    renameFor?.let { item ->
        var text by remember(item) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            icon = { Icon(Icons.Rounded.Edit, null) },
            title = { Text("Đổi tên video") },
            text = {
                Column {
                    OutlinedTextField(
                        text, { text = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Chỉ đổi tên hiển thị trong Kho Video, file gốc giữ nguyên.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { vm.rename(item, text); renameFor = null }, enabled = text.isNotBlank()) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Huỷ") } },
        )
    }

    deleteLegacyFor?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteLegacyFor = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Xoá bản chép này?") },
            text = { Text("\"${item.name}\" là bản chép trong app (từ phiên bản 1.0) và sẽ bị xoá vĩnh viễn.") },
            confirmButton = {
                Button(
                    onClick = { vm.deleteLegacy(item); deleteLegacyFor = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { deleteLegacyFor = null }) { Text("Huỷ") } },
        )
    }
}

internal fun LazyGridScope.fullLine(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SelectionHeader(
    selectedCount: Int,
    totalCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, "Huỷ chọn")
        }
        Text(
            "Đã chọn $selectedCount",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = onSelectAll) {
            Text(if (selectedCount == totalCount && totalCount > 0) "Bỏ chọn" else "Tất cả")
        }
        FilledTonalIconButton(
            onClick = onDelete,
            enabled = selectedCount > 0,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
        ) {
            Icon(Icons.Rounded.DeleteOutline, "Xoá đã chọn")
        }
    }
}

// ───────────────────────────── Phần đầu trang ─────────────────────────────

@Composable
internal fun Header(
    title: String,
    subtitle: String,
    showActions: Boolean,
    sortOptions: List<String>,
    selectedSort: Int,
    onSort: (Int) -> Unit,
    gridMode: Boolean,
    onToggle: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
    /** Nút thêm ở đầu nhóm nút bên phải (vd nút chọn thư mục của mục Ảnh). */
    extraAction: (@Composable () -> Unit)? = null,
    showWidgets: Boolean = true,
    onToggleWidgets: (() -> Unit)? = null,
    onSelectMode: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Quay lại")
            }
        }
        Column(Modifier.weight(1f).offset(x = if (onBack != null) (-8).dp else 0.dp)) {
            Text(
                title,
                style = if (onBack != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (extraAction != null) {
            extraAction()
            if (showActions) Spacer(Modifier.width(8.dp))
        }
        if (showActions) {
            val colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (onSelectMode != null) {
                FilledTonalIconButton(onClick = onSelectMode, colors = colors) {
                    Icon(Icons.Rounded.Checklist, "Chọn video")
                }
                Spacer(Modifier.width(8.dp))
            }
            if (onToggleWidgets != null) {
                FilledTonalIconButton(onClick = onToggleWidgets, colors = colors) {
                    Icon(
                        if (showWidgets) Icons.Rounded.DashboardCustomize else Icons.Rounded.Dashboard,
                        if (showWidgets) "Ẩn tiện ích" else "Hiện tiện ích",
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Box {
                var open by remember { mutableStateOf(false) }
                FilledTonalIconButton(onClick = { open = true }, colors = colors) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, "Sắp xếp")
                }
                DropdownMenu(open, { open = false }, shape = RoundedCornerShape(16.dp)) {
                    Text(
                        "Sắp xếp theo",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    sortOptions.forEachIndexed { i, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onSort(i); open = false },
                            trailingIcon = {
                                if (i == selectedSort) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                            },
                        )
                    }
                }
            }
            if (onToggle != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onToggle, colors = colors) {
                    Crossfade(gridMode, label = "layout") { grid ->
                        Icon(
                            if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                            contentDescription = if (grid) "Xem dạng danh sách" else "Xem dạng lưới",
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PermissionPrompt(
    onAllow: () -> Unit,
    title: String = "Tìm video trong máy",
    body: String = "Cho phép Kho Video truy cập video để tự động tìm tất cả video trong máy, kể cả thẻ nhớ. " +
        "App chỉ đọc để hiển thị và phát, không sửa hay gửi video đi đâu.",
    button: String = "Cho phép truy cập video",
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(132.dp), Alignment.Center) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
            Box(Modifier.size(92.dp).clip(CircleShape).background(BrandGradient), Alignment.Center) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        GradientButton(button, Icons.Rounded.LockOpen, onAllow)
    }
}

@Composable
internal fun PartialAccessBanner(
    onAllowAll: () -> Unit,
    text: String = "Bạn mới cho phép một số video. Kho Video chỉ thấy những video đó.",
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAllowAll) { Text("Cho phép tất cả") }
        }
    }
}

@Composable
private fun StatsCard(videos: List<VideoItem>, folderCount: Int) {
    val context = LocalContext.current
    val totalSize = remember(videos) { videos.sumOf { it.sizeBytes } }
    val totalDuration = remember(videos) { videos.sumOf { it.durationMs } }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(BrandGradient)
            .drawBehind {
                // vòng tròn trang trí
                drawCircle(Color.White.copy(alpha = 0.08f), radius = 70.dp.toPx(), center = Offset(size.width - 30.dp.toPx(), 10.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.06f), radius = 40.dp.toPx(), center = Offset(size.width * 0.45f, size.height + 10.dp.toPx()))
            }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(Icons.Rounded.Folder, folderCount.toString(), "thư mục")
            Stat(Icons.Rounded.VideoLibrary, videos.size.toString(), "video")
            Stat(Icons.Rounded.Schedule, formatTotalDuration(totalDuration), "tổng thời lượng")
            Stat(Icons.Rounded.SdStorage, Formatter.formatShortFileSize(context, totalSize), "dung lượng")
        }
    }
}

@Composable
private fun ContinueWatchingCard(item: VideoItem, positionMs: Long, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayCircle, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Xem tiếp", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Tiếp tục từ ${formatDuration(positionMs)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, value: String, label: String) {
    Column {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(value, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun SearchField(query: String, placeholder: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Xoá tìm kiếm", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SearchSummary(found: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(Icons.Rounded.ManageSearch, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            if (found > 0) "Tìm thấy $found / $total video trong máy" else "Không tìm thấy trong $total video của máy",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderTile(folder: FolderInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val hidden = folder.key == FILTER_HIDDEN
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Thumbnail(folder.cover, Modifier.fillMaxSize())
            if (hidden) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))),
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f)),
                Alignment.Center,
            ) {
                Icon(
                    if (hidden) Icons.Rounded.VisibilityOff else folderIcon(folder.name),
                    null, tint = Color.White, modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "${folder.count}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            "${folder.count} video · ${Formatter.formatShortFileSize(context, folder.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun FolderRow(folder: FolderInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val hidden = folder.key == FILTER_HIDDEN
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.small)) {
            Thumbnail(folder.cover, Modifier.fillMaxSize())
            if (hidden) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        Spacer(Modifier.width(14.dp))
        Icon(
            if (hidden) Icons.Rounded.VisibilityOff else folderIcon(folder.name),
            null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${folder.count} video · ${Formatter.formatShortFileSize(context, folder.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun folderIcon(name: String): ImageVector = when (name.lowercase()) {
    "camera" -> Icons.Rounded.PhotoCamera
    "download", "downloads" -> Icons.Rounded.Download
    "screenrecorder", "screen recordings", "screenrecord" -> Icons.Rounded.Videocam
    "movies" -> Icons.Rounded.Movie
    LEGACY_FOLDER.lowercase() -> Icons.Rounded.Inventory2
    else -> Icons.Rounded.Folder
}

// ───────────────────────────── Thẻ video ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoTile(
    item: VideoItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.9f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Thumbnail(item, Modifier.fillMaxSize())
        // lớp phủ tối để chữ dễ đọc
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        0.3f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.85f),
                    )
                ),
        )
        Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.durationMs > 0) DurationBadge(item.durationMs)
            if (item.hidden) HiddenBadge()
            if (item.watched) WatchedBadge()
        }
        if (selectionMode) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f)),
                Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = onMore),
                Alignment.Center,
            ) {
                Icon(Icons.Rounded.MoreHoriz, "Tuỳ chọn", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
                Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(item.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                metaLine(context, item),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    item: VideoItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(112.dp)
                .aspectRatio(16f / 10f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Thumbnail(item, Modifier.fillMaxSize())
            if (item.durationMs > 0) DurationBadge(item.durationMs, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(folderIcon(item.folder), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(
                    item.folder,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metaLine(context, item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.hidden) { Spacer(Modifier.width(6.dp)); HiddenBadge() }
                if (item.watched) { Spacer(Modifier.width(6.dp)); WatchedBadge() }
            }
        }
        if (selectionMode) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
        } else {
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Tuỳ chọn") }
        }
    }
}

@Composable
internal fun DurationBadge(ms: Long, modifier: Modifier = Modifier) {
    Text(
        formatDuration(ms),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun HiddenBadge() {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(12.dp), tint = Color.White)
        Spacer(Modifier.width(3.dp))
        Text("Đã ẩn", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

private val thumbCache = LruCache<String, ImageBitmap>(120)

@Composable
internal fun Thumbnail(item: VideoItem, modifier: Modifier) {
    val repo = LocalRepo.current
    val key = "${item.id}:${item.sizeBytes}"
    val bmp by produceState(thumbCache.get(key), key) {
        // Khi khung này chuyển sang video khác (vd hộp Gacha, ảnh bìa thư mục), phải bỏ ảnh cũ
        val cached = thumbCache.get(key)
        if (cached != null) {
            value = cached
        } else {
            value = null
            value = repo.thumbnail(item)?.asImageBitmap()?.also { thumbCache.put(key, it) }
        }
    }
    Crossfade(bmp, modifier, label = "thumb") { b ->
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(BrandGradient), Alignment.Center) {
                Icon(Icons.Rounded.Movie, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
            }
        }
    }
}

// ───────────────────────────── Trạng thái trống ─────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(132.dp), Alignment.Center) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
            Box(Modifier.size(92.dp).clip(CircleShape).background(BrandGradient), Alignment.Center) {
                Icon(Icons.Rounded.VideoLibrary, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Chưa có video nào", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Máy chưa có video. Khi bạn quay hoặc tải video về, chúng sẽ tự xuất hiện ở đây.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoResults(query: String) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            if (query.isBlank()) "Không có video nào ở đây" else "Không có video nào khớp \"$query\"",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun GradientButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent) {
        Row(
            Modifier.background(BrandGradient).padding(horizontal = 22.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ───────────────────────────── Bảng thao tác ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(
    item: VideoItem,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleHidden: () -> Unit,
    onExport: () -> Unit,
    onDeleteLegacy: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(96.dp).aspectRatio(16f / 10f).clip(MaterialTheme.shapes.small)) {
                    Thumbnail(item, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            if (item.durationMs > 0) append(formatDuration(item.durationMs)).append(" · ")
                            append(metaLine(context, item))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        item.path?.let { "📁 $it" } ?: "📁 ${item.folder}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            val act = { f: () -> Unit -> { onDismiss(); f() } }
            SheetAction(Icons.Rounded.PlayArrow, "Phát video", onClick = act(onPlay))
            SheetAction(Icons.Rounded.Edit, "Đổi tên", "Chỉ đổi tên trong app, file gốc giữ nguyên", onClick = act(onRename))
            if (item.localFile != null) {
                SheetAction(Icons.Rounded.SaveAlt, "Lưu ra Thư viện", "Bản sao trong Movies/KhoVideo", onClick = act(onExport))
                SheetAction(Icons.Rounded.DeleteOutline, "Xoá bản chép", "Bản chép trong app từ phiên bản 1.0", danger = true, onClick = act(onDeleteLegacy))
            } else {
                SheetAction(Icons.Rounded.DeleteOutline, "Xoá video", "Xoá vĩnh viễn khỏi thiết bị", danger = true, onClick = act(onDelete))
            }
            if (item.hidden) {
                SheetAction(Icons.Rounded.Visibility, "Bỏ ẩn", "Hiện lại video trong danh sách", onClick = act(onToggleHidden))
            } else {
                SheetAction(Icons.Rounded.VisibilityOff, "Ẩn khỏi danh sách", "Video trong máy vẫn giữ nguyên", onClick = act(onToggleHidden))
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────────────── Định dạng ─────────────────────────────

private fun metaLine(context: Context, item: VideoItem) =
    "${Formatter.formatShortFileSize(context, item.sizeBytes)} · ${DateFormat.getDateFormat(context).format(item.addedAt)}"

internal fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun formatTotalDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}g ${m}p"
        totalMin > 0 -> "$m phút"
        else -> "${ms / 1000} giây"
    }
}
