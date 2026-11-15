package vn.maythu.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import vn.maythu.app.data.AlarmAction
import vn.maythu.app.data.Store

/** Nhận tín hiệu khi tới giờ hẹn. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val ctx = context.applicationContext
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)
        val store = Store(ctx)
        val list = store.alarms()
        val a = list.firstOrNull { it.id == id } ?: return
        if (!snooze) {
            if (a.isOneTime) {
                store.saveAlarms(list.map { if (it.id == id) it.copy(enabled = false) else it })
            } else {
                AlarmScheduler.schedule(ctx, a)
            }
            AlarmState.changeTick++
        }
        if (a.action == AlarmAction.PLAY) AlarmService.start(ctx, a) else AlarmService.turnOffEverything()
    }
}

/** Đặt lại lịch sau khi khởi động máy, đổi giờ hệ thống hoặc cập nhật app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context.applicationContext)
    }
}
