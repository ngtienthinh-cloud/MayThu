package vn.maythu.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import vn.maythu.app.alarm.AlarmService
import vn.maythu.app.alarm.AlarmState
import vn.maythu.app.data.AlarmAction
import vn.maythu.app.data.AlarmItem
import vn.maythu.app.data.AlarmTime
import vn.maythu.app.data.Channel
import vn.maythu.app.data.Kind
import vn.maythu.app.player.Playback
import java.util.Calendar
import java.util.Locale

// ============================== QUYỀN CẦN CHO BÁO THỨC ==============================

private data class PermState(val exact: Boolean, val notif: Boolean, val fullScreen: Boolean, val battery: Boolean)

private fun checkPerms(ctx: Context): PermState {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val exact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    val notif = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val full = if (Build.VERSION.SDK_INT >= 34) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
    } else {
        true
    }
    val battery = if (Build.VERSION.SDK_INT >= 23) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    } else {
        true
    }
    return PermState(exact, notif, full, battery)
}

private fun openSetting(ctx: Context, action: String, withPackage: Boolean): Boolean = try {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) i.data = Uri.parse("package:" + ctx.packageName)
    ctx.startActivity(i)
    true
} catch (e: Exception) {
    false
}

// ============================== MỤC BÁO THỨC ==============================

@Composable
fun AlarmTab(vm: AppVm, isTv: Boolean, nav: Nav) {
    val ctx = LocalContext.current
    LaunchedEffect(AlarmState.changeTick) { vm.reloadAlarms() }

    var resumeTick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val now by produceState(System.currentTimeMillis(), resumeTick) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val perms = remember(resumeTick, now) { checkPerms(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }
    val edge = if (isTv) 4.dp else 16.dp
    val sorted = vm.alarms.sortedBy { it.hour * 60 + it.minute }
    val next = vm.nextAlarm()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = edge, end = edge, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intro") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Pal.Panel, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Text("Báo thức bằng radio", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Đến giờ, app tự mở và phát kênh bạn chọn, âm lượng tăng dần. Có thể hẹn giờ tắt app. " +
                        "Hẹn theo giờ phút, theo thứ trong tuần hoặc theo ngày cụ thể.",
                    color = Pal.Muted, fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                TextAction("Thêm báo thức", { vm.newAlarm(AlarmAction.PLAY) }, icon = Ic.Alarm)
                Spacer(Modifier.height(10.dp))
                TextAction("Thêm hẹn giờ tắt app", { vm.newAlarm(AlarmAction.STOP) }, primary = false, icon = Ic.Power)
                if (next != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Sắp tới: " + AlarmTime.whenText(next.second, now) + ". " + AlarmTime.untilText(next.second, now),
                        color = Pal.Amber, fontSize = 14.sp,
                    )
                }
            }
        }

        if (!perms.exact) {
            item(key = "p-exact") {
                PermCard(
                    "Cần quyền báo thức đúng giờ",
                    "Không có quyền này, báo thức có thể reo trễ vài phút.",
                    "Cấp quyền",
                ) {
                    if (Build.VERSION.SDK_INT >= 31 && !openSetting(ctx, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true)) {
                        vm.message = "Máy này không có trang cài đặt quyền báo thức"
                    }
                }
            }
        }
        if (!perms.notif) {
            item(key = "p-notif") {
                PermCard(
                    "Cho phép thông báo",
                    "Để báo thức hiện nút Tắt và Báo lại trên màn hình khóa.",
                    "Cho phép",
                ) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        if (!perms.fullScreen) {
            item(key = "p-full") {
                PermCard(
                    "Cho phép hiện báo thức toàn màn hình",
                    "Để màn hình báo thức tự bật lên khi điện thoại đang khóa.",
                    "Mở cài đặt",
                ) {
                    if (Build.VERSION.SDK_INT >= 34 && !openSetting(ctx, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, true)) {
                        vm.message = "Máy này không có trang cài đặt này"
                    }
                }
            }
        }
        if (!perms.battery && vm.alarms.isNotEmpty()) {
            item(key = "p-battery") {
                PermCard(
                    "Nên tắt tối ưu pin cho Máy Thu",
                    "Một số máy (Xiaomi, Oppo, Vivo…) tự chặn app chạy nền khiến báo thức không reo. " +
                        "Hãy cho phép Máy Thu chạy nền và tự khởi động.",
                    "Mở cài đặt pin",
                ) {
                    val ok = Build.VERSION.SDK_INT >= 23 &&
                        (openSetting(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) ||
                            openSetting(ctx, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, false))
                    if (!ok) vm.message = "Máy này không có trang cài đặt pin"
                }
            }
        }

        if (sorted.isEmpty()) {
            item(key = "empty") {
                MessageBlock(
                    "Chưa có báo thức nào",
                    "Bấm \"Thêm báo thức\" để chọn giờ và kênh radio sẽ đánh thức bạn.",
                )
            }
        }
        items(sorted, key = { it.id }) { a ->
            AlarmCard(
                a = a,
                now = now,
                isTv = isTv,
                onClick = { vm.editingAlarm = a },
                onToggle = { on -> vm.toggleAlarm(a, on) },
            )
        }
        item(key = "tip") {
            Text(
                "Báo thức vẫn reo khi đã đóng app hoặc khởi động lại máy. Nếu lúc đó không có mạng, " +
                    "app sẽ phát chuông báo thức của máy để bạn không ngủ quên.",
                color = Pal.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PermCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Pal.Panel2, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Warning, contentDescription = null, tint = Pal.Amber, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Pal.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        TextAction(action, onAction, primary = false)
    }
}

@Composable
private fun AlarmCard(a: AlarmItem, now: Long, isTv: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val next = if (a.enabled) a.nextTrigger(now) else null
    FocusBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Pal.Panel,
        scaleOnFocus = 1.02f,
    ) { _ ->
        Row(
            Modifier.padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        a.timeText,
                        fontSize = if (isTv) 40.sp else 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (a.enabled) Pal.Text else Pal.Muted,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        a.repeatText(),
                        fontSize = 14.sp,
                        color = Pal.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (a.action == AlarmAction.PLAY) Ic.Radio else Ic.Power,
                        contentDescription = null,
                        tint = if (a.enabled) Pal.Amber else Pal.Muted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (a.action == AlarmAction.PLAY) "Bật " + (a.channel?.name ?: "(chưa chọn kênh)") else "Tắt app và dừng phát",
                        fontSize = 14.sp,
                        color = Pal.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (a.label.isNotBlank()) Text(a.label, fontSize = 13.sp, color = Pal.Muted, maxLines = 1)
                Text(
                    when {
                        next != null -> AlarmTime.untilText(next, now)
                        a.enabled -> "Thời điểm đã qua"
                        else -> "Đang tắt"
                    },
                    fontSize = 13.sp,
                    color = if (next != null) Pal.Amber else Pal.Muted,
                )
            }
            Switch(
                checked = a.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Pal.AmberInk,
                    checkedTrackColor = Pal.Amber,
                    uncheckedThumbColor = Pal.Muted,
                    uncheckedTrackColor = Pal.Panel2,
                    uncheckedBorderColor = Pal.Line,
                ),
            )
        }
    }
}

/** Thẻ "Báo thức tiếp theo" ở Trang chủ. */
@Composable
fun NextAlarmCard(vm: AppVm, edge: Dp, onOpen: () -> Unit) {
    val next = vm.nextAlarm()
    FocusBox(
        onClick = onOpen,
        modifier = Modifier
            .padding(horizontal = edge)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Pal.Panel,
        scaleOnFocus = 1.02f,
    ) { _ ->
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(Pal.AmberDim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Ic.Alarm, contentDescription = null, tint = Pal.Amber, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (next != null) {
                    val a = next.first
                    Text("Báo thức " + AlarmTime.whenText(next.second), color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (a.action == AlarmAction.PLAY) "Bật " + (a.channel?.name ?: "") else "Tắt app") + ". " + AlarmTime.untilText(next.second),
                        color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text("Báo thức bằng radio", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Thức dậy với VOV hay kênh yêu thích; hẹn giờ tắt app", color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Ic.ChevronRight, contentDescription = null, tint = Pal.Muted)
        }
    }
}

// ============================== TRÌNH ĐẶT BÁO THỨC ==============================

@Composable
private fun EditorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Pal.Panel, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(title, color = Pal.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun AlarmEditor(vm: AppVm, isTv: Boolean, initial: AlarmItem, onClose: () -> Unit) {
    var a by remember(initial.id) { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val exists = vm.alarms.any { it.id == initial.id }
    val side = if (isTv) 48.dp else 16.dp
    val firstFocus = remember { FocusRequester() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        delay(150)
        try {
            firstFocus.requestFocus()
        } catch (e: Exception) {
        }
    }
    val now = System.currentTimeMillis()
    val next = a.nextTrigger(now)
    val play = a.action == AlarmAction.PLAY

    val save: () -> Unit = {
        if (vm.saveAlarm(a)) {
            val needNotif = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            onClose()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = side, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Ic.Close, "Đóng", onClose, size = 46.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    exists -> "Sửa hẹn giờ"
                    play -> "Báo thức mới"
                    else -> "Hẹn giờ tắt mới"
                },
                color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextAction("Lưu", save, icon = Ic.Check)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = side, end = side, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "action") {
                EditorCard("Việc cần làm") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("Bật kênh (báo thức)", play, { a = a.copy(action = AlarmAction.PLAY) }, Modifier.focusRequester(firstFocus), icon = Ic.Alarm)
                        Pill("Tắt app", !play, { a = a.copy(action = AlarmAction.STOP) }, icon = Ic.Power)
                    }
                }
            }
            item(key = "time") {
                EditorCard("Giờ") {
                    TimeStepper(a.hour, a.minute) { h, m -> a = a.copy(hour = h, minute = m) }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Pill("−5 phút", false, { a = shiftMinutes(a, -5) }) }
                        item { Pill("+5 phút", false, { a = shiftMinutes(a, 5) }) }
                        for (m in listOf(0, 15, 30, 45)) {
                            item { Pill(String.format(Locale.US, ":%02d", m), a.minute == m, { a = a.copy(minute = m) }) }
                        }
                        item { Pill("Nhập giờ", false, { typing = true }, icon = Ic.Edit) }
                    }
                }
            }
            item(key = "repeat") {
                EditorCard("Lặp lại") {
                    RepeatEditor(a) { a = it }
                }
            }
            if (play) {
                item(key = "channel") {
                    EditorCard("Kênh sẽ phát") {
                        val ch = a.channel
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ch != null) {
                                LogoTile(ch, Modifier.size(52.dp), textSize = 15.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ch.name, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (ch.kind == Kind.TV) "Kênh truyền hình" else ch.subtitle, color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                Text("Chưa chọn kênh", color = Pal.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.width(10.dp))
                            TextAction(if (ch == null) "Chọn kênh" else "Đổi kênh", { picking = true }, primary = ch == null)
                        }
                    }
                }
                item(key = "sound") {
                    EditorCard("Âm lượng khi báo thức") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RoundIcon(Ic.Remove, "Giảm âm lượng", { a = a.copy(volume = (a.volume - 10).coerceAtLeast(10)) }, size = 46.dp)
                            Text(
                                "${a.volume}%",
                                color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.width(84.dp),
                            )
                            RoundIcon(Ic.Add, "Tăng âm lượng", { a = a.copy(volume = (a.volume + 10).coerceAtMost(100)) }, size = 46.dp)
                            Spacer(Modifier.width(14.dp))
                            Pill("Tăng dần", a.ramp, { a = a.copy(ramp = !a.ramp) })
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Tự tắt sau", color = Pal.Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((m, t) in listOf(15 to "15 phút", 30 to "30 phút", 60 to "1 giờ", 120 to "2 giờ", 0 to "Không tự tắt")) {
                                item { Pill(t, a.autoStopMin == m, { a = a.copy(autoStopMin = m) }) }
                            }
                        }
                    }
                }
            }
            item(key = "label") {
                EditorCard("Ghi chú (không bắt buộc)") {
                    OutlinedTextField(
                        value = a.label,
                        onValueChange = { a = a.copy(label = it.take(40)) },
                        singleLine = true,
                        placeholder = { Text(if (play) "Ví dụ: Dậy đi làm" else "Ví dụ: Tắt radio đi ngủ") },
                        colors = alarmFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "summary") {
                Text(
                    if (next != null) {
                        (if (play) "Sẽ bật kênh lúc " else "Sẽ tắt app lúc ") + AlarmTime.whenText(next, now) + ". " + AlarmTime.untilText(next, now)
                    } else {
                        "Thời điểm này đã qua, hãy chọn giờ hoặc ngày khác."
                    },
                    color = if (next != null) Pal.Amber else Pal.Red,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item(key = "buttons") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextAction("Lưu", save, icon = Ic.Check)
                    if (play) TextAction("Phát thử ngay", { vm.testAlarm(a) }, primary = false, icon = Ic.Play)
                    if (exists) {
                        TextAction("Xóa hẹn giờ này", {
                            vm.deleteAlarm(a)
                            onClose()
                        }, primary = false, icon = Ic.Delete)
                    }
                }
            }
        }
    }

    if (picking) {
        ChannelPickerDialog(
            vm = vm,
            onPick = {
                a = a.copy(channel = it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
    if (typing) {
        TimeInputDialog(
            hour = a.hour,
            minute = a.minute,
            onDone = { h, m ->
                a = a.copy(hour = h, minute = m)
                typing = false
            },
            onDismiss = { typing = false },
        )
    }
}

@Composable
private fun alarmFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Pal.Amber,
    unfocusedBorderColor = Pal.Line,
    cursorColor = Pal.Amber,
    focusedTextColor = Pal.Text,
    unfocusedTextColor = Pal.Text,
    focusedLabelColor = Pal.Amber,
    unfocusedLabelColor = Pal.Muted,
    focusedPlaceholderColor = Pal.Muted,
    unfocusedPlaceholderColor = Pal.Muted,
    focusedContainerColor = Pal.Ink,
    unfocusedContainerColor = Pal.Ink,
)

private fun shiftMinutes(a: AlarmItem, d: Int): AlarmItem {
    val total = ((a.hour * 60 + a.minute + d) % 1440 + 1440) % 1440
    return a.copy(hour = total / 60, minute = total % 60)
}

@Composable
private fun TimeStepper(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberWheel(hour, "giờ", { onChange((hour + 1) % 24, minute) }, { onChange((hour + 23) % 24, minute) })
        Text(
            ":",
            color = Pal.Text, fontSize = 52.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
        )
        NumberWheel(minute, "phút", { onChange(hour, (minute + 1) % 60) }, { onChange(hour, (minute + 59) % 60) })
    }
}

@Composable
private fun NumberWheel(value: Int, label: String, onUp: () -> Unit, onDown: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIcon(Ic.ChevronUp, "Tăng $label", onUp, size = 52.dp)
        Text(
            String.format(Locale.US, "%02d", value),
            color = Pal.Text, fontSize = 60.sp, fontWeight = FontWeight.Bold,
        )
        RoundIcon(Ic.ChevronDown, "Giảm $label", onDown, size = 52.dp)
        Text(label, color = Pal.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun TimeInputDialog(hour: Int, minute: Int, onDone: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%02d:%02d", hour, minute)) }
    var error by remember { mutableStateOf(false) }
    val confirm: () -> Unit = {
        val digits = text.filter { it.isDigit() }
        val parts = text.split(':', '.', 'h', 'g', ' ').filter { it.isNotBlank() }
        val h: Int?
        val m: Int?
        if (parts.size >= 2) {
            h = parts[0].toIntOrNull()
            m = parts[1].toIntOrNull()
        } else if (digits.length in 3..4) {
            h = digits.dropLast(2).toIntOrNull()
            m = digits.takeLast(2).toIntOrNull()
        } else {
            h = digits.toIntOrNull()
            m = 0
        }
        if (h != null && m != null && h in 0..23 && m in 0..59) {
            onDone(h, m)
        } else {
            error = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Panel,
        title = { Text("Nhập giờ", color = Pal.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(5)
                        error = false
                    },
                    singleLine = true,
                    label = { Text("Giờ:phút, ví dụ 05:45") },
                    isError = error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = alarmFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) Text("Giờ không hợp lệ", color = Pal.Red, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = confirm) { Text("Xong", color = Pal.Amber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy", color = Pal.Muted) } },
    )
}

@Composable
private fun RepeatEditor(a: AlarmItem, onChange: (AlarmItem) -> Unit) {
    val onceAuto = a.days == 0 && a.date.isBlank()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Pill("Một lần", onceAuto, { onChange(a.copy(days = 0, date = "")) }) }
        item { Pill("Hằng ngày", a.days == AlarmItem.ALL, { onChange(a.copy(days = AlarmItem.ALL, date = "")) }) }
        item { Pill("Thứ 2 đến thứ 6", a.days == AlarmItem.WEEKDAYS, { onChange(a.copy(days = AlarmItem.WEEKDAYS, date = "")) }) }
        item { Pill("Cuối tuần", a.days == AlarmItem.WEEKEND, { onChange(a.copy(days = AlarmItem.WEEKEND, date = "")) }) }
        item {
            Pill("Chọn ngày, tháng", a.date.isNotBlank(), {
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, a.hour)
                c.set(Calendar.MINUTE, a.minute)
                if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
                onChange(a.copy(days = 0, date = AlarmTime.formatDate(c)))
            }, icon = Ic.Edit)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Hoặc chọn từng thứ", color = Pal.Muted, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0..6) {
            item {
                Pill(AlarmTime.SHORT[i], (a.days and (1 shl i)) != 0, {
                    onChange(a.copy(days = a.days xor (1 shl i), date = ""))
                })
            }
        }
    }
    if (a.date.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        DateStepper(a.date) { onChange(a.copy(date = it)) }
    }
}

@Composable
private fun DateStepper(date: String, onChange: (String) -> Unit) {
    val c = AlarmTime.parseDate(date) ?: Calendar.getInstance()
    fun shift(field: Int, n: Int) {
        val x = c.clone() as Calendar
        x.add(field, n)
        onChange(AlarmTime.formatDate(x))
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Ic.ChevronLeft, "Lùi một ngày", { shift(Calendar.DAY_OF_YEAR, -1) }, size = 46.dp)
            Text(
                AlarmTime.longDate(c),
                color = Pal.Amber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            RoundIcon(Ic.ChevronRight, "Tới một ngày", { shift(Calendar.DAY_OF_YEAR, 1) }, size = 46.dp)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Pill("− 1 tháng", false, { shift(Calendar.MONTH, -1) }) }
            item { Pill("+ 1 tháng", false, { shift(Calendar.MONTH, 1) }) }
            item { Pill("+ 7 ngày", false, { shift(Calendar.DAY_OF_YEAR, 7) }) }
            item { Pill("+ 1 năm", false, { shift(Calendar.YEAR, 1) }) }
        }
    }
}

@Composable
private fun ChannelPickerDialog(vm: AppVm, onPick: (Channel) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.ensure("radio:vn") }
    val vn = vm.state("radio:vn")
    val sections = ArrayList<Pair<String, List<Channel>>>()
    val cur = Playback.current
    if (cur != null) sections.add("Đang phát" to listOf(cur))
    if (vm.favorites.isNotEmpty()) sections.add("Yêu thích" to vm.favorites.toList())
    if (vm.recent.isNotEmpty()) sections.add("Gần đây" to vm.recent.take(15))
    if (vn.items.isNotEmpty()) sections.add("Radio Việt Nam" to vn.items.take(80))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Panel,
        title = { Text("Chọn kênh hoặc đài", color = Pal.Text) },
        text = {
            LazyColumn(Modifier.height(420.dp)) {
                if (sections.isEmpty()) {
                    item(key = "loading") { LoadingLine("Đang tải danh sách đài…") }
                }
                sections.forEachIndexed { si, sec ->
                    item(key = "h$si") {
                        Text(sec.first, color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(sec.second, key = { "c$si-" + it.id }) { ch ->
                        ChannelRow(ch = ch, onClick = { onPick(ch) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng", color = Pal.Amber) } },
    )
}

// ============================== MÀN HÌNH BÁO THỨC ĐANG REO ==============================

@Composable
fun AlarmRingingScreen(vm: AppVm, isTv: Boolean, onHide: () -> Unit, onWatch: () -> Unit) {
    val a = AlarmState.ringing ?: return
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val stopFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(250)
        try {
            stopFocus.requestFocus()
        } catch (e: Exception) {
        }
    }
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    val ch = a.channel
    val ctx = LocalContext.current
    val status = when {
        AlarmState.usingFallback -> "Không phát được kênh, đang dùng chuông báo thức của máy"
        else -> Playback.statusText()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Pal.Panel2, Pal.Ink, Pal.Ink)))
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Alarm, contentDescription = null, tint = Pal.Amber, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(a.label.ifBlank { "Báo thức" }, color = Pal.Amber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)),
                color = Pal.Text,
                fontSize = if (isTv) 120.sp else 92.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(AlarmTime.longDate(cal), color = Pal.Muted, fontSize = 18.sp)
            Spacer(Modifier.height(26.dp))
            if (ch != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoTile(ch, Modifier.size(58.dp), textSize = 16.sp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(ch.name, color = Pal.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            status,
                            color = if (AlarmState.usingFallback) Pal.Red else Pal.Muted,
                            fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Dial(Playback.dialPosition, Modifier.width(280.dp).height(14.dp))
            }
            Spacer(Modifier.height(30.dp))
            TextAction("Tắt báo thức", { AlarmService.stopFromUi() }, Modifier.focusRequester(stopFocus), icon = Ic.Close)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextAction("Báo lại ${AlarmService.SNOOZE_MIN} phút", { AlarmService.snoozeFromUi(ctx) }, primary = false, icon = Ic.Timer)
                TextAction(
                    if (ch?.kind == Kind.TV) "Xem tiếp" else "Nghe tiếp",
                    {
                        AlarmService.keepFromUi()
                        if (ch?.kind == Kind.TV) onWatch() else onHide()
                    },
                    primary = false,
                    icon = Ic.Play,
                )
            }
        }
    }
}
