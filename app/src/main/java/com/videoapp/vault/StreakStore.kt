package com.videoapp.vault

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Chuỗi lửa theo ngày.
 *
 * - Mỗi ngày xem đủ mục tiêu (mặc định 30 phút) → +1 lửa đỏ. Ngày nào thiếu → chuỗi đỏ về 0
 *   (trừ khi dùng lửa xanh để bù).
 * - Tích đủ [WHITE_BASE] lửa đỏ → +1 lửa trắng; mỗi lần nhận, mốc lần sau tăng thêm 1
 *   (7, 8, 9...). Dùng lửa trắng → mốc về lại 7.
 * - Nhận đủ [BLUE_BASE] lửa trắng → +1 lửa xanh; mốc lần sau tăng thêm 1 (3, 4, 5...).
 *   Dùng lửa xanh → mốc về lại 3.
 * - Lửa xanh: bù cho ngày không xem đủ (1 lửa xanh / ngày), người dùng tự chọn có dùng hay không.
 * - Lửa trắng: giảm mục tiêu hôm nay đi (xanh + trắng + đỏ/100) phút, tối thiểu còn 1 phút.
 *   Không dùng được vào tháng 11. Có thể đổi 3 lửa trắng → 1 lửa xanh (không đổi ngược được).
 * - Lửa đỏ không dùng/đổi được.
 */
data class StreakGap(val firstDay: Long, val lastDay: Long, val streakAtRisk: Int) {
    val days: Int get() = (lastDay - firstDay + 1).toInt()
}

data class StreakState(
    val red: Int = 0,
    val white: Int = 0,
    val blue: Int = 2,
    val redProgress: Int = 0,
    val whiteNeed: Int = StreakStore.WHITE_BASE,
    val blueProgress: Int = 0,
    val blueNeed: Int = StreakStore.BLUE_BASE,
    val todaySec: Long = 0,
    val todayReqSec: Long = StreakStore.BASE_SEC,
    val todayCounted: Boolean = false,
    val gap: StreakGap? = null,
    val novemberLock: Boolean = false,
) {
    val todayMet get() = todaySec >= todayReqSec

    /** Số giây mục tiêu hôm nay sẽ giảm nếu dùng 1 lửa trắng bây giờ. */
    val whiteCutSec: Long get() = ((blue + white) * 60.0 + red * 60.0 / 100.0).roundToLong()
    val reqAfterWhite: Long get() = max(StreakStore.MIN_SEC, todayReqSec - whiteCutSec)

    /** Lý do không dùng được lửa trắng (null = dùng được). */
    val whiteBlockReason: String?
        get() = when {
            white <= 0 -> "Chưa có lửa trắng"
            novemberLock -> "Tháng 11 không dùng được lửa trắng"
            todayMet -> "Hôm nay đã xem đủ rồi"
            todayReqSec <= StreakStore.MIN_SEC -> "Mục tiêu hôm nay đã ở mức tối thiểu 1 phút"
            else -> null
        }
}

object StreakStore {
    const val BASE_SEC = 30 * 60L
    const val MIN_SEC = 60L
    const val WHITE_BASE = 7
    const val BLUE_BASE = 3
    const val EXCHANGE_COST = 3

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(StreakState())
    val state: StateFlow<StreakState> = _state.asStateFlow()

    /** Mốc bắt đầu một ngày mới của chuỗi: 1:30 sáng (xem lúc 1:00 vẫn tính cho ngày hôm trước). */
    const val DAY_START_MINUTES = 90L

    /** "Ngày của chuỗi" hiện tại: đổi ngày lúc 1:30 sáng thay vì nửa đêm. */
    fun todayDate(): LocalDate = LocalDateTime.now().minusMinutes(DAY_START_MINUTES).toLocalDate()

    fun today(): Long = todayDate().toEpochDay()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("streak", Context.MODE_PRIVATE)
        if (!prefs.contains(K_CHECKED)) {
            // Khởi đầu: 0 đỏ, 0 trắng, 2 xanh; bắt đầu tính từ hôm nay
            val t = today()
            prefs.edit()
                .putLong(K_START, t).putLong(K_CHECKED, t - 1)
                .putInt(K_RED, 0).putInt(K_WHITE, 0).putInt(K_BLUE, 2)
                .putInt(K_RP, 0).putInt(K_WN, WHITE_BASE).putInt(K_BP, 0).putInt(K_BN, BLUE_BASE)
                .apply()
        }
        refresh()
    }

    // ─────────────────────────── Bộ đếm ───────────────────────────

    private class C(
        var red: Int, var white: Int, var blue: Int,
        var rp: Int, var wn: Int, var bp: Int, var bn: Int,
        var checked: Long,
    )

    private fun load() = C(
        prefs.getInt(K_RED, 0), prefs.getInt(K_WHITE, 0), prefs.getInt(K_BLUE, 2),
        prefs.getInt(K_RP, 0), prefs.getInt(K_WN, WHITE_BASE), prefs.getInt(K_BP, 0), prefs.getInt(K_BN, BLUE_BASE),
        prefs.getLong(K_CHECKED, today() - 1),
    )

    private fun save(c: C, extra: SharedPreferences.Editor.() -> Unit = {}) {
        prefs.edit().apply {
            putInt(K_RED, c.red); putInt(K_WHITE, c.white); putInt(K_BLUE, c.blue)
            putInt(K_RP, c.rp); putInt(K_WN, c.wn); putInt(K_BP, c.bp); putInt(K_BN, c.bn)
            putLong(K_CHECKED, c.checked)
            extra()
            // Bỏ dữ liệu của những ngày đã chốt xong (giữ lại hôm nay để còn hiển thị)
            val t = today()
            prefs.all.keys.forEach { k ->
                val day = (k.removePrefix(K_SEC).takeIf { k.startsWith(K_SEC) }
                    ?: k.removePrefix(K_REQ).takeIf { k.startsWith(K_REQ) })?.toLongOrNull()
                if (day != null && day <= c.checked && day < t) remove(k)
            }
        }.apply()
    }

    private fun sec(day: Long) = prefs.getLong(K_SEC + day, 0)
    private fun req(day: Long) = prefs.getLong(K_REQ + day, BASE_SEC)

    /** Một ngày được tính (xem đủ hoặc được lửa xanh bù) → +1 đỏ, có thể kéo theo trắng/xanh. */
    private fun credit(c: C, msgs: MutableList<String>) {
        c.red++
        c.rp++
        if (c.rp >= c.wn) {
            c.rp = 0
            c.white++
            c.wn++
            msgs += "⚪ Nhận 1 lửa trắng!"
            c.bp++
            if (c.bp >= c.bn) {
                c.bp = 0
                c.blue++
                c.bn++
                msgs += "🔵 Nhận 1 lửa xanh!"
            }
        }
    }

    // ─────────────────────────── Chốt sổ ───────────────────────────

    /** Duyệt các ngày chưa chốt: cộng lửa cho ngày đủ, dừng lại ở ngày bị lỡ để hỏi người dùng. */
    @Synchronized
    fun refresh(): List<String> {
        if (!::prefs.isInitialized) return emptyList()
        val c = load()
        val today = today()
        val msgs = mutableListOf<String>()
        var gap: StreakGap? = null
        var d = c.checked + 1
        while (d <= today) {
            if (sec(d) >= req(d)) {
                credit(c, msgs); c.checked = d; d++; continue
            }
            if (d == today) break // hôm nay vẫn còn thời gian
            var end = d
            while (end + 1 < today && sec(end + 1) < req(end + 1)) end++
            if (c.red == 0) { // chuỗi đang 0 thì không có gì để mất
                c.rp = 0; c.checked = end; d = end + 1; continue
            }
            gap = StreakGap(d, end, c.red)
            break
        }
        save(c)
        publish(c, gap)
        return msgs
    }

    private fun publish(c: C, gap: StreakGap?) {
        val t = today()
        _state.value = StreakState(
            red = c.red, white = c.white, blue = c.blue,
            redProgress = c.rp, whiteNeed = c.wn, blueProgress = c.bp, blueNeed = c.bn,
            todaySec = sec(t), todayReqSec = req(t), todayCounted = c.checked >= t,
            gap = gap,
            novemberLock = todayDate().monthValue == 11,
        )
    }

    // ─────────────────────────── Hành động ───────────────────────────

    /** Cộng thời gian xem. Trả về thông báo khi hôm nay vừa đạt mục tiêu (hoặc vừa nhận thưởng). */
    @Synchronized
    fun addWatch(seconds: Long): List<String> {
        if (!::prefs.isInitialized || seconds <= 0) return emptyList()
        val t = today()
        val before = sec(t) >= req(t)
        prefs.edit().putLong(K_SEC + t, sec(t) + seconds).apply()
        val msgs = refresh().toMutableList()
        val s = _state.value
        if (!before && s.todayMet) {
            msgs.add(0, if (s.todayCounted) "🔥 Xem đủ hôm nay! Chuỗi: ${s.red} lửa đỏ" else "🔥 Đã xem đủ mục tiêu hôm nay")
        }
        return msgs
    }

    /** Xử lý chuỗi bị đứt: dùng lửa xanh để bù, hoặc chấp nhận về 0. */
    @Synchronized
    fun resolveGap(useBlue: Boolean): List<String> {
        val g = _state.value.gap ?: return emptyList()
        val c = load()
        val msgs = mutableListOf<String>()
        if (useBlue && c.blue >= g.days) {
            c.blue -= g.days
            c.bn = BLUE_BASE
            repeat(g.days) { credit(c, msgs) }
            msgs.add(0, "🔵 Đã dùng ${g.days} lửa xanh — chuỗi được giữ")
        } else {
            c.red = 0
            c.rp = 0
            msgs.add(0, "Chuỗi lửa đỏ đã về 0")
        }
        c.checked = g.lastDay
        save(c)
        return msgs + refresh()
    }

    /** Dùng 1 lửa trắng để giảm mục tiêu xem của hôm nay. */
    @Synchronized
    fun useWhite(): List<String> {
        val s = _state.value
        if (s.whiteBlockReason != null) return emptyList()
        val newReq = s.reqAfterWhite
        val c = load()
        c.white--
        c.wn = WHITE_BASE
        save(c) { putLong(K_REQ + today(), newReq) }
        val msgs = mutableListOf("⚪ Mục tiêu hôm nay còn ${formatWatch(newReq)}")
        return msgs + refresh()
    }

    /** Đổi 3 lửa trắng → 1 lửa xanh (một chiều). */
    @Synchronized
    fun exchange(): List<String> {
        val c = load()
        if (c.white < EXCHANGE_COST) return emptyList()
        c.white -= EXCHANGE_COST
        c.wn = WHITE_BASE
        c.blue++
        c.bn++
        save(c)
        return listOf("🔵 Đã đổi $EXCHANGE_COST lửa trắng lấy 1 lửa xanh") + refresh()
    }

    private const val K_START = "start"
    private const val K_CHECKED = "checked"
    private const val K_RED = "red"
    private const val K_WHITE = "white"
    private const val K_BLUE = "blue"
    private const val K_RP = "red_progress"
    private const val K_WN = "white_need"
    private const val K_BP = "blue_progress"
    private const val K_BN = "blue_need"
    private const val K_SEC = "sec_"
    private const val K_REQ = "req_"
}

/** 1800 → "30 phút", 1794 → "29 phút 54 giây", 45 → "45 giây". */
fun formatWatch(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return when {
        m == 0L -> "$s giây"
        s == 0L -> "$m phút"
        else -> "$m phút $s giây"
    }
}
