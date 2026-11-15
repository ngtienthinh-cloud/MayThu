package vn.maythu.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import vn.maythu.app.MainActivity
import vn.maythu.app.data.AlarmAction
import vn.maythu.app.data.AlarmItem
import vn.maythu.app.data.Store

/** Đặt lịch với AlarmManager của Android để báo thức reo đúng giờ cả khi app đã tắt. */
object AlarmScheduler {
    const val ACTION_FIRE = "vn.maythu.app.alarm.FIRE"
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_SNOOZE = "snooze"
    private const val SNOOZE_OFFSET = 500_000

    private fun flags(): Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun firePi(ctx: Context, id: Int, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_SNOOZE, snooze)
        return PendingIntent.getBroadcast(ctx, if (snooze) id + SNOOZE_OFFSET else id, i, flags())
    }

    private fun manager(ctx: Context): AlarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canExact(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) manager(ctx).canScheduleExactAlarms() else true

    private fun setAt(ctx: Context, at: Long, op: PendingIntent, asAlarmClock: Boolean) {
        val am = manager(ctx)
        try {
            if (asAlarmClock) {
                // Hiện biểu tượng đồng hồ báo thức trên thanh trạng thái, reo cả khi máy ngủ sâu
                val show = PendingIntent.getActivity(
                    ctx, 7,
                    Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ALARMS, true),
                    flags(),
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, op)
            }
        } catch (e: SecurityException) {
            // Chưa có quyền báo thức chính xác: vẫn đặt, có thể lệch vài phút
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            else am.set(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    /** Đặt (hoặc đặt lại) lịch cho một báo thức. Trả về thời điểm reo, null nếu không đặt. */
    fun schedule(ctx: Context, a: AlarmItem): Long? {
        cancel(ctx, a.id)
        if (!a.enabled) return null
        val at = a.nextTrigger() ?: return null
        setAt(ctx, at, firePi(ctx, a.id, false), a.action == AlarmAction.PLAY)
        return at
    }

    fun cancel(ctx: Context, id: Int) {
        val am = manager(ctx)
        am.cancel(firePi(ctx, id, false))
        am.cancel(firePi(ctx, id, true))
    }

    fun snooze(ctx: Context, a: AlarmItem, minutes: Int) {
        setAt(ctx, System.currentTimeMillis() + minutes * 60_000L, firePi(ctx, a.id, true), true)
    }

    /** Sau khi khởi động lại máy, đổi giờ, cập nhật app: đặt lại toàn bộ lịch. */
    fun rescheduleAll(ctx: Context) {
        val store = Store(ctx)
        val list = store.alarms()
        val fixed = list.map { a -> if (a.enabled && a.nextTrigger() == null) a.copy(enabled = false) else a }
        if (fixed != list) store.saveAlarms(fixed)
        for (a in fixed) schedule(ctx, a)
    }
}
