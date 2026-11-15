package vn.maythu.app.ui

import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import vn.maythu.app.data.Channel
import vn.maythu.app.player.Playback
import vn.maythu.app.player.Status

// ============================== XEM TRUYỀN HÌNH ==============================

/**
 * Màn hình xem TV toàn màn hình.
 * Remote: ▲▼ (hoặc CH+/CH−) đổi kênh, OK mở danh sách kênh, BACK thoát.
 * Điện thoại: chạm để hiện nút điều khiển.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(vm: AppVm, isTv: Boolean, onClose: () -> Unit) {
    val ch = Playback.current
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    var showUi by remember { mutableStateOf(true) }
    var showList by remember { mutableStateOf(false) }
    var uiTick by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }

    DisposableEffect(activity, isTv) {
        val a = activity
        var ctrl: WindowInsetsControllerCompat? = null
        if (a != null) {
            if (!isTv) a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            ctrl = c
        }
        onDispose {
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            if (a != null && !isTv) a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(ch?.id) {
        showUi = true
        uiTick++
    }
    LaunchedEffect(showUi, uiTick, showList) {
        if (showUi && !showList && Playback.status != Status.ERROR) {
            delay(4_500)
            showUi = false
        }
    }
    LaunchedEffect(showList) {
        if (!showList) {
            delay(80)
            try {
                rootFocus.requestFocus()
            } catch (e: Exception) {
            }
        }
    }
    BackHandler(enabled = showList) { showList = false }

    if (ch == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val fav = vm.isFav(ch)
    val number = Playback.index + 1

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || showList) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> {
                        Playback.next()
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown -> {
                        Playback.prev()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                        showList = true
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        showUi = true
                        uiTick++
                        true
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        Playback.togglePlay()
                        showUi = true
                        uiTick++
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    player = Playback.player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { v -> v.player = null },
        )

        // lớp nhận chạm (điện thoại)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        showUi = !showUi
                        uiTick++
                    })
                }
        )

        when {
            Playback.status == Status.LOADING || (Playback.status == Status.PLAYING && Playback.buffering) -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Pal.Amber, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(Playback.statusText(), color = Pal.Text, fontSize = 15.sp)
                }
            }
            Playback.status == Status.ERROR -> {
                ErrorPanel(isTv = isTv, modifier = Modifier.align(Alignment.Center), onRetry = { Playback.play(ch) })
            }
            else -> Unit
        }

        AnimatedVisibility(
            visible = showUi || Playback.status == Status.ERROR,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // dải thông tin phía trên
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                        .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 28.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isTv) {
                        RoundIcon(Ic.Back, "Quay lại", onClose, size = 44.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (number > 0) {
                        Box(
                            Modifier
                                .background(Pal.Amber, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(number.toString(), color = Pal.AmberInk, fontWeight = FontWeight.Bold, fontSize = if (isTv) 20.sp else 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    LogoTile(ch, Modifier.size(if (isTv) 56.dp else 44.dp), textSize = 14.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ch.name,
                            color = Pal.Text,
                            fontSize = if (isTv) 24.sp else 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Pal.Red, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Trực tiếp, " + ch.subtitle,
                                color = Pal.Muted,
                                fontSize = if (isTv) 15.sp else 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!isTv) {
                        RoundIcon(
                            if (fav) Ic.Star else Ic.StarBorder,
                            if (fav) "Bỏ khỏi yêu thích" else "Thêm vào yêu thích",
                            { vm.toggleFav(ch) },
                            size = 44.dp,
                            tint = if (fav) Pal.Amber else Pal.Text,
                        )
                        RoundIcon(Ic.ListIcon, "Danh sách kênh", { showList = true }, size = 44.dp)
                    }
                }

                // phía dưới
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 24.dp else 12.dp),
                ) {
                    if (isTv) {
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            KeyHint("▲ ▼", "Đổi kênh")
                            KeyHint("OK", "Danh sách kênh, thêm yêu thích")
                            KeyHint("BACK", "Thoát")
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundIcon(Ic.Prev, "Kênh trước", { Playback.prev() }, size = 52.dp)
                            Spacer(Modifier.width(28.dp))
                            RoundIcon(
                                if (Playback.isActive) Ic.Pause else Ic.Play,
                                if (Playback.isActive) "Tạm dừng" else "Phát",
                                { Playback.togglePlay() },
                                size = 62.dp,
                                filled = true,
                            )
                            Spacer(Modifier.width(28.dp))
                            RoundIcon(Ic.Next, "Kênh sau", { Playback.next() }, size = 52.dp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Dial(Playback.dialPosition, Modifier.fillMaxWidth().height(14.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = showList,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            ChannelListPanel(
                vm = vm,
                isTv = isTv,
                onPick = { picked ->
                    Playback.play(picked)
                    showList = false
                },
                onClose = { showList = false },
            )
        }
    }
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .background(Pal.Panel2, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(key, color = Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = Pal.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun ErrorPanel(isTv: Boolean, modifier: Modifier, onRetry: () -> Unit) {
    Column(
        modifier
            .padding(24.dp)
            .background(Pal.Ink.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Không phát được kênh này", color = Pal.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Nguồn có thể đã ngừng phát hoặc chặn truy cập từ Việt Nam.",
            color = Pal.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (isTv) {
            Text("Bấm ▲ ▼ để chuyển kênh khác, OK để mở danh sách kênh", color = Pal.Amber, fontSize = 15.sp, textAlign = TextAlign.Center)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextAction("Thử lại", onRetry, primary = false, icon = Ic.Refresh)
                TextAction("Kênh tiếp theo", { Playback.next() }, icon = Ic.Next)
            }
        }
    }
}

@Composable
private fun ChannelListPanel(vm: AppVm, isTv: Boolean, onPick: (Channel) -> Unit, onClose: () -> Unit) {
    val q = Playback.queue
    val cur = Playback.current
    val idx = q.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 3).coerceAtLeast(0))
    val curFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        try {
            curFocus.requestFocus()
        } catch (e: Exception) {
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(if (isTv) 420.dp else 340.dp)
                .fillMaxHeight()
                .background(Pal.Ink.copy(alpha = 0.96f))
                .padding(top = if (isTv) 24.dp else 12.dp)
        ) {
            Row(
                Modifier.padding(start = if (isTv) 28.dp else 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Danh sách kênh", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${q.size} kênh", color = Pal.Muted, fontSize = 13.sp)
                }
                if (cur != null) {
                    val fav = vm.isFav(cur)
                    TextAction(
                        if (fav) "Đã yêu thích" else "Yêu thích",
                        { vm.toggleFav(cur) },
                        primary = false,
                        icon = if (fav) Ic.Star else Ic.StarBorder,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = state,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = if (isTv) 16.dp else 6.dp, end = 10.dp, bottom = 20.dp),
            ) {
                itemsIndexed(q, key = { _, c -> c.id }) { i, c ->
                    ChannelRow(
                        ch = c,
                        onClick = { onPick(c) },
                        number = i + 1,
                        modifier = if (i == idx) Modifier.focusRequester(curFocus) else Modifier,
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
        )
    }
}

// ============================== NGHE RADIO ==============================

/** Màn hình đang phát của radio: logo lớn, tên bài hát, thang dò sóng, điều khiển. */
@Composable
fun RadioPlayerScreen(vm: AppVm, isTv: Boolean, onClose: () -> Unit) {
    val ch = Playback.current
    val playFocus = remember { FocusRequester() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) {
        delay(160)
        try {
            playFocus.requestFocus()
        } catch (e: Exception) {
        }
    }
    if (ch == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val fav = vm.isFav(ch)
    val sleepLeft = if (Playback.sleepEndsAt > 0L) ((Playback.sleepEndsAt - now) / 60_000L + 1).coerceAtLeast(1L) else 0L
    val title = Playback.statusText()
    val songShown = Playback.nowTitle.isNotEmpty() && Playback.status == Status.PLAYING && !Playback.buffering
    val pos = if (Playback.queue.size > 1 && Playback.index >= 0) "Đài ${Playback.index + 1} / ${Playback.queue.size}" else ""

    val sleepAction = {
        val m = Playback.cycleSleep()
        vm.message = if (m > 0) "Sẽ tự tắt sau $m phút" else "Đã hủy hẹn giờ tắt"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Pal.Panel2, Pal.Ink, Pal.Ink)))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.ChannelUp -> {
                        Playback.next()
                        true
                    }
                    Key.ChannelDown -> {
                        Playback.prev()
                        true
                    }
                    Key.MediaPlayPause -> {
                        Playback.togglePlay()
                        true
                    }
                    else -> false
                }
            }
    ) {
        if (isTv) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 72.dp, vertical = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogoTile(ch, Modifier.size(300.dp), corner = 28.dp, textSize = 64.sp, pad = 28.dp)
                Spacer(Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    Text("Đang phát", color = Pal.Muted, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(ch.name, color = Pal.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (songShown) "♪ $title" else title,
                        color = if (songShown) Pal.Amber else Pal.Muted,
                        fontSize = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(ch.subtitle, color = Pal.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(28.dp))
                    Dial(Playback.dialPosition, Modifier.fillMaxWidth().height(18.dp))
                    if (pos.isNotEmpty()) Text(pos, color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(28.dp))
                    RadioControls(playFocus, big = 76.dp)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TextAction(
                            if (fav) "Đã yêu thích" else "Thêm vào yêu thích",
                            { vm.toggleFav(ch) },
                            primary = false,
                            icon = if (fav) Ic.Star else Ic.StarBorder,
                        )
                        TextAction(
                            if (sleepLeft > 0) "Tắt sau $sleepLeft phút" else "Hẹn giờ tắt",
                            sleepAction,
                            primary = false,
                            icon = Ic.Timer,
                        )
                        TextAction("Quay lại", onClose, primary = false, icon = Ic.Back)
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIcon(Ic.ChevronDown, "Thu nhỏ", onClose, size = 46.dp)
                    Text(
                        "Đang phát",
                        color = Pal.Muted,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    RoundIcon(
                        if (fav) Ic.Star else Ic.StarBorder,
                        if (fav) "Bỏ khỏi yêu thích" else "Thêm vào yêu thích",
                        { vm.toggleFav(ch) },
                        size = 46.dp,
                        tint = if (fav) Pal.Amber else Pal.Text,
                    )
                }
                Spacer(Modifier.weight(1f))
                LogoTile(
                    ch,
                    Modifier
                        .fillMaxWidth(0.74f)
                        .aspectRatio(1f),
                    corner = 28.dp,
                    textSize = 56.sp,
                    pad = 24.dp,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    ch.name,
                    color = Pal.Text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (songShown) "♪ $title" else title,
                    color = if (songShown) Pal.Amber else Pal.Muted,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(24.dp))
                Dial(Playback.dialPosition, Modifier.fillMaxWidth().height(16.dp))
                if (pos.isNotEmpty()) Text(pos, color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(24.dp))
                RadioControls(playFocus, big = 74.dp)
                Spacer(Modifier.height(22.dp))
                TextAction(
                    if (sleepLeft > 0) "Tự tắt sau $sleepLeft phút" else "Hẹn giờ tắt",
                    sleepAction,
                    primary = false,
                    icon = Ic.Timer,
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RadioControls(playFocus: FocusRequester, big: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Ic.Prev, "Đài trước", { Playback.prev() }, size = 58.dp)
        Spacer(Modifier.width(26.dp))
        Box(contentAlignment = Alignment.Center) {
            RoundIcon(
                if (Playback.isActive) Ic.Pause else Ic.Play,
                if (Playback.isActive) "Dừng" else "Phát",
                { Playback.togglePlay() },
                modifier = Modifier.focusRequester(playFocus),
                size = big,
                filled = true,
            )
            if (Playback.status == Status.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(big + 10.dp),
                    color = Pal.Amber,
                    strokeWidth = 2.dp,
                )
            }
        }
        Spacer(Modifier.width(26.dp))
        RoundIcon(Ic.Next, "Đài sau", { Playback.next() }, size = 58.dp)
    }
}
