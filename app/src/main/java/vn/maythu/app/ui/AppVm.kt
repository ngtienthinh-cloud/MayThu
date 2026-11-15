package vn.maythu.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vn.maythu.app.data.Catalog
import vn.maythu.app.data.Channel
import vn.maythu.app.data.ListState
import vn.maythu.app.data.Playlist
import vn.maythu.app.data.Repo
import vn.maythu.app.data.Store
import vn.maythu.app.data.Txt
import vn.maythu.app.player.Playback
import java.io.IOException

class AppVm(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    val favorites = mutableStateListOf<Channel>()
    val recent = mutableStateListOf<Channel>()
    val playlists = mutableStateListOf<Playlist>()
    val lists = mutableStateMapOf<String, ListState>()

    var tvCat by mutableStateOf(store.getString("tvCat", "vn"))
        private set
    var radioCat by mutableStateOf(store.getString("radioCat", "vn"))
        private set

    /** "auto", "phone" hoặc "tv" */
    var uiMode by mutableStateOf(store.getString("uiMode", "auto"))
        private set

    var searchTv by mutableStateOf<List<Channel>>(emptyList())
        private set
    var searchRadio by mutableStateOf(ListState())
        private set
    var worldTvLoading by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    /** Thông báo ngắn hiện ở cuối màn hình. */
    var message by mutableStateOf<String?>(null)

    init {
        favorites.addAll(store.favorites())
        recent.addAll(store.recent())
        playlists.addAll(store.playlists())
        Playback.onStarted = { ch -> addRecent(ch) }
    }

    override fun onCleared() {
        Playback.onStarted = null
        super.onCleared()
    }

    // ---------- danh sách kênh ----------

    fun tvKey(cat: String = tvCat) = "tv:$cat"
    fun radioKey(cat: String = radioCat) = "radio:$cat"

    fun state(key: String): ListState = lists[key] ?: ListState(items = Repo.cached(key) ?: emptyList(), loading = true)

    /** Tải danh sách nếu chưa có bản mới trong phiên này. */
    fun ensure(key: String, force: Boolean = false) {
        val cur = lists[key]
        if (!force && cur != null && (cur.loading || cur.fresh)) return
        if (!force && cur == null && Repo.isFresh(key)) {
            lists[key] = ListState(items = Repo.cached(key) ?: emptyList(), fresh = true)
            return
        }
        val shown = cur?.items ?: Repo.cached(key) ?: emptyList()
        lists[key] = ListState(items = shown, loading = true)
        viewModelScope.launch {
            try {
                val items = Repo.load(key, force) { fetch(key) }
                lists[key] = ListState(items = items, fresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lists[key] = ListState(
                    items = lists[key]?.items ?: emptyList(),
                    error = friendly(e),
                )
            }
        }
    }

    private suspend fun fetch(key: String): List<Channel> {
        if (key.startsWith("tv:my:")) {
            val id = key.removePrefix("tv:my:")
            val p = playlists.firstOrNull { it.id == id } ?: throw IOException("Danh sách này đã bị xóa")
            return Repo.playlist(p.url)
        }
        if (key.startsWith("tv:")) {
            val path = Catalog.tvPath(key.removePrefix("tv:")) ?: throw IOException("Không có nhóm kênh này")
            return Repo.tvList(path)
        }
        if (key.startsWith("radio:")) {
            val params = Catalog.radioParams(key.removePrefix("radio:")) ?: throw IOException("Không có nhóm đài này")
            return Repo.radio(params)
        }
        throw IOException("Nguồn không hợp lệ")
    }

    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException -> "Không có kết nối mạng"
            e is java.net.SocketTimeoutException -> "Mạng chậm, quá thời gian chờ"
            m.isNotBlank() -> m
            else -> "Không tải được danh sách"
        }
    }

    fun selectTv(cat: String) {
        tvCat = cat
        if (cat != "all") store.putString("tvCat", cat)
        ensure(tvKey(cat))
    }

    fun selectRadio(cat: String) {
        radioCat = cat
        if (!cat.startsWith("q:")) store.putString("radioCat", cat)
        ensure(radioKey(cat))
    }

    fun tvCategories(): List<Pair<String, String>> =
        Catalog.tv.map { it.key to it.title } + playlists.map { "my:" + it.id to it.name }

    fun radioCategories(): List<Pair<String, String>> = Catalog.radio.map { it.key to it.title }

    fun titleFor(cat: String, radio: Boolean): String = when {
        cat == "all" -> "Tất cả kênh trên thế giới"
        cat.startsWith("c:") -> Catalog.countryName(cat.removePrefix("c:"))
        cat.startsWith("q:") -> "Kết quả: " + cat.removePrefix("q:")
        cat.startsWith("my:") -> playlists.firstOrNull { "my:" + it.id == cat }?.name ?: "Danh sách của tôi"
        radio -> Catalog.radio.firstOrNull { it.key == cat }?.title ?: cat
        else -> Catalog.tv.firstOrNull { it.key == cat }?.title ?: cat
    }

    fun refreshAll() {
        Repo.clearAll()
        lists.clear()
        ensure(tvKey())
        ensure(radioKey())
        message = "Đang tải lại toàn bộ danh sách kênh"
    }

    // ---------- yêu thích, gần đây ----------

    fun isFav(ch: Channel): Boolean = favorites.any { it.id == ch.id }

    fun toggleFav(ch: Channel) {
        val i = favorites.indexOfFirst { it.id == ch.id }
        if (i >= 0) {
            favorites.removeAt(i)
            message = "Đã bỏ \"${ch.name}\" khỏi Yêu thích"
        } else {
            favorites.add(ch)
            message = "Đã thêm \"${ch.name}\" vào Yêu thích"
        }
        store.saveFavorites(favorites.toList())
    }

    private fun addRecent(ch: Channel) {
        val i = recent.indexOfFirst { it.id == ch.id }
        if (i >= 0) recent.removeAt(i)
        recent.add(0, ch)
        while (recent.size > 30) recent.removeAt(recent.size - 1)
        store.saveRecent(recent.toList())
    }

    fun clearRecent() {
        recent.clear()
        store.saveRecent(emptyList())
        message = "Đã xóa lịch sử xem và nghe"
    }

    // ---------- danh sách IPTV riêng ----------

    fun addPlaylist(name: String, url: String, done: (Boolean) -> Unit) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            message = "Đường dẫn cần bắt đầu bằng http:// hoặc https://"
            done(false)
            return
        }
        message = "Đang kiểm tra danh sách…"
        viewModelScope.launch {
            try {
                val items = Repo.playlist(u)
                if (items.isEmpty()) throw IOException("Không đọc được kênh nào trong đường dẫn này")
                val id = System.currentTimeMillis().toString(36)
                val p = Playlist(id, name.trim().ifBlank { "Danh sách của tôi" }, u)
                playlists.add(p)
                store.savePlaylists(playlists.toList())
                Repo.put("tv:my:$id", items)
                lists["tv:my:$id"] = ListState(items = items, fresh = true)
                tvCat = "my:$id"
                store.putString("tvCat", tvCat)
                message = "Đã thêm \"${p.name}\" với ${items.size} kênh"
                done(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Không mở được danh sách: " + friendly(e)
                done(false)
            }
        }
    }

    fun removePlaylist(p: Playlist) {
        playlists.removeAll { it.id == p.id }
        store.savePlaylists(playlists.toList())
        lists.remove("tv:my:" + p.id)
        if (tvCat == "my:" + p.id) selectTv("vn")
        message = "Đã xóa danh sách \"${p.name}\""
    }

    /** Thêm một kênh/đài lẻ (đường dẫn luồng) thẳng vào Yêu thích. */
    fun addSingle(name: String, url: String, radio: Boolean): Channel? {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            message = "Đường dẫn cần bắt đầu bằng http:// hoặc https://"
            return null
        }
        val ch = Channel(
            id = "u:" + Integer.toHexString(u.hashCode()),
            kind = if (radio) vn.maythu.app.data.Kind.RADIO else vn.maythu.app.data.Kind.TV,
            name = name.trim().ifBlank { "Kênh tự thêm" },
            url = u,
            group = if (radio) "" else "Kênh tự thêm",
            country = if (radio) "Đài tự thêm" else "",
        )
        if (!isFav(ch)) {
            favorites.add(ch)
            store.saveFavorites(favorites.toList())
        }
        message = "Đã thêm \"${ch.name}\" vào Yêu thích"
        return ch
    }

    @JvmName("applyUiMode")
    fun setUiMode(mode: String) {
        uiMode = mode
        store.putString("uiMode", mode)
    }

    // ---------- tìm kiếm ----------

    fun search(q: String) {
        searchJob?.cancel()
        val query = q.trim()
        if (query.length < 2) {
            searchTv = emptyList()
            searchRadio = ListState()
            return
        }
        val nq = Txt.norm(query)
        searchTv = Repo.allLoadedTv()
            .filter { Txt.norm(it.name).contains(nq) }
            .distinctBy { it.id }
            .take(300)
        searchRadio = ListState(loading = true)
        searchJob = viewModelScope.launch {
            try {
                val r = Repo.radio(mapOf("name" to query, "limit" to "150"))
                searchRadio = ListState(items = r.distinctBy { it.id }, fresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchRadio = ListState(error = friendly(e))
            }
        }
    }

    /** Tải danh mục toàn cầu (hơn 10.000 kênh) rồi tìm lại. */
    fun loadWorldTv(q: String) {
        if (worldTvLoading) return
        worldTvLoading = true
        viewModelScope.launch {
            try {
                Repo.load("tv:all", false) { Repo.tvList("index.m3u") }
                search(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Không tải được danh mục toàn cầu: " + friendly(e)
            } finally {
                worldTvLoading = false
            }
        }
    }
}
