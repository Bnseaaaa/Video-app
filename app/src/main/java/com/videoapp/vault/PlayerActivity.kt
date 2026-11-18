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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
            } else {
                r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
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
        }

        override fun onPlayerError(e: PlaybackException) {
            error = "Không phát được video này"
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
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
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
        player = ExoPlayer.Builder(this).build().also { p ->
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
        p.setMediaItem(MediaItem.fromUri(queue[index].uri))
        p.seekTo(startAt)
        p.playWhenReady = play
        p.prepare()
    }

    /** Chuyển sang video trước (-1) / sau (+1) trong danh sách. */
    private fun go(delta: Int) {
        val n = index + delta
        if (n !in queue.indices) return
        flushWatch()
        index = n
        VideoRepository(this).markWatched(queue[n])
        load(0, true)
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

    /** Nút xoay: chuyển qua lại giữa dọc và ngang. */
    private fun toggleOrientation() {
        val landscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscapeNow) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ───────────────────────────── Giao diện ─────────────────────────────

    @Composable
    private fun PlayerScreen() {
        var controls by remember { mutableStateOf(true) }
        var locked by rememberSaveable { mutableStateOf(false) }
        var showUnlock by remember { mutableStateOf(false) }
        var pos by remember { mutableLongStateOf(0L) }
        var dur by remember { mutableLongStateOf(0L) }
        var seeking by remember { mutableStateOf<Float?>(null) }
        var touched by remember { mutableIntStateOf(0) }   // tăng mỗi lần bấm nút → đếm lại thời gian tự ẩn
        var flash by remember { mutableStateOf<String?>(null) }  // chữ "« 5 giây" / "5 giây »" ở giữa màn hình
        var fast by remember { mutableStateOf(false) }  // đang giữ màn hình để tua nhanh
        var preview by remember { mutableStateOf<ImageBitmap?>(null) }  // khung hình xem trước khi kéo thanh tua
        // Khi kéo: lấy khung hình ở vị trí đang kéo tới (làm tròn theo giây); kéo nhanh thì bỏ qua các vị trí cũ
        LaunchedEffect(Unit) {
            snapshotFlow { seeking?.let { (it * dur).toLong() / 1000 * 1000 } }
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { t -> frameAt(queue[index].uri, t)?.let { preview = it.asImageBitmap() } }
        }
        LaunchedEffect(index) { preview = null }
        LaunchedEffect(seeking == null) { if (seeking == null) preview = null }
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

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ playerView }, Modifier.fillMaxSize())
            // Chạm vào video để ẩn/hiện thanh điều khiển; giữ ngón tay để tua nhanh x2, thả ra về bình thường
            Box(
                Modifier.fillMaxSize().pointerInput(locked) {
                    detectTapGestures(
                        onTap = { if (locked) showUnlock = !showUnlock else controls = !controls },
                        onLongPress = {
                            val pl = player
                            if (!locked && pl != null && !ended && error == null) {
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
            // Nhãn "Tua nhanh 2x" khi đang giữ
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

            if (buffering && error == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
            }
            error?.let {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color.White)
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
                }
            }

            // Thanh dưới: thanh tua, thời gian, các nút
            AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.9f))))
                        .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars))
                        .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 20.dp),
                ) {
                    val fraction = seeking ?: if (dur > 0) pos.toFloat() / dur else 0f
                    var barWidth by remember { mutableIntStateOf(0) }
                    Box(Modifier.fillMaxWidth().onSizeChanged { barWidth = it.width }) {
                        SeekBar(
                            fraction = fraction,
                            onChange = { seeking = it },
                            onDone = { f ->
                                player?.seekTo((f * dur).toLong())
                                pos = (f * dur).toLong()
                                seeking = null
                                touched++
                            },
                        )
                        // Ô xem trước nằm ngay trên nút tròn, chạy theo ngón tay
                        seeking?.let { f ->
                            SeekPreview(
                                image = preview,
                                time = formatDuration((f * dur).toLong()),
                                modifier = Modifier.layout { measurable, _ ->
                                    val p = measurable.measure(Constraints())
                                    val r = SEEK_THUMB.toPx()
                                    val cx = r + (barWidth - 2 * r) * f
                                    val x = (cx - p.width / 2).toInt().coerceIn(0, (barWidth - p.width).coerceAtLeast(0))
                                    // Không chiếm chỗ trong bố cục, chỉ vẽ nổi phía trên thanh tua
                                    layout(0, 0) { p.place(x, -p.height - 4.dp.roundToPx()) }
                                },
                            )
                        }
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
                        CtrlButton(Icons.Rounded.Replay5, "Lùi 5 giây") { act { seekBy(-5_000); flash = "« 5 giây" } }
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
                        CtrlButton(Icons.Rounded.Forward5, "Tới 5 giây") { act { seekBy(5_000); flash = "5 giây »" } }
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
    private fun SeekBar(fraction: Float, onChange: (Float) -> Unit, onDone: (Float) -> Unit) {
        var width by remember { mutableIntStateOf(1) }
        var dragging by remember { mutableStateOf(false) }
        var current by remember { mutableFloatStateOf(0f) }
        val change by rememberUpdatedState(onChange)
        val done by rememberUpdatedState(onDone)
        val thumb = SEEK_THUMB
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { width = it.width }
                .pointerInput(Unit) {
                    val r = thumb.toPx()
                    fun f(x: Float) = ((x - r) / (width - 2 * r)).coerceIn(0f, 1f)
                    detectTapGestures { o -> f(o.x).let { change(it); done(it) } }
                }
                .pointerInput(Unit) {
                    val r = thumb.toPx()
                    fun f(x: Float) = ((x - r) / (width - 2 * r)).coerceIn(0f, 1f)
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; current = f(o.x); change(current) },
                        onHorizontalDrag = { ch, _ -> current = f(ch.position.x); change(current) },
                        onDragEnd = { dragging = false; done(current) },
                        onDragCancel = { dragging = false; done(current) },
                    )
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
