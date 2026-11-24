package com.videoapp.vault

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/** Danh sách video đang xem (thư mục / kết quả tìm kiếm), để nút "video trước / sau" biết chuyển tới đâu. */
object PlayQueue {
    var items: List<VideoItem> = emptyList()
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player by mutableStateOf<ExoPlayer?>(null)
    private lateinit var playerView: PlayerView
    private var position = 0L
    private var playWhenReady = true

    private var queue: List<VideoItem> = emptyList()
    private var index by mutableIntStateOf(0)
    private var resumeFlash by mutableStateOf<String?>(null)

    private fun currentItemKey(): String? =
        queue.getOrNull(index)?.id?.ifEmpty { null } ?: queue.getOrNull(index)?.uri?.toString()

    private fun getResumePosition(key: String?): Long {
        if (key == null) return 0L
        val prefs = getSharedPreferences("kho_video", Context.MODE_PRIVATE)
        return prefs.getLong("playback_pos_$key", 0L)
    }

    private fun savePlaybackPosition(key: String?, pos: Long, dur: Long) {
        if (key == null) return
        val prefs = getSharedPreferences("kho_video", Context.MODE_PRIVATE)
        // Lưu nếu đã xem qua 2 giây và chưa đến sát cuối (còn hơn 3s)
        if (pos > 2_000L && dur > 5_000L && pos < dur - 3_000L) {
            prefs.edit().putLong("playback_pos_$key", pos).putString("last_playback_id", key).apply()
        } else if (pos >= dur - 3_000L || dur <= 5_000L) {
            prefs.edit().remove("playback_pos_$key").apply()
        }
    }

    // Trạng thái hiển thị cho giao diện
    private var isPlaying by mutableStateOf(false)
    private var buffering by mutableStateOf(false)
    private var ended by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    /** Thông báo chuỗi lửa chờ hiện ở phía trên màn hình. */
    private val banners = mutableStateListOf<String>()

    /** Thời điểm (elapsedRealtime) bắt đầu đoạn đang phát chưa cộng vào chuỗi; 0 = không phát. */
    private var playingSince = 0L
    // ── Ảnh xem trước khi kéo thanh tua ──
    /** Một luồng riêng để đọc khung hình (MediaMetadataRetriever không dùng được từ nhiều luồng cùng lúc). */
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private val previewDispatcher = previewExecutor.asCoroutineDispatcher()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverFor: Uri? = null
    /** Khung hình đã lấy của video hiện tại, theo từng giây. */
    private val frameCache = LruCache<Long, Bitmap>(60)

    /** Lấy khung hình gần thời điểm [ms] của video đang phát (ảnh nhỏ, nhanh). */
    private suspend fun frameAt(uri: Uri, ms: Long): Bitmap? = withContext(previewDispatcher) {
        if (retrieverFor != uri) {
            retriever?.release()
            frameCache.evictAll()
            retriever = runCatching { MediaMetadataRetriever().apply { setDataSource(this@PlayerActivity, uri) } }.getOrNull()
            retrieverFor = uri
        }
        frameCache.get(ms) ?: runCatching {
            val r = retriever ?: return@runCatching null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW_PX, PREVIEW_PX)
                    ?: r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, PREVIEW_PX, PREVIEW_PX)
                    ?: r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            } else {
                r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { b ->
                    val s = PREVIEW_PX.toFloat() / maxOf(b.width, b.height)
                    if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
                }
            }
        }.getOrNull()?.also { frameCache.put(ms, it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewExecutor.execute { retriever?.release(); retriever = null }
        previewExecutor.shutdown()
    }

    /** Trước khi giữ để tua nhanh, video có đang phát không (để thả tay ra thì trả lại như cũ). */
    private var wasPlayingBeforeFast = true
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            flushWatch()
            player?.let { savePlaybackPosition(currentItemKey(), it.currentPosition, it.duration) }
            handler.postDelayed(this, FLUSH_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (playing) {
                if (playingSince == 0L) playingSince = SystemClock.elapsedRealtime()
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, FLUSH_MS)
            } else {
                flushWatch()
                playingSince = 0L
                handler.removeCallbacks(tick)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            ended = state == Player.STATE_ENDED
            if (state == Player.STATE_ENDED) {
                val dur = player?.duration ?: 0L
                savePlaybackPosition(currentItemKey(), dur, dur)
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            error = if (e.errorCodeName.contains("DECOD", ignoreCase = true)) {
                "Codec video này không phát được bằng bộ giải mã hiện tại"
            } else {
                "Không phát được video này (${e.errorCodeName})"
            }
        }
    }

    /** Cộng thời gian thực sự đang phát vào chuỗi lửa của hôm nay. */
    private fun flushWatch() {
        if (playingSince == 0L) return
        val now = SystemClock.elapsedRealtime()
        val sec = (now - playingSince) / 1000
        if (sec <= 0) return
        playingSince += sec * 1000
        StreakStore.addWatch(sec).takeIf { it.isNotEmpty() }?.let { banners += it.joinToString("\n") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StreakStore.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Hàng đợi: lấy danh sách nơi người dùng bấm vào; nếu không có (vd app bị hệ thống tắt rồi mở lại) thì chỉ phát 1 video
        val uri = intent.data ?: return finish()
        val start = intent.getIntExtra(EXTRA_INDEX, -1)
        val shared = PlayQueue.items
        if (start in shared.indices && shared[start].uri == uri) {
            queue = shared
            index = start
        } else {
            queue = listOf(VideoItem(uri.toString(), uri, "", 0, 0, 0, "", null, null))
            index = 0
        }

        if (savedInstanceState != null) {
            position = savedInstanceState.getLong(KEY_POS)
            playWhenReady = savedInstanceState.getBoolean(KEY_PLAY, true)
            index = savedInstanceState.getInt(KEY_INDEX, index).coerceIn(queue.indices)
            requestedOrientation = savedInstanceState.getInt(KEY_ORIENT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        } else {
            // Luôn mở ở màn hình dọc, dù video dọc hay ngang; muốn xem ngang thì bấm nút xoay
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Keep the current frame visible while ExoPlayer prepares the next video.
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        }

        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) { PlayerScreen() }
        }
    }

    override fun onStart() {
        super.onStart()
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this).setRenderersFactory(renderers).build().also { p ->
            playerView.player = p
            p.addListener(listener)
        }
        load(position, playWhenReady)
    }

    override fun onStop() {
        super.onStop()
        flushWatch()
        playingSince = 0L
        handler.removeCallbacks(tick)
        player?.let {
            position = it.currentPosition
            playWhenReady = it.playWhenReady
            savePlaybackPosition(currentItemKey(), position, it.duration)
            it.removeListener(listener)
            it.release()
        }
        player = null
        playerView.player = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_POS, player?.currentPosition ?: position)
        outState.putBoolean(KEY_PLAY, player?.playWhenReady ?: playWhenReady)
        outState.putInt(KEY_INDEX, index)
        outState.putInt(KEY_ORIENT, requestedOrientation)
    }

    private fun load(startAt: Long, play: Boolean) {
        val p = player ?: return
        error = null
        ended = false
        val key = currentItemKey()
        val resumePos = if (startAt > 0) startAt else getResumePosition(key)
        if (resumePos > 0 && startAt == 0L) {
            resumeFlash = "Tiếp tục từ ${formatDuration(resumePos)}"
        }
        p.setMediaItem(MediaItem.fromUri(queue[index].uri))
        p.seekTo(resumePos)
        p.playWhenReady = play
        p.prepare()
    }

    /** Chuyển sang video trước (-1) / sau (+1) trong danh sách. */
    private fun go(delta: Int) {
        val n = index + delta
        if (n !in queue.indices) return
        player?.let { savePlaybackPosition(currentItemKey(), it.currentPosition, it.duration) }
        flushWatch()
        index = n
        VideoRepository(this).markWatched(queue[n])
        load(0, true)
    }

    private fun seekFromGesture(targetMs: Long): Long? {
        val p = player ?: return null
        val durationMs = p.duration
        if (!p.isCurrentMediaItemSeekable || durationMs <= 0L) return null

        val target = targetMs.coerceIn(0L, durationMs)
        // Seek directly to a decodable frame for gestures. Keep the ends of the
        // timeline exact, including a seek to the end to finish playback.
        if (target > 0L && target < durationMs) {
            p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            try {
                p.seekTo(target)
            } finally {
                p.setSeekParameters(SeekParameters.EXACT)
            }
        } else {
            p.seekTo(target)
        }
        return p.currentPosition
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0, dur))
    }

    private fun togglePlay() {
        val p = player ?: return
        when {
            ended -> { p.seekTo(0); p.play() }
            p.isPlaying -> p.pause()
            else -> {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
            }
        }
    }

    private fun openInAnotherPlayer() {
        val item = queue.getOrNull(index) ?: return
        val uri = item.localFile?.let { file ->
            runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrNull()
        } ?: item.uri
        val type = item.mime ?: contentResolver.getType(uri) ?: "video/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(view, "Mở video bằng")) }
            .onFailure { error = "Không tìm thấy ứng dụng khác có thể mở video này" }
    }

    /** Nút xoay: chuyển qua lại giữa dọc và ngang. */
    private fun toggleOrientation() {
        val landscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscapeNow) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ───────────────────────────── Giao diện ─────────────────────────────

    @Composable
    private fun PlayerScreen() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val prefs = remember { getSharedPreferences("kho_video", Context.MODE_PRIVATE) }
        var splitMode by rememberSaveable { mutableStateOf(prefs.getBoolean("player_split_mode", false)) }
        var splitRatio by rememberSaveable { mutableFloatStateOf(prefs.getFloat("player_split_ratio", 0.5f).coerceIn(0.25f, 0.75f)) }
        var splitImageZoom by rememberSaveable { mutableFloatStateOf(1f) }
        var splitAutoPlay by rememberSaveable { mutableStateOf(prefs.getBoolean("player_split_auto_play", false)) }
        var splitAreaHeightPx by remember { mutableIntStateOf(0) }
        var splitImageUri by rememberSaveable { mutableStateOf(prefs.getString("player_split_image", null)) }
        var splitFolderKey by rememberSaveable { mutableStateOf(prefs.getString("player_split_folder", null)) }
        var splitImage by remember { mutableStateOf<ImageBitmap?>(null) }
        val photoRepo = remember(context) { PhotoRepository(context) }
        var showSplitPhotoPicker by remember { mutableStateOf(false) }
        var splitPickerLoading by remember { mutableStateOf(false) }
        var splitPickerPhotos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
        LaunchedEffect(showSplitPhotoPicker, splitMode) {
            if (showSplitPhotoPicker || splitMode) {
                splitPickerLoading = true
                val selectedDirs = photoRepo.selectedDirs()
                splitPickerPhotos = photoRepo.scan()
                    .filter { it.dirKey in selectedDirs }
                    .sortedByDescending { it.takenAt }
                splitPickerLoading = false
            }
        }
        val splitFolders = remember(splitPickerPhotos) {
            splitPickerPhotos.groupBy { it.dirKey }.values
                .map { folder -> folder.sortedByDescending { it.takenAt } }
                .sortedByDescending { it.firstOrNull()?.takenAt ?: 0L }
        }
        LaunchedEffect(splitFolders, splitImageUri, splitFolderKey) {
            if (splitFolders.isNotEmpty()) {
                val validKeys = splitFolders.mapNotNull { it.firstOrNull()?.dirKey }.toSet()
                val uriFolder = splitPickerPhotos.firstOrNull { it.uri.toString() == splitImageUri }?.dirKey
                val savedFolderKey = splitFolderKey
                val chosenKey = when {
                    savedFolderKey != null && savedFolderKey in validKeys -> savedFolderKey
                    uriFolder != null && uriFolder in validKeys -> uriFolder
                    else -> splitFolders.first().first().dirKey
                }
                if (chosenKey != splitFolderKey) {
                    splitFolderKey = chosenKey
                    prefs.edit().putString("player_split_folder", chosenKey).apply()
                }
            }
        }
        val splitFolderPhotos = remember(splitPickerPhotos, splitFolderKey) {
            splitPickerPhotos.filter { it.dirKey == splitFolderKey }.sortedByDescending { it.takenAt }
        }
        val selectedSplitPhotoIndex = splitFolderPhotos.indexOfFirst { it.uri.toString() == splitImageUri }
        val splitPagerState = rememberPagerState(
            initialPage = selectedSplitPhotoIndex.coerceAtLeast(0),
        ) { splitFolderPhotos.size }
        LaunchedEffect(splitImageUri, splitFolderPhotos) {
            val target = splitFolderPhotos.indexOfFirst { it.uri.toString() == splitImageUri }
            if (target >= 0 && target != splitPagerState.currentPage) {
                splitPagerState.scrollToPage(target)
            } else if (target < 0 && splitFolderPhotos.isNotEmpty()) {
                splitPagerState.scrollToPage(0)
                val first = splitFolderPhotos.first()
                splitImageUri = first.uri.toString()
                prefs.edit().putString("player_split_image", splitImageUri).apply()
            }
        }
        LaunchedEffect(splitAutoPlay, splitMode, splitPagerState.currentPage, splitFolderPhotos.size) {
            if (splitAutoPlay && splitMode && splitFolderPhotos.size > 1) {
                delay(3_000)
                val next = (splitPagerState.currentPage + 1) % splitFolderPhotos.size
                splitPagerState.scrollToPage(next)
            }
        }
        LaunchedEffect(splitPagerState.currentPage, splitFolderPhotos) {
            val photo = splitFolderPhotos.getOrNull(splitPagerState.currentPage) ?: return@LaunchedEffect
            val selectedIndex = splitFolderPhotos.indexOfFirst { it.uri.toString() == splitImageUri }
            if (!splitPagerState.isScrollInProgress && selectedIndex >= 0 && selectedIndex != splitPagerState.currentPage) {
                return@LaunchedEffect
            }
            if (photo.uri.toString() != splitImageUri) {
                splitImageUri = photo.uri.toString()
                prefs.edit().putString("player_split_image", splitImageUri).apply()
            }
            splitImageZoom = 1f
        }
        LaunchedEffect(splitImageUri, splitPickerPhotos) {
            val raw = splitImageUri
            if (raw == null || splitPickerPhotos.any { it.uri.toString() == raw }) {
                splitImage = null
            } else {
                splitImage = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(Uri.parse(raw))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                    }.getOrNull()
                }
            }
        }
        var controls by remember { mutableStateOf(true) }
        var videoZoom by remember { mutableFloatStateOf(1f) }
        var locked by rememberSaveable { mutableStateOf(false) }
        var showUnlock by remember { mutableStateOf(false) }
        var pos by remember { mutableLongStateOf(0L) }
        var dur by remember { mutableLongStateOf(0L) }
        var seeking by remember { mutableStateOf<Float?>(null) }
        var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
        var touched by remember { mutableIntStateOf(0) }   // tăng mỗi lần bấm nút → đếm lại thời gian tự ẩn
        var flash by remember { mutableStateOf<String?>(null) }  // chữ "« 5 giây" / "5 giây »" ở giữa màn hình
        var fast by remember { mutableStateOf(false) }  // đang giữ màn hình để tua nhanh
        var preview by remember { mutableStateOf<ImageBitmap?>(null) }  // khung hình xem trước khi kéo thanh tua
        var isSwiping by remember { mutableStateOf(false) }
        var swipeTargetPos by remember { mutableLongStateOf(0L) }
        var swipeDeltaSec by remember { mutableLongStateOf(0L) }
        var screenWidth by remember { mutableIntStateOf(1) }

        LaunchedEffect(resumeFlash) {
            val msg = resumeFlash
            if (msg != null) {
                flash = msg
                resumeFlash = null
            }
        }
        val previewActive = seeking != null || isSwiping
        LaunchedEffect(previewActive, index) {
            preview = null
            var lastSample = -1L
            while (seeking != null || isSwiping) {
                val target = seeking?.let { (it * dur).toLong() } ?: swipeTargetPos
                val sample = target / 300L * 300L
                if (dur > 0L && sample != lastSample) {
                    lastSample = sample
                    frameAt(queue[index].uri, sample)?.let { bitmap ->
                        if (seeking != null || isSwiping) preview = bitmap.asImageBitmap()
                    }
                }
                delay(50)
            }
        }
        var showBuffering by remember { mutableStateOf(false) }
        LaunchedEffect(buffering, previewActive) {
            showBuffering = false
            if (buffering && !previewActive) {
                delay(350)
                showBuffering = true
            }
        }
        val p = player

        LaunchedEffect(p) {
            while (true) {
                p?.let {
                    pos = it.currentPosition
                    dur = it.duration.coerceAtLeast(0)
                }
                delay(200)
            }
        }
        // Tự ẩn thanh điều khiển sau 3,5 giây khi đang phát
        LaunchedEffect(controls, isPlaying, seeking, touched) {
            if (controls && isPlaying && seeking == null) {
                delay(3500)
                controls = false
            }
        }
        // Thanh điều hướng / thanh trạng thái của máy hiện cùng lúc với các nút (chỉ cần chạm màn hình, không phải vuốt)
        LaunchedEffect(controls, locked) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                if (controls && !locked) show(WindowInsetsCompat.Type.systemBars())
                else hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        LaunchedEffect(showUnlock) {
            if (showUnlock) { delay(2500); showUnlock = false }
        }
        LaunchedEffect(flash) {
            if (flash != null) { delay(700); flash = null }
        }
        val act = { block: () -> Unit -> touched++; block() }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { screenWidth = it.width }
        ) {
            val dividerHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
            val videoPaneFraction = if (splitAreaHeightPx > 0) {
                ((splitAreaHeightPx - dividerHeightPx) * splitRatio / splitAreaHeightPx).coerceIn(0.2f, 0.8f)
            } else splitRatio
            Column(Modifier.fillMaxSize().onSizeChanged { splitAreaHeightPx = it.height }) {
                Box(
                    (if (splitMode) Modifier.fillMaxWidth().weight(splitRatio) else Modifier.fillMaxSize())
                        .clipToBounds(),
                ) {
                    AndroidView(
                        { playerView },
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = videoZoom
                            scaleY = videoZoom
                        },
                    )
                }
                if (splitMode) {
                    Box(
                        Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF202124))
                            .pointerInput(splitAreaHeightPx) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, drag ->
                                        change.consume()
                                        if (splitAreaHeightPx > 0) {
                                            splitRatio = (splitRatio + drag / splitAreaHeightPx).coerceIn(0.25f, 0.75f)
                                        }
                                    },
                                    onDragEnd = { prefs.edit().putFloat("player_split_ratio", splitRatio).apply() },
                                    onDragCancel = { prefs.edit().putFloat("player_split_ratio", splitRatio).apply() },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.width(52.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.8f)))
                    }
                    Box(
                        Modifier.fillMaxWidth().weight(1f - splitRatio)
                            .clipToBounds()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var previousDistance = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val fingers = event.changes.filter { it.pressed }
                                        if (fingers.size >= 2) {
                                            val dx = fingers[0].position.x - fingers[1].position.x
                                            val dy = fingers[0].position.y - fingers[1].position.y
                                            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                            if (previousDistance > 0f && distance > 0f) {
                                                splitImageZoom = (splitImageZoom * (distance / previousDistance)).coerceIn(1f, 4f)
                                            }
                                            previousDistance = distance
                                            fingers.forEach { it.consume() }
                                        } else {
                                            previousDistance = 0f
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .background(Color(0xFF111111))
                            .pointerInput(splitImageZoom) {
                                detectTapGestures(onDoubleTap = {
                                    splitImageZoom = if (splitImageZoom > 1.01f) 1f else 2f
                                })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (splitFolderPhotos.isNotEmpty()) {
                            HorizontalPager(
                                state = splitPagerState,
                                modifier = Modifier.fillMaxSize(),
                            ) { page ->
                                SplitPhotoPage(
                                    photo = splitFolderPhotos[page],
                                    repository = photoRepo,
                                    zoom = splitImageZoom,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else if (splitImage != null) {
                            Image(
                                splitImage!!,
                                "Ảnh bên dưới video",
                                Modifier.fillMaxSize().graphicsLayer {
                                    scaleX = splitImageZoom
                                    scaleY = splitImageZoom
                                },
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            TextButton(onClick = { showSplitPhotoPicker = true }) {
                                Icon(Icons.Rounded.AddPhotoAlternate, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Chọn thư mục ảnh trong mục Ảnh")
                            }
                        }
                        Row(
                            Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (splitFolderPhotos.size > 1 && selectedSplitPhotoIndex >= 0) {
                                IconButton(
                                    onClick = {
                                        splitAutoPlay = !splitAutoPlay
                                        prefs.edit().putBoolean("player_split_auto_play", splitAutoPlay).apply()
                                    },
                                ) {
                                    Icon(
                                        if (splitAutoPlay) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        if (splitAutoPlay) "Dừng tự chuyển ảnh" else "Tự chuyển ảnh",
                                        tint = Color.White,
                                    )
                                }
                            }
                            TextButton(onClick = { showSplitPhotoPicker = true }) { Text("Đổi thư mục", color = Color.White) }
                        }
                    }
                }
            }
            // Vuốt ngang màn hình để tua live trên màn hình lớn; chạm/nhấp đúp 5s/giữ 2x
            if (splitMode) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(Color.Black),
                )
            }
            if (previewActive && preview != null) {
                Image(
                    bitmap = preview!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (splitMode) Modifier.fillMaxHeight(videoPaneFraction).align(Alignment.TopCenter) else Modifier.fillMaxSize().align(Alignment.Center))
                        .graphicsLayer {
                            scaleX = videoZoom
                            scaleY = videoZoom
                        }
                        .background(Color.Black),
                    contentScale = ContentScale.Fit,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (splitMode) Modifier.fillMaxHeight(videoPaneFraction).align(Alignment.TopCenter) else Modifier.fillMaxSize())
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var previousDistance = 0f
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.filter { it.pressed }
                                if (fingers.size >= 2) {
                                    val dx = fingers[0].position.x - fingers[1].position.x
                                    val dy = fingers[0].position.y - fingers[1].position.y
                                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                    if (previousDistance > 0f && distance > 0f) {
                                        videoZoom = (videoZoom * (distance / previousDistance)).coerceIn(1f, 4f)
                                    }
                                    previousDistance = distance
                                    fingers.forEach { it.consume() }
                                } else {
                                    previousDistance = 0f
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        var totalDragX = 0f
                        var startPos = 0L
                        detectHorizontalDragGestures(
                            onDragStart = {
                                val pl = player ?: return@detectHorizontalDragGestures
                                totalDragX = 0f
                                startPos = pl.currentPosition
                                isSwiping = true
                                swipeTargetPos = startPos
                                swipeDeltaSec = 0L
                                preview = null
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalDragX += dragAmount
                                val pl = player ?: return@detectHorizontalDragGestures
                                val range = dur.coerceIn(30_000L, 180_000L)
                                val deltaMs = (totalDragX / screenWidth.coerceAtLeast(1)) * range
                                val target = (startPos + deltaMs.toLong()).coerceIn(0L, dur)
                                swipeTargetPos = target
                                swipeDeltaSec = (target - startPos) / 1000
                            },
                            onDragEnd = {
                                isSwiping = false
                                preview = null
                                seekFromGesture(swipeTargetPos)?.let { pos = it }
                                touched++
                            },
                            onDragCancel = {
                                isSwiping = false
                                preview = null
                            },
                        )
                    }
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = { if (locked) showUnlock = !showUnlock else controls = !controls },
                            onDoubleTap = { offset ->
                                if (!locked && player != null && !ended && error == null) {
                                    val isRight = offset.x >= screenWidth / 2
                                    val delta = if (isRight) 5_000L else -5_000L
                                    seekBy(delta)
                                    touched++
                                }
                            },
                            onLongPress = {
                                val pl = player
                                if (!locked && pl != null && !ended && error == null && !isSwiping) {
                                    wasPlayingBeforeFast = pl.playWhenReady
                                    pl.setPlaybackSpeed(FAST_SPEED)
                                    pl.play()
                                    fast = true
                                }
                            },
                            onPress = {
                                tryAwaitRelease()
                                if (fast) {
                                    fast = false
                                    player?.let { pl ->
                                        pl.setPlaybackSpeed(1f)
                                        if (!wasPlayingBeforeFast) pl.pause()
                                    }
                                }
                            },
                        )
                    },
            )

            // Khung hiển thị khi vuốt trên màn hình để tua
            AnimatedVisibility(
                fast, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
                    .padding(top = 20.dp),
            ) {
                Row(
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tua nhanh ${FAST_SPEED.toInt()}x", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }

            if (showSplitPhotoPicker) {
                SplitPhotoPicker(
                    folders = splitFolders,
                    loading = splitPickerLoading,
                    repository = photoRepo,
                    onSelect = { photo ->
                        splitFolderKey = photo.dirKey
                        splitImageUri = photo.uri.toString()
                        splitImageZoom = 1f
                        prefs.edit()
                            .putString("player_split_folder", splitFolderKey)
                            .putString("player_split_image", splitImageUri)
                            .putBoolean("player_split_mode", true)
                            .apply()
                        splitMode = true
                        showSplitPhotoPicker = false
                    },
                    onDismiss = { showSplitPhotoPicker = false },
                )
            }

            if (buffering && showBuffering && error == null && !previewActive) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
            }
            error?.let {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { openInAnotherPlayer() }) {
                        Icon(Icons.Rounded.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Mở bằng ứng dụng khác")
                    }
                }
            }
            flash?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center)
                        .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Thanh trên: quay lại + tên video
            AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Quay lại", tint = Color.White) }
                    Text(
                        queue[index].name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (queue.size > 1) {
                        Text(
                            "${index + 1}/${queue.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    IconButton(onClick = {
                        splitMode = !splitMode
                        prefs.edit().putBoolean("player_split_mode", splitMode).apply()
                        if (splitMode && splitImage == null) showSplitPhotoPicker = true
                    }) {
                        Icon(Icons.Rounded.Collections, "Bật/tắt video và ảnh", tint = Color.White)
                    }
                }
            }

            // Thanh dưới: thanh tua, thời gian, các nút
            AnimatedVisibility(
                controls && !locked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = if (splitMode) {
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(videoPaneFraction)
                } else {
                    Modifier.align(Alignment.BottomCenter)
                },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (splitMode) Modifier.fillMaxHeight() else Modifier)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.9f))))
                        .then(
                            if (splitMode) Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                            else Modifier.windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars)),
                        )
                        .padding(start = 20.dp, end = 20.dp, top = if (splitMode) 8.dp else 48.dp, bottom = if (splitMode) 8.dp else 20.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    val fraction = seeking ?: if (dur > 0) pos.toFloat() / dur else 0f
                    Box(Modifier.fillMaxWidth().height(36.dp)) {
                        SeekBar(
                            fraction = fraction,
                            onChange = { value ->
                                if (seeking == null) {
                                    preview = null
                                    wasPlayingBeforeSeek = player?.playWhenReady == true
                                    if (wasPlayingBeforeSeek) player?.pause()
                                }
                                seeking = value
                            },
                            onDone = { f ->
                                val target = (f * dur).toLong()
                                seekFromGesture(target)?.let { pos = it }
                                seeking = null
                                preview = null
                                if (wasPlayingBeforeSeek) player?.play()
                                wasPlayingBeforeSeek = false
                                touched++
                            },
                            onCancel = {
                                seeking = null
                                preview = null
                                if (wasPlayingBeforeSeek) player?.play()
                                wasPlayingBeforeSeek = false
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                        val shownPos = seeking?.let { (it * dur).toLong() } ?: pos
                        Text(formatDuration(shownPos), color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(dur), color = Color.White, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CtrlButton(Icons.Rounded.LockOpen, "Khoá màn hình") { locked = true; showUnlock = true }
                        CtrlButton(Icons.Rounded.SkipPrevious, "Video trước", enabled = index > 0) { act { go(-1) } }
                        CtrlButton(Icons.Rounded.Replay5, "Lùi 5 giây") { act { seekBy(-5_000) } }
                        IconButton(onClick = { act { togglePlay() } }, modifier = Modifier.size(64.dp)) {
                            Icon(
                                when {
                                    ended -> Icons.Rounded.Replay
                                    isPlaying -> Icons.Rounded.Pause
                                    else -> Icons.Rounded.PlayArrow
                                },
                                if (isPlaying) "Tạm dừng" else "Phát",
                                tint = Color.White,
                                modifier = Modifier.size(56.dp),
                            )
                        }
                        CtrlButton(Icons.Rounded.Forward5, "Tới 5 giây") { act { seekBy(5_000) } }
                        CtrlButton(Icons.Rounded.SkipNext, "Video sau", enabled = index < queue.lastIndex) { act { go(1) } }
                        CtrlButton(Icons.Rounded.ScreenRotation, "Xoay màn hình") { act { toggleOrientation() } }
                    }
                }
            }

            // Đang khoá: chỉ hiện nút mở khoá khi chạm vào màn hình
            AnimatedVisibility(
                locked && showUnlock, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars))
                    .padding(start = 20.dp, bottom = 28.dp),
            ) {
                Row(
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                        .pointerInput(Unit) { detectTapGestures { locked = false; showUnlock = false; controls = true } }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Chạm để mở khoá", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }

            // Thông báo chuỗi lửa: hiện ở phía trên, tự ẩn sau vài giây
            StreakBanner(banners.firstOrNull(), onDone = { if (banners.isNotEmpty()) banners.removeAt(0) })
        }
    }

    @Composable
    private fun BoxScope.StreakBanner(message: String?, onDone: () -> Unit) {
        var last by remember { mutableStateOf("") }
        if (message != null) last = message
        LaunchedEffect(message) {
            if (message != null) { delay(4000); onDone() }
        }
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
                .padding(top = 64.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                Modifier
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFFA53B), Color(0xFFFF4E36))))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Nội dung thông báo đã có sẵn biểu tượng 🔥 / ⚪ / 🔵 ở đầu
                Text(last, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    @Composable
    private fun SeekPreview(image: ImageBitmap?, time: String, modifier: Modifier) {
        // Video ngang: rộng 176dp; video dọc: cao 180dp — giữ đúng tỉ lệ khung hình
        val aspect = image?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        val w: Dp = if (aspect >= 1f) 176.dp else 180.dp * aspect
        val h: Dp = if (aspect >= 1f) 176.dp / aspect else 180.dp
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(w, h).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1E))
                    .border(2.dp, Color.White, RoundedCornerShape(10.dp)),
                Alignment.Center,
            ) {
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                time,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }

    @Composable
    private fun CtrlButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(30.dp))
        }
    }

    /** Thanh tua: vạch mảnh màu trắng + nút tròn trắng, chạm hoặc kéo để tua. */
    @Composable
    private fun SeekBar(
        fraction: Float,
        onChange: (Float) -> Unit,
        onDone: (Float) -> Unit,
        onCancel: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var width by remember { mutableIntStateOf(1) }
        var dragging by remember { mutableStateOf(false) }
        val change by rememberUpdatedState(onChange)
        val done by rememberUpdatedState(onDone)
        val cancel by rememberUpdatedState(onCancel)
        val thumb = SEEK_THUMB
        Box(
            modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { width = it.width }
                .pointerInput(Unit) {
                    val r = thumb.toPx()
                    fun f(x: Float) = ((x - r) / (width - 2 * r)).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (pointer.isConsumed) break
                                val current = f(pointer.position.x)
                                pointer.consume()
                                if (!pointer.pressed) {
                                    dragging = false
                                    done(current)
                                    break
                                }
                                if (dragging || kotlin.math.abs(pointer.position.x - down.position.x) >= viewConfiguration.touchSlop) {
                                    dragging = true
                                    change(current)
                                }
                            }
                        } finally {
                            if (dragging) {
                                dragging = false
                                cancel()
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = thumb.toPx()
                val y = size.height / 2
                val track = 5.dp.toPx()
                val start = r
                val end = size.width - r
                val x = start + (end - start) * fraction.coerceIn(0f, 1f)
                drawRoundRect(
                    Color.White.copy(alpha = 0.35f), Offset(start, y - track / 2), Size(end - start, track),
                    CornerRadius(track / 2),
                )
                drawRoundRect(Color.White, Offset(start, y - track / 2), Size(x - start, track), CornerRadius(track / 2))
                drawCircle(Color.White, radius = if (dragging) r * 1.25f else r, center = Offset(x, y))
            }
        }
    }

    companion object {
        private const val KEY_POS = "pos"
        private const val KEY_PLAY = "play"
        private const val KEY_INDEX = "index"
        private const val KEY_ORIENT = "orient"
        private const val EXTRA_INDEX = "queue_index"
        private const val FLUSH_MS = 10_000L
        private const val FAST_SPEED = 2f
        private const val PREVIEW_PX = 360
        private val SEEK_THUMB = 12.dp

        /** Mở video [item]; [queue] là danh sách đang xem để bấm video trước / sau. */
        fun start(context: Context, item: VideoItem, queue: List<VideoItem> = listOf(item)) {
            val list = queue.takeIf { q -> q.any { it.id == item.id } } ?: listOf(item)
            PlayQueue.items = list
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .setData(item.uri)
                    .putExtra(EXTRA_INDEX, list.indexOfFirst { it.id == item.id }),
            )
        }
    }
}
@Composable
private fun SplitPhotoPage(
    photo: PhotoItem,
    repository: PhotoRepository,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    var image by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photo.id) {
        image = repository.full(photo)?.asImageBitmap()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = photo.name,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                },
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun SplitPhotoPicker(
    folders: List<List<PhotoItem>>,
    loading: Boolean,
    repository: PhotoRepository,
    onSelect: (PhotoItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var activeFolderKey by remember { mutableStateOf<String?>(null) }
    val activeFolder = folders.firstOrNull { it.firstOrNull()?.dirKey == activeFolderKey }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (activeFolder != null) {
                        IconButton(onClick = { activeFolderKey = null }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Chọn thư mục khác")
                        }
                    }
                    Text(
                        activeFolder?.firstOrNull()?.folder?.let { "Chọn ảnh · $it" } ?: "Chọn thư mục ảnh",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Đóng") }
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    folders.isEmpty() -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Chưa có thư mục ảnh nào được hiển thị. Hãy chọn thư mục ở tab Ảnh trước.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(136.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (activeFolder == null) {
                                items(folders, key = { it.first().dirKey }) { folder ->
                                    val cover = folder.first()
                                    var thumbnail by remember(cover.id) { mutableStateOf<ImageBitmap?>(null) }
                                    LaunchedEffect(cover.id) {
                                        thumbnail = repository.thumbnail(cover)?.asImageBitmap()
                                    }
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clip(MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .clickable { activeFolderKey = cover.dirKey }
                                            .padding(6.dp),
                                    ) {
                                        Box(
                                            Modifier.fillMaxWidth().aspectRatio(1.15f)
                                                .clip(MaterialTheme.shapes.small),
                                        ) {
                                            thumbnail?.let {
                                                Image(it, cover.folder, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                            }
                                        }
                                        Text(
                                            cover.folder,
                                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            "${folder.size} ảnh",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            } else {
                                items(activeFolder, key = { it.id }) { photo ->
                                    var thumbnail by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
                                    LaunchedEffect(photo.id) {
                                        thumbnail = repository.thumbnail(photo)?.asImageBitmap()
                                    }
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clip(MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .clickable { onSelect(photo) }
                                            .padding(6.dp),
                                    ) {
                                        Box(
                                            Modifier.fillMaxWidth().aspectRatio(1f)
                                                .clip(MaterialTheme.shapes.small),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            thumbnail?.let {
                                                Image(it, photo.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                            } ?: CircularProgressIndicator(Modifier.size(24.dp))
                                        }
                                        Text(
                                            photo.name,
                                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
