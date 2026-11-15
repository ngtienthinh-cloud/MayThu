package vn.maythu.app.alarm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vn.maythu.app.data.AlarmItem

/** Trạng thái báo thức dùng chung giữa dịch vụ chạy nền và giao diện. */
object AlarmState {
    /** Báo thức đang reo (null nếu không). */
    var ringing by mutableStateOf<AlarmItem?>(null)
    var ringStartedAt by mutableLongStateOf(0L)

    /** true khi không phát được kênh và đang dùng chuông của máy. */
    var usingFallback by mutableStateOf(false)

    /** Tăng lên khi danh sách báo thức đổi từ chạy nền (ví dụ báo thức một lần đã reo). */
    var changeTick by mutableIntStateOf(0)

    /** Yêu cầu giao diện mở mục Báo thức. */
    var openAlarmsTick by mutableIntStateOf(0)

    /** Yêu cầu đóng app (hẹn giờ tắt). */
    var exitTick by mutableIntStateOf(0)
}
