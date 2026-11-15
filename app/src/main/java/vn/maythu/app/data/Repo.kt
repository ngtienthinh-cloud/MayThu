package vn.maythu.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Tải danh sách kênh. Mỗi lần mở app đều lấy bản mới nhất từ nguồn,
 * đồng thời lưu một bản trên máy để hiện ngay và dùng khi mất mạng.
 */
object Repo {
    private const val UA = "MayThu/1.0 (Android)"
    private val rbHosts = listOf(
        "de1.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "fi1.api.radio-browser.info",
        "nl1.api.radio-browser.info",
        "at1.api.radio-browser.info",
    )

    @Volatile
    private var rbHost: String? = null
    private val mem = ConcurrentHashMap<String, List<Channel>>()
    private val fresh: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private var dir: File? = null
    private val nonFileChars = Regex("[^A-Za-z0-9_.-]")
    private val ws = Regex("\\s+")

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.cacheDir, "lists").apply { mkdirs() }
    }

    /** Tải nội dung văn bản qua HTTP(S), tự theo chuyển hướng kể cả http → https. */
    suspend fun get(url: String, timeoutMs: Int = 25_000): String = withContext(Dispatchers.IO) {
        var u = url
        for (hop in 0..5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    if (loc != null) {
                        u = URL(URL(u), loc).toString()
                        continue
                    }
                }
                if (code !in 200..299) throw IOException("Máy chủ báo lỗi $code")
                return@withContext c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Đường dẫn chuyển hướng quá nhiều lần")
    }

    fun cached(key: String): List<Channel>? {
        mem[key]?.let { return it }
        val f = file(key) ?: return null
        if (!f.exists()) return null
        val text = try { f.readText() } catch (e: Exception) { null }
        val list = Channel.listFromJson(text)
        if (list.isEmpty()) return null
        mem[key] = list
        return list
    }

    fun isFresh(key: String): Boolean = fresh.contains(key)

    suspend fun load(key: String, force: Boolean, fetch: suspend () -> List<Channel>): List<Channel> {
        if (!force && fresh.contains(key)) {
            val m = mem[key]
            if (m != null) return m
        }
        return try {
            val list = fetch().distinctBy { it.id }
            mem[key] = list
            fresh.add(key)
            withContext(Dispatchers.IO) {
                try {
                    file(key)?.writeText(Channel.listToJson(list))
                } catch (e: Exception) {
                    // bộ nhớ đầy: bỏ qua, lần sau tải lại
                }
            }
            list
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached(key) ?: throw e
        }
    }

    fun put(key: String, list: List<Channel>) {
        mem[key] = list
        fresh.add(key)
    }

    fun clearAll() {
        mem.clear()
        fresh.clear()
        dir?.listFiles()?.forEach { it.delete() }
    }

    fun allLoadedTv(): List<Channel> {
        val out = ArrayList<Channel>()
        for ((k, v) in mem) if (k.startsWith("tv:")) out.addAll(v)
        return out
    }

    private fun file(key: String): File? = dir?.let { File(it, key.replace(nonFileChars, "_") + ".json") }

    suspend fun tvList(path: String): List<Channel> {
        val text = get(Catalog.IPTV + path, 45_000)
        return withContext(Dispatchers.Default) { M3u.parse(text) }
    }

    suspend fun playlist(url: String): List<Channel> {
        val text = get(url, 45_000)
        return withContext(Dispatchers.Default) { M3u.parse(text) }
    }

    suspend fun radio(params: Map<String, String>): List<Channel> {
        val all = linkedMapOf("hidebroken" to "true", "order" to "votes", "reverse" to "true")
        all.putAll(params)
        val qs = all.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val hosts = (listOfNotNull(rbHost) + rbHosts).distinct()
        var last: Exception? = null
        for (h in hosts) {
            for (scheme in arrayOf("https", "http")) {
                try {
                    val text = get("$scheme://$h/json/stations/search?$qs", 15_000)
                    val list = withContext(Dispatchers.Default) { parseStations(text) }
                    rbHost = h
                    return list
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        throw last ?: IOException("Không kết nối được danh bạ radio")
    }

    /** Báo cho Radio Browser biết đài được nghe (giúp xếp hạng đài phổ biến). */
    suspend fun countClick(uuid: String) {
        try {
            get("https://" + (rbHost ?: rbHosts[0]) + "/json/url/" + enc(uuid), 8_000)
        } catch (e: Exception) {
        }
    }

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun parseStations(text: String): List<Channel> {
        val a = JSONArray(text)
        val out = ArrayList<Channel>(a.length())
        val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val url = o.str("url_resolved").ifBlank { o.str("url") }.trim()
            val name = o.str("name").replace(ws, " ").trim()
            val uuid = o.str("stationuuid")
            if (url.isBlank() || name.isBlank() || uuid.isBlank()) continue
            if (!seen.add(Txt.norm(name) + "|" + url)) continue
            val tags = o.str("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ")
            out.add(
                Channel(
                    id = "r:$uuid",
                    kind = Kind.RADIO,
                    name = name,
                    url = url,
                    logo = o.str("favicon").trim(),
                    country = o.str("country"),
                    tags = tags,
                    codec = o.str("codec"),
                    bitrate = o.optInt("bitrate", 0),
                    hls = o.optInt("hls", 0) == 1,
                    uuid = uuid,
                )
            )
        }
        return out
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
