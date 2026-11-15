package vn.maythu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vn.maythu.app.BuildConfig
import vn.maythu.app.data.Catalog
import vn.maythu.app.data.Channel
import vn.maythu.app.data.Kind
import vn.maythu.app.data.ListState

// ============================== TRANG CHỦ ==============================

@Composable
fun HomeTab(vm: AppVm, isTv: Boolean, nav: Nav) {
    LaunchedEffect(Unit) {
        vm.ensure("tv:vn")
        vm.ensure("radio:vn")
        vm.ensure("tv:news")
        vm.ensure("radio:pop")
    }
    val cardW = if (isTv) 176.dp else 148.dp
    val edge = if (isTv) 12.dp else 16.dp
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 22.dp else 18.dp),
    ) {
        if (vm.recent.isNotEmpty()) {
            item(key = "recent") {
                HomeRow("Xem và nghe gần đây", vm.recent.toList(), isTv, cardW, edge, nav, null)
            }
        }
        if (vm.favorites.isNotEmpty()) {
            item(key = "fav") {
                HomeRow("Yêu thích", vm.favorites.toList(), isTv, cardW, edge, nav, seeAll = { nav.goTab(AppTab.FAV) })
            }
        }
        item(key = "tvvn") {
            HomeListRow(vm, "tv:vn", "Truyền hình Việt Nam", isTv, cardW, edge, nav) {
                vm.selectTv("vn")
                nav.goTab(AppTab.TV)
            }
        }
        item(key = "radiovn") {
            HomeListRow(vm, "radio:vn", "Radio Việt Nam", isTv, cardW, edge, nav) {
                vm.selectRadio("vn")
                nav.goTab(AppTab.RADIO)
            }
        }
        item(key = "news") {
            HomeListRow(vm, "tv:news", "Tin tức thế giới", isTv, cardW, edge, nav) {
                vm.selectTv("news")
                nav.goTab(AppTab.TV)
            }
        }
        item(key = "pop") {
            HomeListRow(vm, "radio:pop", "Radio nhạc Pop", isTv, cardW, edge, nav) {
                vm.selectRadio("pop")
                nav.goTab(AppTab.RADIO)
            }
        }
    }
}

@Composable
private fun HomeListRow(
    vm: AppVm,
    key: String,
    title: String,
    isTv: Boolean,
    cardW: Dp,
    edge: Dp,
    nav: Nav,
    seeAll: () -> Unit,
) {
    val st = vm.state(key)
    HomeRow(
        title = title,
        items = st.items.take(24),
        isTv = isTv,
        cardW = cardW,
        edge = edge,
        nav = nav,
        seeAll = seeAll,
        loading = st.loading && st.items.isEmpty(),
        error = if (st.items.isEmpty()) st.error else null,
        onRetry = { vm.ensure(key, force = true) },
        fullList = st.items,
    )
}

@Composable
private fun HomeRow(
    title: String,
    items: List<Channel>,
    isTv: Boolean,
    cardW: Dp,
    edge: Dp,
    nav: Nav,
    seeAll: (() -> Unit)?,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    fullList: List<Channel> = items,
) {
    Column {
        SectionTitle(title, Modifier.padding(horizontal = edge))
        Spacer(Modifier.height(10.dp))
        when {
            loading -> LoadingLine("Đang tải…")
            error != null -> Row(
                Modifier.padding(horizontal = edge),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error, color = Pal.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(12.dp))
                TextAction("Thử lại", onRetry, primary = false, icon = Ic.Refresh)
            }
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = edge, vertical = if (isTv) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { ch ->
                    ChannelCard(
                        ch = ch,
                        isTv = isTv,
                        onClick = { nav.play(ch, fullList) },
                        modifier = Modifier.width(cardW),
                    )
                }
                if (seeAll != null) {
                    item(key = "see-all") {
                        FocusBox(
                            onClick = seeAll,
                            modifier = Modifier
                                .width(cardW * 0.8f)
                                .height(if (isTv) 158.dp else 136.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Pal.Panel,
                        ) { _ ->
                            Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Ic.ChevronRight, contentDescription = null, tint = Pal.Amber, modifier = Modifier.size(30.dp))
                                Text("Xem tất cả", color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================== TRUYỀN HÌNH ==============================

@Composable
fun TvTab(vm: AppVm, isTv: Boolean, nav: Nav) {
    val cat = vm.tvCat
    val key = vm.tvKey(cat)
    LaunchedEffect(key) { vm.ensure(key) }
    val st = vm.state(key)
    var pickCountry by remember { mutableStateOf(false) }
    BrowseLayout(
        isTv = isTv,
        cats = vm.tvCategories(),
        selected = cat,
        onPick = { vm.selectTv(it) },
        onCountry = { pickCountry = true },
    ) {
        ListHeader(vm.titleFor(cat, radio = false), st, "kênh", isTv) { vm.ensure(key, force = true) }
        ListBody(st, "kênh", isTv, onRetry = { vm.ensure(key, force = true) }) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (isTv) 172.dp else 150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = if (isTv) 8.dp else 16.dp,
                    end = if (isTv) 8.dp else 16.dp,
                    top = 8.dp,
                    bottom = 28.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(st.items, key = { it.id }) { ch ->
                    ChannelCard(ch = ch, isTv = isTv, onClick = { nav.play(ch, st.items) })
                }
            }
        }
    }
    if (pickCountry) {
        CountryDialog(
            includeIntl = true,
            onPick = {
                vm.selectTv("c:$it")
                pickCountry = false
            },
            onDismiss = { pickCountry = false },
        )
    }
}

// ============================== RADIO ==============================

@Composable
fun RadioTab(vm: AppVm, isTv: Boolean, nav: Nav) {
    val cat = vm.radioCat
    val key = vm.radioKey(cat)
    LaunchedEffect(key) { vm.ensure(key) }
    val st = vm.state(key)
    var pickCountry by remember { mutableStateOf(false) }
    BrowseLayout(
        isTv = isTv,
        cats = vm.radioCategories(),
        selected = cat,
        onPick = { vm.selectRadio(it) },
        onCountry = { pickCountry = true },
    ) {
        ListHeader(vm.titleFor(cat, radio = true), st, "đài", isTv) { vm.ensure(key, force = true) }
        ListBody(st, "đài", isTv, onRetry = { vm.ensure(key, force = true) }) {
            if (isTv) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(330.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(st.items, key = { it.id }) { ch ->
                        Box(Modifier.background(Pal.Panel, RoundedCornerShape(12.dp))) {
                            ChannelRow(ch = ch, onClick = { nav.play(ch, st.items) })
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
                ) {
                    items(st.items, key = { it.id }) { ch ->
                        ChannelRow(
                            ch = ch,
                            onClick = { nav.play(ch, st.items) },
                            fav = vm.isFav(ch),
                            onFav = { vm.toggleFav(ch) },
                        )
                    }
                }
            }
        }
    }
    if (pickCountry) {
        CountryDialog(
            includeIntl = false,
            onPick = {
                vm.selectRadio("c:$it")
                pickCountry = false
            },
            onDismiss = { pickCountry = false },
        )
    }
}

// ============================== DÙNG CHUNG ==============================

/** TV: cột nhóm bên trái + nội dung. Điện thoại: hàng nút nhóm + nội dung. */
@Composable
private fun BrowseLayout(
    isTv: Boolean,
    cats: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onCountry: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isCountry = selected.startsWith("c:")
    if (isTv) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .width(214.dp)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item { Text("Nhóm", color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)) }
                items(cats, key = { it.first }) { (k, title) ->
                    CategoryItem(title, selected == k) { onPick(k) }
                }
                item(key = "country") {
                    CategoryItem(if (isCountry) "Quốc gia: " + Catalog.countryName(selected.removePrefix("c:")) else "Quốc gia khác…", isCountry, onCountry)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                content = content,
            )
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cats, key = { it.first }) { (k, title) ->
                    Pill(title, selected == k, { onPick(k) })
                }
                item(key = "country") {
                    Pill(
                        if (isCountry) Catalog.countryName(selected.removePrefix("c:")) else "Quốc gia khác",
                        isCountry,
                        onCountry,
                        icon = Ic.Globe,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun CategoryItem(title: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Pal.AmberDim else Color.Transparent,
        focusedColor = Pal.Panel2,
        scaleOnFocus = 1.03f,
    ) { _ ->
        Text(
            text = title,
            color = if (selected) Pal.Amber else Pal.Text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun ListHeader(title: String, st: ListState, unit: String, isTv: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isTv) 8.dp else 16.dp, end = if (isTv) 8.dp else 10.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = if (isTv) 20.sp else 18.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = when {
                st.loading && st.items.isEmpty() -> "Đang tải danh sách…"
                st.loading -> "${st.items.size} $unit, đang cập nhật…"
                st.error != null && st.items.isNotEmpty() -> "${st.items.size} $unit (bản lưu trên máy, chưa cập nhật được)"
                else -> "${st.items.size} $unit"
            }
            Text(info, fontSize = 13.sp, color = Pal.Muted)
        }
        if (st.loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Pal.Amber, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else {
            RoundIcon(Ic.Refresh, "Tải lại danh sách", onRefresh, size = 42.dp, tint = Pal.Muted)
        }
    }
}

@Composable
private fun ListBody(
    st: ListState,
    unit: String,
    isTv: Boolean,
    onRetry: () -> Unit,
    list: @Composable () -> Unit,
) {
    when {
        st.items.isNotEmpty() -> list()
        st.loading -> LoadingLine("Đang tải danh sách $unit…", Modifier.padding(start = if (isTv) 0.dp else 4.dp))
        st.error != null -> MessageBlock(
            title = "Chưa tải được danh sách",
            body = (st.error ?: "Lỗi mạng") + ". Kiểm tra kết nối mạng rồi thử lại. Nếu vẫn lỗi, nguồn dữ liệu có thể đang bảo trì.",
            action = "Thử lại",
            onAction = onRetry,
        )
        else -> MessageBlock("Nhóm này chưa có $unit nào", "Chọn nhóm khác ở trên.")
    }
}

@Composable
private fun CountryDialog(includeIntl: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val list = if (includeIntl) listOf("INT" to "Kênh quốc tế") + Catalog.countries else Catalog.countries
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Panel,
        title = { Text("Chọn quốc gia", color = Pal.Text) },
        text = {
            LazyColumn(Modifier.height(380.dp)) {
                items(list, key = { it.first }) { (code, name) ->
                    FocusBox(
                        onClick = { onPick(code) },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent,
                        scaleOnFocus = 1.02f,
                    ) { _ ->
                        Text(name, color = Pal.Text, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Đóng", color = Pal.Amber) }
        },
    )
}

// ============================== YÊU THÍCH ==============================

@Composable
fun FavTab(vm: AppVm, isTv: Boolean, nav: Nav) {
    val all = vm.favorites.toList()
    if (all.isEmpty()) {
        MessageBlock(
            title = "Chưa có kênh yêu thích",
            body = "Khi đang xem hoặc nghe, bấm nút ngôi sao để lưu kênh vào đây. Lần sau mở app là thấy ngay.",
        )
        return
    }
    val tv = all.filter { it.kind == Kind.TV }
    val radio = all.filter { it.kind == Kind.RADIO }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (isTv) 172.dp else 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = if (isTv) 8.dp else 16.dp, end = if (isTv) 8.dp else 16.dp, top = 8.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (tv.isNotEmpty()) {
            item(key = "h-tv", span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("Truyền hình", trailing = "${tv.size} kênh")
            }
            items(tv, key = { "f-" + it.id }) { ch ->
                ChannelCard(ch = ch, isTv = isTv, onClick = { nav.play(ch, tv) })
            }
        }
        if (radio.isNotEmpty()) {
            item(key = "h-radio", span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("Radio", Modifier.padding(top = 8.dp), trailing = "${radio.size} đài")
            }
            items(radio, key = { "f-" + it.id }) { ch ->
                ChannelCard(ch = ch, isTv = isTv, onClick = { nav.play(ch, radio) })
            }
        }
    }
}

// ============================== CÀI ĐẶT ==============================

@Composable
fun SettingsTab(vm: AppVm, isTv: Boolean, nav: Nav) {
    var addList by remember { mutableStateOf(false) }
    var addOne by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = if (isTv) 4.dp else 16.dp, end = if (isTv) 4.dp else 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "lists") {
            SettingsCard(
                "Danh sách IPTV của tôi",
                "Thêm đường dẫn .m3u từ gói IPTV riêng. Danh sách sẽ hiện thành một nhóm trong mục Truyền hình và tự cập nhật mỗi lần mở app.",
            ) {
                for (p in vm.playlists) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(p.url, color = Pal.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        RoundIcon(Ic.Delete, "Xóa danh sách ${p.name}", { vm.removePlaylist(p) }, size = 42.dp, tint = Pal.Muted)
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextAction("Thêm danh sách .m3u", { addList = true }, icon = Ic.Add)
                Spacer(Modifier.height(10.dp))
                TextAction("Thêm một kênh hoặc đài lẻ", { addOne = true }, primary = false, icon = Ic.Add)
            }
        }
        item(key = "ui") {
            SettingsCard(
                "Giao diện",
                "App tự nhận biết đang chạy trên TV hay điện thoại. Nếu TV box hiện giao diện điện thoại, chọn TV ở đây.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Tự động", vm.uiMode == "auto", { vm.setUiMode("auto") })
                    Pill("Điện thoại", vm.uiMode == "phone", { vm.setUiMode("phone") })
                    Pill("TV", vm.uiMode == "tv", { vm.setUiMode("tv") })
                }
            }
        }
        item(key = "data") {
            SettingsCard(
                "Dữ liệu",
                "Danh sách kênh tự lấy bản mới nhất mỗi lần mở app. Bấm tải lại nếu muốn cập nhật ngay.",
            ) {
                TextAction("Tải lại toàn bộ danh sách kênh", { vm.refreshAll() }, primary = false, icon = Ic.Refresh)
                Spacer(Modifier.height(10.dp))
                TextAction("Xóa lịch sử xem và nghe", { vm.clearRecent() }, primary = false, icon = Ic.Delete)
            }
        }
        item(key = "official") {
            SettingsCard(
                "Kênh có bản quyền",
                "VTV, HTV, VTC và nhiều kênh trong nước phát trên nền tảng riêng, không xem được trong app này. Mở các trang sau:",
            ) {
                for (l in Catalog.official) {
                    FocusBox(
                        onClick = {
                            if (!openUrl(ctx, l.url)) vm.message = "Thiết bị chưa có trình duyệt để mở ${l.url}"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent,
                        scaleOnFocus = 1.02f,
                    ) { _ ->
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(l.note, color = Pal.Muted, fontSize = 12.5.sp)
                            }
                            Icon(Ic.OpenInNew, contentDescription = null, tint = Pal.Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        item(key = "about") {
            SettingsCard(
                "Giới thiệu",
                "Máy Thu ${BuildConfig.VERSION_NAME}. Kênh truyền hình lấy từ danh mục công khai iptv-org, đài radio lấy từ danh bạ Radio Browser. " +
                    "App không lưu trữ nội dung, chỉ mở các luồng phát công khai; kênh nào ngừng phát hoặc chặn vùng thì không xem được.",
            ) {}
        }
    }
    if (addList) AddPlaylistDialog(vm) { addList = false }
    if (addOne) {
        AddOneDialog(
            vm = vm,
            onDone = { ch ->
                addOne = false
                if (ch != null) nav.play(ch, listOf(ch))
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Pal.Panel, RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Text(title, color = Pal.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Pal.Muted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Pal.Amber,
    unfocusedBorderColor = Pal.Line,
    cursorColor = Pal.Amber,
    focusedTextColor = Pal.Text,
    unfocusedTextColor = Pal.Text,
    focusedLabelColor = Pal.Amber,
    unfocusedLabelColor = Pal.Muted,
    focusedPlaceholderColor = Pal.Muted,
    unfocusedPlaceholderColor = Pal.Muted,
    focusedLeadingIconColor = Pal.Amber,
    unfocusedLeadingIconColor = Pal.Muted,
    focusedContainerColor = Pal.Ink,
    unfocusedContainerColor = Pal.Ink,
)

@Composable
private fun AddPlaylistDialog(vm: AppVm, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Pal.Panel,
        title = { Text("Thêm danh sách IPTV", color = Pal.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên danh sách") },
                    singleLine = true,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Đường dẫn .m3u") },
                    placeholder = { Text("https://…/playlist.m3u") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LoadingLine("Đang kiểm tra danh sách…")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    vm.addPlaylist(name, url) { ok ->
                        busy = false
                        if (ok) onClose()
                    }
                },
            ) { Text("Thêm", color = Pal.Amber) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Hủy", color = Pal.Muted) }
        },
    )
}

@Composable
private fun AddOneDialog(vm: AppVm, onDone: (Channel?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var radio by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = Pal.Panel,
        title = { Text("Thêm một kênh hoặc đài", color = Pal.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên") },
                    singleLine = true,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Đường dẫn luồng phát (.m3u8, .mp3…)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Truyền hình", !radio, { radio = false }, icon = Ic.Tv)
                    Pill("Radio", radio, { radio = true }, icon = Ic.Radio)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ch = vm.addSingle(name, url, radio)
                if (ch != null) onDone(ch)
            }) { Text("Thêm và phát", color = Pal.Amber) }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text("Hủy", color = Pal.Muted) }
        },
    )
}

// ============================== TÌM KIẾM ==============================

@Composable
fun SearchScreen(vm: AppVm, isTv: Boolean, nav: Nav, onClose: () -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(200)
        try {
            field.requestFocus()
        } catch (e: Exception) {
        }
    }
    LaunchedEffect(q) {
        delay(450)
        vm.search(q)
    }
    val side = if (isTv) 48.dp else 12.dp
    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = if (isTv) 24.dp else 6.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = side),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Ic.Back, "Quay lại", onClose, size = 46.dp)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(field),
                singleLine = true,
                placeholder = { Text("Tên kênh hoặc đài, ví dụ: VOV, VTC, BBC") },
                leadingIcon = { Icon(Ic.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(q) }),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
            )
        }
        val query = q.trim()
        val tv = vm.searchTv
        val radio = vm.searchRadio
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 28.dp),
        ) {
            if (query.length < 2) {
                item(key = "hint") {
                    MessageBlock(
                        title = "Tìm kênh và đài",
                        body = "Gõ ít nhất 2 chữ. Gõ không dấu cũng được: \"nghe an\" vẫn tìm ra \"Nghệ An\".",
                    )
                }
            } else {
                item(key = "h-radio") {
                    SectionTitle(
                        "Đài radio",
                        Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        trailing = if (radio.loading) "đang tìm…" else "${radio.items.size} đài",
                    )
                }
                if (radio.error != null) {
                    item(key = "e-radio") {
                        Text(radio.error ?: "", color = Pal.Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    }
                }
                items(radio.items.take(60), key = { "s-" + it.id }) { ch ->
                    ChannelRow(ch = ch, onClick = { nav.play(ch, radio.items) }, fav = vm.isFav(ch), onFav = { vm.toggleFav(ch) })
                }
                item(key = "h-tv") {
                    SectionTitle(
                        "Kênh truyền hình",
                        Modifier.padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
                        trailing = "${tv.size} kênh",
                    )
                }
                items(tv.take(120), key = { "s-" + it.id }) { ch ->
                    ChannelRow(ch = ch, onClick = { nav.play(ch, tv) }, fav = vm.isFav(ch), onFav = { vm.toggleFav(ch) })
                }
                item(key = "world") {
                    Box(Modifier.padding(8.dp)) {
                        if (vm.worldTvLoading) {
                            LoadingLine("Đang tải danh mục hơn 10.000 kênh trên thế giới…")
                        } else {
                            TextAction("Tìm trong toàn bộ kênh TV thế giới", { vm.loadWorldTv(q) }, primary = false, icon = Ic.Globe)
                        }
                    }
                }
            }
        }
    }
}
