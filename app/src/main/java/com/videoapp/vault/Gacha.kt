package com.videoapp.vault

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

const val GACHA_ALL = "\u0000gacha_all"

/** Thẻ "Gacha" trên màn hình: cho biết còn bao nhiêu video chưa xem + nút Quay. */
@Composable
fun GachaCard(videos: List<VideoItem>, scopeLabel: String, compact: Boolean = false, onSpin: () -> Unit) {
    val total = videos.size
    val watched = remember(videos) { videos.count { it.watched } }
    val left = total - watched
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, BrandGradient, MaterialTheme.shapes.large),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(if (compact) 40.dp else 48.dp).clip(CircleShape).background(BrandGradient),
                    Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Casino, null, tint = Color.White, modifier = Modifier.size(if (compact) 22.dp else 26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (compact) "Gacha trong $scopeLabel" else "Hôm nay xem gì?",
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (left > 0) "Còn $left video chưa xem" else "Đã xem hết $total video 🎉",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                GradientButton("Quay", Icons.Rounded.Casino, onSpin)
            }
            if (!compact && total > 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { watched.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    drawStopIndicator = {},
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Đã xem $watched / $total video trong $scopeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class Phase { SPINNING, REVEALED, ALL_DONE }

/**
 * Hộp quay Gacha: ảnh bìa xoay nhanh rồi chậm dần, dừng ở một video CHƯA XEM ngẫu nhiên.
 * "Quay lại" sẽ không ra lại video vừa bỏ qua (trong lượt mở hộp này).
 */
@Composable
fun GachaDialog(
    title: String,
    pool: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onResetRound: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val unwatched = pool.filterNot { it.watched }
    val skipped = remember { mutableStateListOf<String>() }
    var roll by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<VideoItem?>(null) }
    var showing by remember { mutableStateOf(pool.randomOrNull()) }
    var phase by remember { mutableStateOf(Phase.SPINNING) }
    val pop = remember { Animatable(1f) }

    LaunchedEffect(roll) {
        val candidates = unwatched.filterNot { it.id in skipped }.ifEmpty {
            skipped.clear()
            unwatched
        }
        if (candidates.isEmpty()) {
            phase = Phase.ALL_DONE
            return@LaunchedEffect
        }
        val target = candidates.random()
        phase = Phase.SPINNING
        // Hiệu ứng quay: đổi ảnh nhanh rồi chậm dần
        // Tổng thời gian quay khoảng 2,2 giây
        var wait = 40.0
        repeat(18) {
            showing = pool.random()
            delay(wait.toLong())
            wait *= 1.12
        }
        showing = target
        result = target
        phase = Phase.REVEALED
        pop.snapTo(0.85f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(20.dp).widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Casino, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Đóng") }
                }
                Spacer(Modifier.height(8.dp))

                if (phase == Phase.ALL_DONE) {
                    AllDone(pool.size, onReset = { onResetRound(); skipped.clear(); roll++ }, onClose = onDismiss)
                    return@Column
                }

                // Khung ảnh bìa
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .scale(pop.value)
                        .clip(MaterialTheme.shapes.large)
                        .border(
                            3.dp,
                            if (phase == Phase.REVEALED) BrandGradient else Brush.linearGradient(listOf(Color.Gray, Color.Gray)),
                            MaterialTheme.shapes.large,
                        ),
                ) {
                    showing?.let { item ->
                        if (item.remoteDevice == null) {
                            Thumbnail(item, Modifier.fillMaxSize())
                        } else {
                            Column(
                                Modifier.fillMaxSize().background(BrandGradient),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Rounded.Bluetooth, null, tint = Color.White, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Video từ ${item.remoteDevice}", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    if (phase == Phase.SPINNING) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                    }
                    if (phase == Phase.REVEALED) {
                        result?.let { r ->
                            if (r.durationMs > 0) DurationBadge(r.durationMs, Modifier.align(Alignment.BottomEnd).padding(10.dp))
                        }
                        Text(
                            "✨ VIDEO MỚI",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(BrandGradient)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                AnimatedContent(
                    targetState = if (phase == Phase.REVEALED) result else null,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "gachaInfo",
                ) { r ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (r == null) {
                            Text("Đang quay...", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(r.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(folderIcon(r.folder), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${r.folder} · ${Formatter.formatShortFileSize(context, r.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                val revealed = phase == Phase.REVEALED
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { result?.let { skipped += it.id }; roll++ },
                        enabled = revealed,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Quay lại")
                    }
                    val remoteResult = result?.remoteDevice != null
                    Button(
                        onClick = { if (!remoteResult) result?.let(onPlay) },
                        enabled = revealed && !remoteResult,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = CircleShape,
                    ) {
                        Icon(if (remoteResult) Icons.Rounded.Bluetooth else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (remoteResult) "Ở máy khác" else "Xem ngay")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Còn ${unwatched.size} video chưa xem",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AllDone(total: Int, onReset: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎉", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("Bạn đã xem hết!", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            if (total > 0) "Bạn đã xem cả $total video ở đây. Bắt đầu vòng mới để quay lại từ đầu nhé."
            else "Ở đây chưa có video nào để quay.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (total > 0) {
            Button(onClick = onReset, shape = CircleShape, modifier = Modifier.height(50.dp)) {
                Icon(Icons.Rounded.Replay, null)
                Spacer(Modifier.width(8.dp))
                Text("Bắt đầu vòng mới")
            }
        } else {
            TextButton(onClick = onClose) { Text("Đóng") }
        }
    }
}

@Composable
fun WatchedBadge() {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(12.dp), tint = Color(0xFF7CE38B))
        Spacer(Modifier.width(3.dp))
        Text("Đã xem", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
