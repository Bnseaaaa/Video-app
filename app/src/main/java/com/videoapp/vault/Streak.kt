package com.videoapp.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class Flame(val label: String, val tint: Color, val bg: Color) {
    RED("Lửa đỏ", Color(0xFFFF5A36), Color(0x26FF5A36)),
    WHITE("Lửa trắng", Color.White, Color(0xFF3A3650)),
    BLUE("Lửa xanh", Color(0xFF3D8BFF), Color(0x263D8BFF)),
}

private val FireGradient = Brush.horizontalGradient(listOf(Color(0xFFFFB13B), Color(0xFFFF5A36)))

@Composable
fun FlameBadge(flame: Flame, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(flame.bg)
            .then(if (flame == Flame.WHITE) Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape) else Modifier),
        Alignment.Center,
    ) {
        Icon(Icons.Rounded.LocalFireDepartment, flame.label, tint = flame.tint, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
private fun FlameCount(flame: Flame, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlameBadge(flame, 26.dp)
        Spacer(Modifier.width(4.dp))
        Text("$count", style = MaterialTheme.typography.titleSmall)
    }
}

/** Thẻ chuỗi lửa trên màn hình chính. */
@Composable
fun StreakCard(s: StreakState, onOpen: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlameBadge(Flame.RED, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.red > 0) "Chuỗi ${s.red} ngày" else "Bắt đầu chuỗi lửa",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when {
                            s.todayMet -> "Hôm nay đã xem đủ ✓"
                            else -> "Hôm nay: ${clock(s.todaySec)} / ${clock(s.todayReqSec)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.todayMet) Color(0xFF2EAD5B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlameCount(Flame.WHITE, s.white)
                Spacer(Modifier.width(10.dp))
                FlameCount(Flame.BLUE, s.blue)
            }
            Spacer(Modifier.height(12.dp))
            FireProgress(s.todaySec.toFloat() / s.todayReqSec)
            s.gap?.let { g ->
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Chuỗi ${g.streakAtRisk} ngày đang bị đứt — bấm để xử lý",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun FireProgress(p: Float) {
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(FireGradient))
    }
}

/** 760 → "12:40" */
private fun clock(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)

private val dayFmt = DateTimeFormatter.ofPattern("dd/MM")
private fun dayText(d: Long) = LocalDate.ofEpochDay(d).format(dayFmt)

private enum class Confirm { WHITE, EXCHANGE }

/** Bảng chi tiết chuỗi lửa: số lửa, tiến độ, dùng/đổi lửa, xử lý chuỗi đứt, luật chơi. */
@Composable
fun StreakDialog(s: StreakState, onMessages: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var showRules by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(20.dp).widthIn(max = 440.dp).fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = Flame.RED.tint)
                    Spacer(Modifier.width(8.dp))
                    Text("Chuỗi lửa", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Đóng") }
                }
                Spacer(Modifier.height(8.dp))

                // Ba loại lửa
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BigFlame(Flame.RED, s.red)
                    BigFlame(Flame.WHITE, s.white)
                    BigFlame(Flame.BLUE, s.blue)
                }
                Spacer(Modifier.height(18.dp))

                // Chuỗi bị đứt
                s.gap?.let { g ->
                    GapPanel(g, s.blue, onUse = { onMessages(StreakStore.resolveGap(true)) }, onSkip = { onMessages(StreakStore.resolveGap(false)) })
                    Spacer(Modifier.height(16.dp))
                }

                // Hôm nay
                Text("Hôm nay", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FireProgress(s.todaySec.toFloat() / s.todayReqSec)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (s.todayMet) "Đã xem ${formatWatch(s.todaySec)} — đủ mục tiêu ✓"
                    else "Đã xem ${formatWatch(s.todaySec)} / ${formatWatch(s.todayReqSec)} · còn ${formatWatch(s.todayReqSec - s.todaySec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                // Phần thưởng tiếp theo
                Text("Phần thưởng tiếp theo", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                NextReward(Flame.WHITE, s.redProgress, s.whiteNeed, "ngày lửa đỏ")
                Spacer(Modifier.height(8.dp))
                NextReward(Flame.BLUE, s.blueProgress, s.blueNeed, "lửa trắng nhận được")
                Spacer(Modifier.height(18.dp))

                // Dùng / đổi lửa
                val whiteReason = s.whiteBlockReason
                FilledTonalButton(
                    onClick = { confirm = Confirm.WHITE },
                    enabled = whiteReason == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    FlameBadge(Flame.WHITE, 26.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dùng 1 lửa trắng")
                        Text(
                            whiteReason ?: "Mục tiêu hôm nay: ${formatWatch(s.todayReqSec)} → ${formatWatch(s.reqAfterWhite)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { confirm = Confirm.EXCHANGE },
                    enabled = s.white >= StreakStore.EXCHANGE_COST,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    FlameBadge(Flame.BLUE, 26.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Đổi 3 lửa trắng → 1 lửa xanh")
                        Text(
                            if (s.white >= StreakStore.EXCHANGE_COST) "Không đổi ngược lại được"
                            else "Cần thêm ${StreakStore.EXCHANGE_COST - s.white} lửa trắng",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showRules = !showRules }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(if (showRules) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Luật chơi")
                }
                if (showRules) Rules()
            }
        }
    }

    when (confirm) {
        Confirm.WHITE -> ConfirmDialog(
            title = "Dùng 1 lửa trắng?",
            text = "Mục tiêu hôm nay giảm từ ${formatWatch(s.todayReqSec)} xuống ${formatWatch(s.reqAfterWhite)}.\n" +
                "Mốc nhận lửa trắng tiếp theo sẽ về lại ${StreakStore.WHITE_BASE} ngày.",
            onYes = { onMessages(StreakStore.useWhite()) },
            onDone = { confirm = null },
        )
        Confirm.EXCHANGE -> ConfirmDialog(
            title = "Đổi 3 lửa trắng lấy 1 lửa xanh?",
            text = "Bạn sẽ còn ${s.white - StreakStore.EXCHANGE_COST} lửa trắng và ${s.blue + 1} lửa xanh. " +
                "Không thể đổi ngược lại.",
            onYes = { onMessages(StreakStore.exchange()) },
            onDone = { confirm = null },
        )
        null -> {}
    }
}

@Composable
private fun BigFlame(flame: Flame, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FlameBadge(flame, 60.dp)
        Spacer(Modifier.height(6.dp))
        Text("$count", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(flame.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NextReward(flame: Flame, have: Int, need: Int, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlameBadge(flame, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${flame.label}: $have / $need $unit",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (have.toFloat() / need).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = if (flame == Flame.WHITE) MaterialTheme.colorScheme.onSurfaceVariant else flame.tint,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun GapPanel(g: StreakGap, blue: Int, onUse: () -> Unit, onSkip: () -> Unit) {
    val enough = blue >= g.days
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(14.dp)) {
            Text("Chuỗi bị đứt!", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                (if (g.days == 1) "Ngày ${dayText(g.firstDay)} bạn chưa xem đủ."
                else "${g.days} ngày từ ${dayText(g.firstDay)} đến ${dayText(g.lastDay)} bạn chưa xem đủ.") +
                    " Dùng ${g.days} lửa xanh để giữ chuỗi ${g.streakAtRisk} ngày, hoặc để chuỗi về 0." +
                    if (!enough) "\nBạn chỉ có $blue lửa xanh — không đủ để bù." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onUse,
                enabled = enough,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Flame.BLUE.tint, contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Dùng ${g.days} lửa xanh để giữ chuỗi")
            }
            OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                Text("Không dùng, để chuỗi về 0")
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onYes: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onYes(); onDone() }) { Text("Đồng ý") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Huỷ") } },
    )
}

@Composable
private fun Rules() {
    val rules = listOf(
        "🔥 Mỗi ngày xem đủ 30 phút → +1 lửa đỏ. Ngày nào thiếu thì chuỗi lửa đỏ về 0.",
        "🕐 Một ngày được tính từ 1:30 sáng đến 1:30 sáng hôm sau (xem lúc 1:00 đêm vẫn tính cho ngày hôm trước).",
        "⚪ Tích đủ 7 lửa đỏ → +1 lửa trắng. Mỗi lần nhận, mốc lần sau tăng thêm 1 ngày (7, 8, 9…).",
        "🔵 Nhận đủ 3 lửa trắng → +1 lửa xanh. Mốc lần sau cũng tăng thêm 1 (3, 4, 5…).",
        "🔵 Lửa xanh bù cho ngày chưa xem đủ (mỗi ngày 1 lửa xanh) để giữ chuỗi. Dùng hay không là tuỳ bạn.",
        "⚪ Lửa trắng giảm mục tiêu hôm nay đi (số xanh + số trắng + số đỏ/100) phút, tối thiểu còn 1 phút. Không dùng được trong tháng 11.",
        "🔁 Có thể đổi 3 lửa trắng → 1 lửa xanh, không đổi ngược lại. Lửa đỏ không dùng hay đổi được.",
        "↺ Dùng lửa trắng/xanh thì mốc nhận loại lửa đó quay về ban đầu (7 / 3).",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rules.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
        }
    }
}
