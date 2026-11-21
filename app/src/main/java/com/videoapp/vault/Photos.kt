package com.videoapp.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LocalPhotoRepo = staticCompositionLocalOf<PhotoRepository> { error("no photo repo") }

/** Bộ nhớ đệm ảnh thu nhỏ, giới hạn theo dung lượng (~40 MB) để lướt nhanh mà không tốn RAM. */
private val photoThumbCache = object : LruCache<String, ImageBitmap>(40 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
}

private val PhotoItem.cacheKey get() = "$id:$sizeBytes"

private enum class AlbumSort(val label: String) { RECENT("Mới cập nhật"), NAME("Tên A–Z"), COUNT("Nhiều ảnh nhất"), SIZE("Dung lượng") }
private enum class PhotoSort(val label: String) { NEWEST("Mới nhất"), OLDEST("Cũ nhất"), NAME("Tên A–Z"), SIZE("Dung lượng") }

private class Album(val key: String, val name: String, val photos: List<PhotoItem>) {
    val count get() = photos.size
    val size = photos.sumOf { it.sizeBytes }
    val cover = photos.maxBy { it.takenAt }
    val latest = cover.takenAt
    val path = photos.firstNotNullOfOrNull { it.path }
}

private fun List<PhotoItem>.sortedBy(mode: PhotoSort) = when (mode) {
    PhotoSort.NEWEST -> sortedByDescending { it.takenAt }
    PhotoSort.OLDEST -> sortedBy { it.takenAt }
    PhotoSort.NAME -> sortedBy { it.name.lowercase() }
    PhotoSort.SIZE -> sortedByDescending { it.sizeBytes }
}

/** Danh sách ảnh đang xem trong trình xem ảnh + vị trí bắt đầu. */
private class ViewerRequest(val photos: List<PhotoItem>, val start: Int)

// ───────────────────────────── Mục Ảnh ─────────────────────────────

@Composable
fun PhotoScreen(vm: PhotoViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kho_video", Context.MODE_PRIVATE) }
    var access by remember { mutableStateOf(currentAccess(context, MediaKind.PHOTO)) }
    var query by rememberSaveable { mutableStateOf("") }
    var albumQuery by rememberSaveable { mutableStateOf("") }
    var albumSort by rememberSaveable { mutableStateOf(prefs.getString("album_sort", null)?.let { v -> AlbumSort.entries.firstOrNull { it.name == v } } ?: AlbumSort.RECENT) }
    var photoSort by rememberSaveable { mutableStateOf(prefs.getString("photo_sort", null)?.let { v -> PhotoSort.entries.firstOrNull { it.name == v } } ?: PhotoSort.NEWEST) }
    var openAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("photo_grid_mode", true)) }
    LaunchedEffect(albumSort) { prefs.edit().putString("album_sort", albumSort.name).apply() }
    LaunchedEffect(photoSort) { prefs.edit().putString("photo_sort", photoSort.name).apply() }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val homeGrid = rememberLazyGridState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean(MediaKind.PHOTO.askedKey, true).apply()
        access = currentAccess(context, MediaKind.PHOTO)
        vm.refresh()
    }
    val requestAccess = {
        val perms = requiredPermissions(MediaKind.PHOTO)
        val blocked = prefs.getBoolean(MediaKind.PHOTO.askedKey, false) && access == Access.NONE &&
            !(context as Activity).shouldShowRequestPermissionRationale(perms[0])
        if (blocked) openAppSettings(context) else permissionLauncher.launch(perms)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = currentAccess(context, MediaKind.PHOTO)
        vm.refresh()
    }

    val photos = state.photos
    val albums = remember(photos, albumSort) {
        val groups = photos.groupBy { it.dirKey }
        // Hai thư mục trùng tên (vd Camera trong máy và Camera trên thẻ nhớ) → ghi thêm nơi lưu cho dễ phân biệt
        val dupNames = groups.values.groupBy { it.first().folder }.filterValues { it.size > 1 }.keys
        groups.map { (key, list) ->
            val first = list.first()
            val name = if (first.folder in dupNames) "${first.folder} · ${storageLabel(context, first.volume)}" else first.folder
            Album(key, name, list)
        }.let { list ->
            when (albumSort) {
                AlbumSort.RECENT -> list.sortedByDescending { it.latest }
                AlbumSort.NAME -> list.sortedBy { it.name.lowercase() }
                AlbumSort.COUNT -> list.sortedByDescending { it.count }
                AlbumSort.SIZE -> list.sortedByDescending { it.size }
            }
        }
    }
    val current = openAlbum?.let { key -> albums.firstOrNull { it.key == key } }
    LaunchedEffect(openAlbum, current, state.loading) {
        if (openAlbum != null && current == null && !state.loading) openAlbum = null
    }
    val closeAlbum = { openAlbum = null; albumQuery = "" }
    BackHandler(enabled = openAlbum != null) { closeAlbum() }

    val searching = query.isNotBlank()
    val searchResults = remember(photos, query, photoSort) {
        if (query.isBlank()) emptyList() else photos.filter { it.matches(query) }.sortedBy(photoSort)
    }
    val albumPhotos = remember(current, albumQuery, photoSort) {
        current?.photos.orEmpty().filter { albumQuery.isBlank() || it.matches(albumQuery) }.sortedBy(photoSort)
    }

    CompositionLocalProvider(LocalPhotoRepo provides vm.repo) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
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
                label = "photoScreen",
            ) { album ->
                if (album == null) {
                    // ─────────── Danh sách album ───────────
                    LazyVerticalGrid(
                        state = homeGrid,
                        columns = when {
                            searching -> GridCells.Adaptive(104.dp)
                            gridMode -> GridCells.Adaptive(150.dp)
                            else -> GridCells.Fixed(1)
                        },
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(if (searching) 4.dp else 14.dp),
                        verticalArrangement = Arrangement.spacedBy(if (searching) 4.dp else if (gridMode) 18.dp else 10.dp),
                    ) {
                        val has = photos.isNotEmpty()
                        val pickButton: @Composable () -> Unit = {
                            FilledTonalIconButton(
                                onClick = { picking = true },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            ) { Icon(Icons.Rounded.CreateNewFolder, "Chọn thư mục") }
                        }
                        fullLine("header") {
                            if (searching) {
                                Header(
                                    title = "Ảnh", subtitle = "Kết quả tìm kiếm", showActions = true,
                                    sortOptions = PhotoSort.entries.map { it.label }, selectedSort = photoSort.ordinal,
                                    onSort = { photoSort = PhotoSort.entries[it] },
                                    gridMode = gridMode, onToggle = null,
                                )
                            } else {
                                Header(
                                    title = "Ảnh",
                                    subtitle = if (has) "${albums.size} thư mục · ${photos.size} ảnh" else "Các thư mục ảnh bạn chọn",
                                    showActions = has,
                                    sortOptions = AlbumSort.entries.map { it.label }, selectedSort = albumSort.ordinal,
                                    onSort = { albumSort = AlbumSort.entries[it] },
                                    gridMode = gridMode, onToggle = { gridMode = !gridMode; prefs.edit().putBoolean("photo_grid_mode", gridMode).apply() },
                                    extraAction = if (access != Access.NONE && state.selected.isNotEmpty()) pickButton else null,
                                )
                            }
                        }
                        if (access == Access.NONE) {
                            fullLine("perm") {
                                PermissionPrompt(
                                    onAllow = requestAccess,
                                    title = "Xem ảnh trong máy",
                                    body = "Cho phép Kho Video truy cập ảnh để tự động tìm tất cả ảnh trong máy, kể cả thẻ nhớ. " +
                                        "App chỉ đọc để hiển thị, không sửa hay gửi ảnh đi đâu.",
                                    button = "Cho phép truy cập ảnh",
                                )
                            }
                            return@LazyVerticalGrid
                        }
                        if (access == Access.PARTIAL) {
                            fullLine("partial") {
                                PartialAccessBanner(requestAccess, "Bạn mới cho phép một số ảnh. Kho Video chỉ thấy những ảnh đó.")
                            }
                        }
                        if (!state.loading && state.missingDirs.isNotEmpty() && !searching) {
                            fullLine("missing") { MissingBanner(state.missingDirs.size, onManage = { picking = true }) }
                        }
                        if (has) {
                            fullLine("search") {
                                Box(Modifier.padding(bottom = if (searching) 8.dp else 0.dp)) {
                                    SearchField(query, "Tìm ảnh theo tên") { query = it }
                                }
                            }
                        }
                        when {
                            state.loading -> fullLine("loading") {
                                Box(Modifier.fillMaxWidth().padding(top = 80.dp), Alignment.Center) { CircularProgressIndicator() }
                            }
                            state.selected.isEmpty() -> fullLine("empty") {
                                PhotoEmpty(
                                    "Chọn thư mục ảnh",
                                    "Mục Ảnh chỉ hiện những thư mục bạn chọn (vd các thư mục trên thẻ nhớ). " +
                                        "Rút thẻ nhớ ra thì các thư mục đó tạm ẩn, cắm lại sẽ tự hiện lại.",
                                    action = "Chọn thư mục" to { picking = true },
                                )
                            }
                            !has -> fullLine("empty") {
                                PhotoEmpty(
                                    "Thư mục chưa kết nối",
                                    "Các thư mục bạn chọn hiện không có trong máy. Cắm thẻ nhớ vào, chúng sẽ tự hiện lại ở đây.",
                                    action = "Chọn thư mục khác" to { picking = true },
                                )
                            }
                            searching -> {
                                fullLine("summary") {
                                    Text(
                                        if (searchResults.isNotEmpty()) "Tìm thấy ${searchResults.size} / ${photos.size} ảnh"
                                        else "Không có ảnh nào khớp \"$query\"",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                                    )
                                }
                                items(searchResults, key = { it.id }) { p ->
                                    PhotoCell(p) { viewer = ViewerRequest(searchResults, searchResults.indexOf(p)) }
                                }
                            }
                            else -> items(albums, key = { "album:" + it.key }) { a ->
                                Box(Modifier.animateItem()) {
                                    if (gridMode) AlbumTile(a) { openAlbum = a.key } else AlbumRow(a) { openAlbum = a.key }
                                }
                            }
                        }
                    }
                } else {
                    // ─────────── Bên trong một album ───────────
                    val indexOf = remember(albumPhotos) { albumPhotos.withIndex().associate { it.value.id to it.index } }
                    val byDate = (photoSort == PhotoSort.NEWEST || photoSort == PhotoSort.OLDEST)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        fullLine("header") {
                            Header(
                                title = album.name,
                                subtitle = "${album.count} ảnh · ${Formatter.formatShortFileSize(context, album.size)}",
                                onBack = closeAlbum,
                                showActions = true,
                                sortOptions = PhotoSort.entries.map { it.label }, selectedSort = photoSort.ordinal,
                                onSort = { photoSort = PhotoSort.entries[it] },
                                gridMode = true, onToggle = null,
                            )
                        }
                        album.path?.let { p ->
                            fullLine("path") {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(6.dp))
                                    Text(p, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        fullLine("search") {
                            Box(Modifier.padding(bottom = 6.dp)) {
                                SearchField(albumQuery, "Tìm trong ${album.name}") { albumQuery = it }
                            }
                        }
                        if (albumPhotos.isEmpty()) fullLine("nores") {
                            Text(
                                "Không có ảnh nào khớp \"$albumQuery\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            )
                        }
                        val open = { p: PhotoItem -> viewer = ViewerRequest(albumPhotos, indexOf[p.id] ?: 0) }
                        if (byDate) {
                            // Chia theo ngày chụp, giống thư viện ảnh của điện thoại
                            albumPhotos.groupBy { it.day }.forEach { (day, list) ->
                                fullLine("day:$day") { DateHeader(day, list.size) }
                                items(list, key = { it.id }) { p -> PhotoCell(p) { open(p) } }
                            }
                        } else {
                            items(albumPhotos, key = { it.id }) { p -> PhotoCell(p) { open(p) } }
                        }
                    }
                }
            }
        }

        viewer?.let { req ->
            PhotoViewer(req.photos, req.start, onDismiss = { viewer = null })
        }
        if (picking) {
            FolderPicker(state, onToggle = vm::setSelected, onDismiss = { picking = false })
        }
    }
}

// ───────────────────────────── Ô album / ô ảnh ─────────────────────────────

private fun albumIcon(name: String): ImageVector = when (name.lowercase()) {
    "camera" -> Icons.Rounded.PhotoCamera
    "screenshots", "screenshot", "ảnh chụp màn hình" -> Icons.Rounded.Screenshot
    "download", "downloads" -> Icons.Rounded.Download
    "pictures" -> Icons.Rounded.Image
    else -> Icons.Rounded.PhotoAlbum
}

@Composable
private fun AlbumTile(album: Album, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            PhotoThumb(album.cover, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            Box(
                Modifier.align(Alignment.TopStart).padding(10.dp).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                Alignment.Center,
            ) {
                Icon(albumIcon(album.cover.folder), null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Text(
                "${album.count}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            "${album.count} ảnh · ${Formatter.formatShortFileSize(context, album.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.small)) { PhotoThumb(album.cover, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(14.dp))
        Icon(albumIcon(album.cover.folder), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${album.count} ảnh · ${Formatter.formatShortFileSize(context, album.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhotoCell(item: PhotoItem, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        PhotoThumb(item, Modifier.fillMaxSize())
        if (item.mime == "image/gif") {
            Text(
                "GIF",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PhotoThumb(item: PhotoItem, modifier: Modifier) {
    val repo = LocalPhotoRepo.current
    val key = item.cacheKey
    val bmp by produceState(photoThumbCache.get(key), key) {
        val cached = photoThumbCache.get(key)
        if (cached != null) {
            value = cached
        } else {
            value = null
            value = repo.thumbnail(item)?.asImageBitmap()?.also { photoThumbCache.put(key, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

private val PhotoItem.day: LocalDate
    get() = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()).toLocalDate()

private fun DayOfWeek.vi() = when (this) {
    DayOfWeek.MONDAY -> "Thứ Hai"
    DayOfWeek.TUESDAY -> "Thứ Ba"
    DayOfWeek.WEDNESDAY -> "Thứ Tư"
    DayOfWeek.THURSDAY -> "Thứ Năm"
    DayOfWeek.FRIDAY -> "Thứ Sáu"
    DayOfWeek.SATURDAY -> "Thứ Bảy"
    DayOfWeek.SUNDAY -> "Chủ Nhật"
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when {
        d == today -> "Hôm nay"
        d == today.minusDays(1) -> "Hôm qua"
        d.year == today.year -> "${d.dayOfWeek.vi()}, ${d.dayOfMonth} tháng ${d.monthValue}"
        else -> "${d.dayOfMonth} tháng ${d.monthValue}, ${d.year}"
    }
}

@Composable
private fun DateHeader(day: LocalDate, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp, start = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(dayLabel(day), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text("$count ảnh", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhotoEmpty(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 56.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(132.dp), Alignment.Center) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
            Box(Modifier.size(92.dp).clip(CircleShape).background(BrandGradient), Alignment.Center) {
                Icon(Icons.Rounded.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(28.dp))
            GradientButton(label, Icons.Rounded.CreateNewFolder, onClick)
        }
    }
}

@Composable
private fun MissingBanner(count: Int, onManage: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SdCardAlert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                "$count thư mục đang không kết nối (thẻ nhớ chưa cắm?). Cắm lại sẽ tự hiện.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManage) { Text("Quản lý") }
        }
    }
}

// ───────────────────────────── Chọn thư mục ─────────────────────────────

private class DirInfo(val key: String, val name: String, val path: String, val volume: String, val photos: List<PhotoItem>?) {
    val count get() = photos?.size ?: 0
    val cover get() = photos?.maxBy { it.takenAt }
}

/**
 * Chọn những thư mục được hiện trong mục Ảnh. Thư mục trên thẻ nhớ được xếp lên đầu.
 * Thư mục đã chọn nhưng đang không kết nối vẫn được giữ và hiện ở cuối, để bỏ chọn nếu muốn.
 */
@Composable
private fun FolderPicker(state: PhotoState, onToggle: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    val dirs = remember(state.all) {
        state.all.groupBy { it.dirKey }.map { (key, list) ->
            val f = list.first()
            DirInfo(key, f.folder, dirKeyPath(key), f.volume, list)
        }
    }
    val missing = state.missingDirs.map { DirInfo(it, dirKeyName(it), dirKeyPath(it), dirKeyVolume(it), null) }
    val shown = (dirs + missing).filter { filter.isBlank() || "${it.name} ${it.path}".toSearchKey().contains(filter.trim().toSearchKey()) }
    // Nhóm: thẻ nhớ (từng thẻ) trước, bộ nhớ trong sau, thư mục đang không kết nối cuối cùng
    val sections = shown.groupBy { if (it.photos == null) "\u0000missing" else it.volume }
        .toList()
        .sortedBy { (vol, _) -> when (vol) { "\u0000missing" -> 2; PRIMARY_VOLUME -> 1; else -> 0 } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight(0.9f),
        ) {
            Column(Modifier.padding(top = 16.dp)) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Chọn thư mục ảnh", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Đã chọn ${state.selected.size} thư mục",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Đóng") }
                }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    SearchField(filter, "Tìm thư mục") { filter = it }
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp),
                ) {
                    if (sections.isEmpty()) item {
                        Text(
                            if (filter.isBlank()) "Không tìm thấy thư mục ảnh nào trong máy" else "Không có thư mục nào khớp",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    sections.forEach { (vol, list) ->
                        item(key = "sec:$vol") {
                            val missingSec = vol == "\u0000missing"
                            Row(Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when {
                                        missingSec -> Icons.Rounded.SdCardAlert
                                        vol == PRIMARY_VOLUME -> Icons.Rounded.PhoneAndroid
                                        else -> Icons.Rounded.SdCard
                                    },
                                    null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (missingSec) "Đang không kết nối" else storageLabel(context, vol),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (missingSec) {
                                Text(
                                    "Vẫn được giữ lại, sẽ tự hiện khi cắm thẻ nhớ. Bỏ chọn nếu không cần nữa.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                                )
                            }
                        }
                        // Thư mục đã chọn lên trước, rồi tới thư mục nhiều ảnh
                        val sorted = list.sortedWith(compareByDescending<DirInfo> { it.key in state.selected }.thenByDescending { it.count })
                        items(sorted.size, key = { "dir:" + sorted[it].key }) { i ->
                            val d = sorted[i]
                            val checked = d.key in state.selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { onToggle(d.key, !checked) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
                                    val cover = d.cover
                                    if (cover != null) PhotoThumb(cover, Modifier.fillMaxSize())
                                    else Icon(Icons.Rounded.SdCardAlert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        (if (d.photos != null) "${d.count} ảnh · " else "") + d.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Checkbox(checked = checked, onCheckedChange = { onToggle(d.key, it) })
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.CenterEnd) {
                    Button(onClick = onDismiss, shape = CircleShape) { Text("Xong") }
                }
            }
        }
    }
}

// ───────────────────────────── Trình xem ảnh ─────────────────────────────

/** Xem ảnh toàn màn hình: vuốt ngang để chuyển ảnh, chụm 2 ngón / chạm 2 lần để phóng to, chạm 1 lần để ẩn/hiện thanh công cụ. */
@Composable
private fun PhotoViewer(photos: List<PhotoItem>, start: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val context = LocalContext.current
        val pager = rememberPagerState(initialPage = start.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
        var chrome by remember { mutableStateOf(true) }
        var infoFor by remember { mutableStateOf<PhotoItem?>(null) }
        val currentPhoto = photos.getOrNull(pager.currentPage)
        var locked by rememberSaveable { mutableStateOf(false) }
        var showUnlock by remember { mutableStateOf(false) }
        var autoPlay by rememberSaveable { mutableStateOf(false) }

        // Tự chuyển ảnh mỗi 3 giây nếu bật và không bị khoá
        LaunchedEffect(autoPlay, locked, pager.currentPage) {
            if (autoPlay && !locked && photos.size > 1) {
                delay(3000L)
                val next = (pager.currentPage + 1) % photos.size
                pager.animateScrollToPage(next)
            }
        }
        LaunchedEffect(showUnlock) {
            if (showUnlock) { delay(2500); showUnlock = false }
        }

        // Ẩn thanh trạng thái khi ẩn thanh công cụ, để xem ảnh trọn màn hình
        val view = LocalView.current
        LaunchedEffect(chrome, locked) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (chrome && !locked) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !locked, // Khi khoá ảnh: không cho vuốt sang ảnh khác
                beyondViewportPageCount = 1,
                key = { photos[it].id },
            ) { page ->
                FullPhoto(photos[page], onTap = {
                    if (locked) showUnlock = !showUnlock
                    else chrome = !chrome
                })
            }

            AnimatedVisibility(chrome && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Đóng", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            currentPhoto?.name.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pager.currentPage + 1} / ${photos.size}" + (currentPhoto?.let { " · " + dayLabel(it.day) } ?: ""),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (autoPlay) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Tự chuyển 3s", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(chrome && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .navigationBarsPadding()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ViewerAction(
                        if (autoPlay) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                        if (autoPlay) "Dừng tự chuyển" else "Tự chuyển ảnh"
                    ) {
                        autoPlay = !autoPlay
                    }
                    ViewerAction(Icons.Rounded.Lock, "Khoá ảnh") {
                        locked = true
                        showUnlock = true
                        autoPlay = false
                    }
                    ViewerAction(Icons.AutoMirrored.Rounded.OpenInNew, "Mở bằng...") { currentPhoto?.let { openPhotoWith(context, it) } }
                    ViewerAction(Icons.Rounded.Info, "Thông tin") { infoFor = currentPhoto }
                }
            }

            // Đang khoá ảnh: hiển thị nút mở khoá khi chạm vào ảnh
            AnimatedVisibility(
                locked && showUnlock,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
            ) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clickable { locked = false; showUnlock = false; chrome = true }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.LockOpen, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Chạm để mở khoá ảnh", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        infoFor?.let { PhotoInfo(it) { infoFor = null } }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** Một ảnh trong trình xem: hiện ảnh thu nhỏ trước, rồi thay bằng ảnh nét khi tải xong. */
@Composable
private fun FullPhoto(item: PhotoItem, onTap: () -> Unit) {
    val repo = LocalPhotoRepo.current
    val preview = remember(item.cacheKey) { photoThumbCache.get(item.cacheKey) }
    var failed by remember(item.id) { mutableStateOf(false) }
    val full by produceState<ImageBitmap?>(null, item.id) {
        value = repo.full(item)?.asImageBitmap()
        if (value == null) failed = true
    }
    val shown = full ?: preview
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        if (shown != null) {
            ZoomableImage(shown, item.name, onTap)
        }
        when {
            failed && preview == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.BrokenImage, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Không mở được ảnh này", color = Color.White.copy(alpha = 0.8f))
            }
            // Vẫn xem được bản thu nhỏ, chỉ báo nhẹ là không tải được ảnh gốc
            failed -> Text(
                "Không tải được ảnh gốc — đang hiện bản thu nhỏ",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            full == null -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(32.dp))
        }
    }
}

/** Ảnh có thể phóng to: chụm 2 ngón, chạm 2 lần (phóng 2,5x / thu về), kéo để di chuyển khi đang phóng to. */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, description: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            val s = 2.5f
                            val center = Offset(box.width / 2f, box.height / 2f)
                            scale = s
                            offset = clamp((center - tap) * (s - 1f), s)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // Chỉ xử lý khi chụm 2 ngón hoặc đang phóng to; còn lại để trang trượt sang ảnh khác
                        if (fingers >= 2 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (newScale <= 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            bitmap,
            description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@Composable
private fun PhotoInfo(item: PhotoItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm, dd/MM/yyyy") }
    val rows = listOfNotNull(
        "Tên tệp" to item.name,
        "Ngày chụp" to Instant.ofEpochMilli(item.takenAt).atZone(ZoneId.systemDefault()).format(fmt),
        ("Kích thước" to "${item.width} × ${item.height} (${"%.1f".format(item.width * item.height / 1_000_000f)} MP)")
            .takeIf { item.width > 0 && item.height > 0 },
        "Dung lượng" to Formatter.formatShortFileSize(context, item.sizeBytes),
        "Album" to item.folder,
        item.path?.let { "Thư mục" to it },
        item.mime?.let { "Định dạng" to it.substringAfter('/').uppercase() },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, null) },
        title = { Text("Thông tin ảnh") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

/** Mở ảnh bằng app khác (vd để chỉnh sửa trong Thư viện ảnh của máy). */
private fun openPhotoWith(context: Context, item: PhotoItem) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(item.uri, item.mime ?: "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(view, "Mở ảnh bằng")) }
}
