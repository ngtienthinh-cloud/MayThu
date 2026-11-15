package vn.maythu.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lưu kênh yêu thích, lịch sử, danh sách riêng và cài đặt trên máy. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("maythu", Context.MODE_PRIVATE)

    fun getString(key: String, def: String): String = sp.getString(key, def) ?: def

    fun putString(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    fun favorites(): List<Channel> = Channel.listFromJson(sp.getString("favs", null))

    fun saveFavorites(list: List<Channel>) {
        sp.edit().putString("favs", Channel.listToJson(list)).apply()
    }

    fun recent(): List<Channel> = Channel.listFromJson(sp.getString("recent", null))

    fun saveRecent(list: List<Channel>) {
        sp.edit().putString("recent", Channel.listToJson(list)).apply()
    }

    fun alarms(): List<AlarmItem> = AlarmItem.listFromJson(sp.getString("alarms", null))

    /** Ghi ngay (commit) vì có thể được gọi khi báo thức vừa reo, app chưa mở. */
    fun saveAlarms(list: List<AlarmItem>) {
        sp.edit().putString("alarms", AlarmItem.listToJson(list)).commit()
    }

    fun playlists(): List<Playlist> {
        return try {
            val a = JSONArray(sp.getString("playlists", "[]") ?: "[]")
            val out = ArrayList<Playlist>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(Playlist(o.optString("id"), o.optString("name"), o.optString("url")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylists(list: List<Playlist>) {
        val a = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("url", p.url)
            a.put(o)
        }
        sp.edit().putString("playlists", a.toString()).apply()
    }
}
