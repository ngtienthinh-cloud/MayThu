package vn.maythu.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

enum class Kind { TV, RADIO }

/** Một kênh truyền hình hoặc một đài radio. */
data class Channel(
    val id: String,
    val kind: Kind,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val country: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Boolean = false,
    val referrer: String = "",
    val userAgent: String = "",
    val uuid: String = "",
    val labels: List<String> = emptyList(),
) {
    val subtitle: String
        get() = when (kind) {
            Kind.TV -> (listOf(group.ifBlank { "Truyền hình" }) + labels).joinToString(", ")
            Kind.RADIO -> {
                val parts = mutableListOf<String>()
                if (country.isNotBlank()) parts += country
                if (codec.isNotBlank() && !codec.equals("UNKNOWN", ignoreCase = true)) {
                    parts += if (bitrate > 0) "$codec $bitrate kbps" else codec
                }
                if (tags.isNotBlank()) parts += tags
                if (parts.isEmpty()) "Radio" else parts.joinToString(", ")
            }
        }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("kind", kind.name)
        o.put("name", name)
        o.put("url", url)
        if (logo.isNotEmpty()) o.put("logo", logo)
        if (group.isNotEmpty()) o.put("group", group)
        if (country.isNotEmpty()) o.put("country", country)
        if (tags.isNotEmpty()) o.put("tags", tags)
        if (codec.isNotEmpty()) o.put("codec", codec)
        if (bitrate > 0) o.put("bitrate", bitrate)
        if (hls) o.put("hls", true)
        if (referrer.isNotEmpty()) o.put("ref", referrer)
        if (userAgent.isNotEmpty()) o.put("ua", userAgent)
        if (uuid.isNotEmpty()) o.put("uuid", uuid)
        if (labels.isNotEmpty()) o.put("labels", JSONArray(labels))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Channel? = try {
            val labelsArr = o.optJSONArray("labels")
            Channel(
                id = o.getString("id"),
                kind = if (o.optString("kind") == "RADIO") Kind.RADIO else Kind.TV,
                name = o.optString("name"),
                url = o.getString("url"),
                logo = o.optString("logo"),
                group = o.optString("group"),
                country = o.optString("country"),
                tags = o.optString("tags"),
                codec = o.optString("codec"),
                bitrate = o.optInt("bitrate"),
                hls = o.optBoolean("hls"),
                referrer = o.optString("ref"),
                userAgent = o.optString("ua"),
                uuid = o.optString("uuid"),
                labels = if (labelsArr == null) emptyList() else List(labelsArr.length()) { i -> labelsArr.optString(i) },
            )
        } catch (e: Exception) {
            null
        }

        fun listToJson(list: List<Channel>): String {
            val a = JSONArray()
            for (c in list) a.put(c.toJson())
            return a.toString()
        }

        fun listFromJson(s: String?): List<Channel> {
            if (s.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(s)
                val out = ArrayList<Channel>(a.length())
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val c = fromJson(o) ?: continue
                    out.add(c)
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/** Danh sách IPTV (.m3u) người dùng tự thêm. */
data class Playlist(val id: String, val name: String, val url: String)

/** Trạng thái tải của một danh sách kênh. */
data class ListState(
    val items: List<Channel> = emptyList(),
    val loading: Boolean = false,
    val fresh: Boolean = false,
    val error: String? = null,
)

object Txt {
    private val marks = Regex("\\p{Mn}+")

    /** Bỏ dấu tiếng Việt, chữ thường — để tìm "vtv" ra "VTV", "nghe an" ra "Nghệ An". */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(marks, "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .lowercase()

    fun initials(name: String): String {
        val words = name.split(' ', '-', '_', '.', '/').filter { it.isNotBlank() }
        val sb = StringBuilder()
        for (w in words) {
            val ch = w.firstOrNull { it.isLetterOrDigit() } ?: continue
            sb.append(ch)
            if (sb.length == 2) break
        }
        return if (sb.isEmpty()) "?" else sb.toString().uppercase()
    }
}
