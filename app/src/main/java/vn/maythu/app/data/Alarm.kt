package vn.maythu.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

enum class AlarmAction { PLAY, STOP }

/**
 * Một lịch hẹn: bật kênh (báo thức bằng radio/TV) hoặc tắt app.
 * days: bit 0 = Thứ Hai … bit 6 = Chủ Nhật; 0 = không lặp.
 * date: "yyyy-MM-dd" khi hẹn đúng một ngày cụ thể.
 */
data class AlarmItem(
    val id: Int,
    val enabled: Boolean = true,
    val action: AlarmAction = AlarmAction.PLAY,
    val hour: Int = 6,
    val minute: Int = 0,
    val days: Int = 0,
    val date: String = "",
    val channel: Channel? = null,
    val volume: Int = 70,
    val ramp: Boolean = true,
    val autoStopMin: Int = 60,
    val label: String = "",
) {
    val isOneTime: Boolean
        get() = days == 0

    val timeText: String
        get() = String.format(Locale.US, "%02d:%02d", hour, minute)

    fun repeatText(): String = when {
        date.isNotBlank() -> "Một lần, " + (AlarmTime.dateText(date) ?: date)
        days == 0 -> "Một lần"
        days == ALL -> "Hằng ngày"
        days == WEEKDAYS -> "Thứ 2 đến thứ 6"
        days == WEEKEND -> "Thứ 7 và Chủ nhật"
        else -> (0..6).filter { (days and (1 shl it)) != 0 }.joinToString(", ") { AlarmTime.SHORT[it] }
    }

    fun nextTrigger(now: Long = System.currentTimeMillis()): Long? = AlarmTime.next(this, now)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("enabled", enabled)
        o.put("action", action.name)
        o.put("hour", hour)
        o.put("minute", minute)
        o.put("days", days)
        o.put("date", date)
        o.put("volume", volume)
        o.put("ramp", ramp)
        o.put("autoStop", autoStopMin)
        o.put("label", label)
        val ch = channel
        if (ch != null) o.put("channel", ch.toJson())
        return o
    }

    companion object {
        const val ALL = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000

        fun fromJson(o: JSONObject): AlarmItem? = try {
            val chObj = o.optJSONObject("channel")
            AlarmItem(
                id = o.getInt("id"),
                enabled = o.optBoolean("enabled", true),
                action = if (o.optString("action") == "STOP") AlarmAction.STOP else AlarmAction.PLAY,
                hour = o.optInt("hour", 6).coerceIn(0, 23),
                minute = o.optInt("minute", 0).coerceIn(0, 59),
                days = o.optInt("days", 0) and ALL,
                date = o.optString("date", ""),
                channel = if (chObj == null) null else Channel.fromJson(chObj),
                volume = o.optInt("volume", 70).coerceIn(10, 100),
                ramp = o.optBoolean("ramp", true),
                autoStopMin = o.optInt("autoStop", 60),
                label = o.optString("label", ""),
            )
        } catch (e: Exception) {
            null
        }

        fun listToJson(list: List<AlarmItem>): String {
            val a = JSONArray()
            for (x in list) a.put(x.toJson())
            return a.toString()
        }

        fun listFromJson(s: String?): List<AlarmItem> {
            if (s.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(s)
                val out = ArrayList<AlarmItem>()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val x = fromJson(o) ?: continue
                    out.add(x)
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/** Tính giờ reo và hiển thị ngày giờ kiểu Việt Nam. */
object AlarmTime {
    val SHORT = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
    val LONG = listOf("Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật")

    /** 0 = Thứ Hai … 6 = Chủ Nhật */
    fun dayIndex(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun parseDate(s: String): Calendar? {
        val p = s.trim().split('-')
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d, 0, 0, 0)
        return c
    }

    fun formatDate(c: Calendar): String = String.format(
        Locale.US, "%04d-%02d-%02d",
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
    )

    fun longDate(c: Calendar): String = LONG[dayIndex(c)] + ", " + String.format(
        Locale.US, "%02d/%02d/%04d",
        c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR),
    )

    fun dateText(s: String): String? = parseDate(s)?.let { longDate(it) }

    fun next(a: AlarmItem, now: Long): Long? {
        if (a.date.isNotBlank()) {
            val c = parseDate(a.date) ?: return null
            c.set(Calendar.HOUR_OF_DAY, a.hour)
            c.set(Calendar.MINUTE, a.minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return if (c.timeInMillis > now) c.timeInMillis else null
        }
        val base = Calendar.getInstance()
        base.timeInMillis = now
        base.set(Calendar.HOUR_OF_DAY, a.hour)
        base.set(Calendar.MINUTE, a.minute)
        base.set(Calendar.SECOND, 0)
        base.set(Calendar.MILLISECOND, 0)
        if (a.days == 0) {
            if (base.timeInMillis <= now) base.add(Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }
        for (i in 0..7) {
            val c = base.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, i)
            if (c.timeInMillis > now && (a.days and (1 shl dayIndex(c))) != 0) return c.timeInMillis
        }
        return null
    }

    /** "Còn 7 giờ 12 phút" */
    fun untilText(t: Long, now: Long = System.currentTimeMillis()): String {
        val mins = ((t - now + 59_999L) / 60_000L).coerceAtLeast(0L)
        val d = mins / 1440
        val h = (mins % 1440) / 60
        val m = mins % 60
        return when {
            d > 0 -> "Còn $d ngày $h giờ"
            h > 0 -> "Còn $h giờ $m phút"
            else -> "Còn $m phút"
        }
    }

    /** "06:30 sáng mai", "06:30 hôm nay", "06:30 Thứ Hai 17/11" */
    fun whenText(t: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        val today = Calendar.getInstance()
        today.timeInMillis = now
        val time = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val sameYear = c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        val dayDiff = if (sameYear) c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR) else 99
        val day = when (dayDiff) {
            0 -> "hôm nay"
            1 -> "ngày mai"
            else -> LONG[dayIndex(c)] + " " + String.format(Locale.US, "%02d/%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
        }
        return "$time $day"
    }
}
