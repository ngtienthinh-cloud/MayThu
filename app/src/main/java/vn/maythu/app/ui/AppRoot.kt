package vn.maythu.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vn.maythu.app.data.Channel
import vn.maythu.app.data.Kind
import vn.maythu.app.player.Playback
import vn.maythu.app.player.Status

enum class AppTab(val title: String) {
    HOME("Trang chủ"), TV("Truyền hình"), RADIO("Radio"), FAV("Yêu thích"), MORE("Cài đặt")
}

enum class Overlay { NONE, VIDEO, RADIO, SEARCH }

fun tabIcon(t: AppTab): ImageVector = when (t) {
    AppTab.HOME -> Ic.Home
    AppTab.TV -> Ic.Tv
    AppTab.RADIO -> Ic.Radio
    AppTab.FAV -> Ic.Star
    AppTab.MORE -> Ic.Settings
}

/** Cách mở các màn hình, truyền xuống các tab. */
class Nav(
    val play: (Channel, List<Channel>) -> Unit,
    val openNowPlaying: () -> Unit,
    val openSearch: () -> Unit,
    val goTab: (AppTab) -> Unit,
)

@Composable
fun AppRoot(vm: AppVm, detectedTv: Boolean) {
    val isTv = when (vm.uiMode) {
        "tv" -> true
        "phone" -> false
        else -> detectedTv
    }
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    val holder = rememberSaveableStateHolder()
    val ctx = LocalContext.current

    val nav = remember {
        Nav(
            play = { ch, list ->
                Playback.play(ch, list)
                overlay = if (ch.kind == Kind.TV) Overlay.VIDEO else Overlay.RADIO
            },
            openNowPlaying = {
                val cur = Playback.current
                if (cur != null) overlay = if (cur.kind == Kind.TV) Overlay.VIDEO else Overlay.RADIO
            },
            openSearch = { overlay = Overlay.SEARCH },
            goTab = { tab = it },
        )
    }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    BackHandler(enabled = overlay != Overlay.NONE || tab != AppTab.HOME) {
        if (overlay != Overlay.NONE) {
            if (overlay == Overlay.VIDEO) Playback.closeIfVideo()
            overlay = Overlay.NONE
        } else {
            tab = AppTab.HOME
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Pal.Ink)
    ) {
        when (overlay) {
            Overlay.NONE -> holder.SaveableStateProvider("shell") {
                if (isTv) TvShell(vm, tab, nav, holder) else PhoneShell(vm, tab, nav, holder)
            }
            Overlay.VIDEO -> VideoPlayerScreen(
                vm = vm,
                isTv = isTv,
                onClose = {
                    Playback.closeIfVideo()
                    overlay = Overlay.NONE
                },
            )
            Overlay.RADIO -> RadioPlayerScreen(vm = vm, isTv = isTv, onClose = { overlay = Overlay.NONE })
            Overlay.SEARCH -> holder.SaveableStateProvider("search") {
                SearchScreen(vm = vm, isTv = isTv, nav = nav, onClose = { overlay = Overlay.NONE })
            }
        }
    }
}

@Composable
private fun TabContent(tab: AppTab, vm: AppVm, isTv: Boolean, nav: Nav) {
    when (tab) {
        AppTab.HOME -> HomeTab(vm, isTv, nav)
        AppTab.TV -> TvTab(vm, isTv, nav)
        AppTab.RADIO -> RadioTab(vm, isTv, nav)
        AppTab.FAV -> FavTab(vm, isTv, nav)
        AppTab.MORE -> SettingsTab(vm, isTv, nav)
    }
}

// ======================= ĐIỆN THOẠI =======================

@Composable
private fun PhoneShell(vm: AppVm, tab: AppTab, nav: Nav, holder: SaveableStateHolder) {
    Scaffold(
        containerColor = Pal.Ink,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Pal.Ink)
                    .statusBarsPadding()
                    .height(58.dp)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(30.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (tab == AppTab.HOME) "Máy Thu" else tab.title,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Pal.Text,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = nav.openSearch) {
                    Icon(Ic.Search, contentDescription = "Tìm kiếm", tint = Pal.Text)
                }
            }
        },
        bottomBar = {
            Column {
                if (Playback.current != null) MiniPlayer(onOpen = nav.openNowPlaying)
                NavigationBar(containerColor = Pal.Panel, contentColor = Pal.Text, tonalElevation = 0.dp) {
                    for (t in AppTab.entries) {
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { nav.goTab(t) },
                            icon = { Icon(tabIcon(t), contentDescription = t.title) },
                            label = { Text(t.title, fontSize = 11.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Pal.AmberInk,
                                selectedTextColor = Pal.Amber,
                                indicatorColor = Pal.Amber,
                                unselectedIconColor = Pal.Muted,
                                unselectedTextColor = Pal.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            holder.SaveableStateProvider("tab-" + tab.name) {
                TabContent(tab, vm, false, nav)
            }
        }
    }
}

/** Thanh "đang phát" nhỏ phía trên thanh điều hướng (điện thoại). */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val ch = Playback.current ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(Pal.Panel2)
    ) {
        Dial(
            position = Playback.dialPosition,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .padding(horizontal = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoTile(ch, Modifier.size(44.dp), textSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ch.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Pal.Text,
                )
                val sub = Playback.statusText()
                Text(
                    text = if (Playback.nowTitle.isNotEmpty() && Playback.status == Status.PLAYING) "♪ $sub" else sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    color = if (Playback.nowTitle.isNotEmpty() && Playback.status == Status.PLAYING) Pal.Amber else Pal.Muted,
                )
            }
            IconButton(onClick = { Playback.togglePlay() }) {
                Icon(
                    imageVector = if (Playback.isActive) Ic.Pause else Ic.Play,
                    contentDescription = if (Playback.isActive) "Dừng" else "Phát",
                    tint = Pal.Amber,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = { Playback.close() }) {
                Icon(Ic.Close, contentDescription = "Tắt", tint = Pal.Muted)
            }
        }
    }
}

// ======================= ANDROID TV =======================

@Composable
private fun TvShell(vm: AppVm, tab: AppTab, nav: Nav, holder: SaveableStateHolder) {
    val railFocus = remember { AppTab.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        delay(150)
        try {
            railFocus.getValue(tab).requestFocus()
        } catch (e: Exception) {
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(232.dp)
                .fillMaxHeight()
                .background(Pal.Panel)
                .padding(start = 28.dp, end = 16.dp, top = 28.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(34.dp)
                Spacer(Modifier.width(10.dp))
                Text("Máy Thu", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Text)
            }
            Spacer(Modifier.height(28.dp))
            for (t in AppTab.entries) {
                RailItem(
                    icon = tabIcon(t),
                    title = t.title,
                    selected = t == tab,
                    onClick = { nav.goTab(t) },
                    modifier = Modifier.focusRequester(railFocus.getValue(t)),
                )
                Spacer(Modifier.height(4.dp))
            }
            RailItem(icon = Ic.Search, title = "Tìm kiếm", selected = false, onClick = nav.openSearch)
            Spacer(Modifier.weight(1f))
            if (Playback.current != null) NowPlayingCard(onOpen = nav.openNowPlaying)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 28.dp, top = 24.dp, end = 40.dp)
        ) {
            holder.SaveableStateProvider("tab-" + tab.name) {
                TabContent(tab, vm, true, nav)
            }
        }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Pal.AmberDim else Color.Transparent,
        focusedColor = Pal.Amber,
        scaleOnFocus = 1.04f,
        borderOnFocus = false,
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val c = when {
                focused -> Pal.AmberInk
                selected -> Pal.Amber
                else -> Pal.Muted
            }
            Icon(icon, contentDescription = null, tint = c, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                color = if (focused) Pal.AmberInk else if (selected) Pal.Amber else Pal.Text,
                fontSize = 16.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun NowPlayingCard(onOpen: () -> Unit) {
    val ch = Playback.current ?: return
    FocusBox(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Pal.Panel2,
        focusedColor = Pal.Panel2,
        scaleOnFocus = 1.04f,
    ) { _ ->
        Column(Modifier.padding(12.dp)) {
            Text("Đang phát", fontSize = 12.sp, color = Pal.Muted)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoTile(ch, Modifier.size(40.dp), textSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text)
                    Text(Playback.statusText(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Pal.Muted)
                }
                if (Playback.status == Status.PLAYING) Eq()
            }
        }
    }
}
