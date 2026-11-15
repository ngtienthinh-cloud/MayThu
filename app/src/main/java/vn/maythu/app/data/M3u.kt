package vn.maythu.app.data

/** Đọc danh sách kênh định dạng M3U / M3U8 (kiểu IPTV). */
object M3u {
    private val attrRe = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val bracketRe = Regex("\\[([^\\]]+)]")
    private val resRe = Regex("\\((\\d{3,4}[pi])\\)", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s{2,}")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val seen = HashSet<String>()
        var hasInfo = false
        var name = ""
        var attrs = HashMap<String, String>()
        var ref = ""
        var ua = ""

        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("#EXTINF", ignoreCase = true) -> {
                    hasInfo = true
                    attrs = HashMap()
                    ref = ""
                    ua = ""
                    var inQuote = false
                    var cut = -1
                    var i = 8
                    while (i < l.length) {
                        val c = l[i]
                        if (c == '"') inQuote = !inQuote
                        else if (c == ',' && !inQuote) {
                            cut = i
                            break
                        }
                        i++
                    }
                    val head = if (cut >= 0) l.substring(0, cut) else l
                    name = if (cut >= 0) l.substring(cut + 1).trim() else ""
                    for (m in attrRe.findAll(head)) attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
                }
                l.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = l.substring(11)
                    val eq = opt.indexOf('=')
                    if (eq > 0) {
                        val k = opt.substring(0, eq).trim().lowercase()
                        val v = opt.substring(eq + 1).trim()
                        if (k == "http-referrer") ref = v
                        if (k == "http-user-agent") ua = v
                    }
                }
                l.startsWith("#EXTGRP:", ignoreCase = true) -> attrs["group-title"] = l.substring(8).trim()
                l.startsWith("#") -> Unit
                else -> {
                    if (l.startsWith("http://", ignoreCase = true) || l.startsWith("https://", ignoreCase = true)) {
                        val labels = ArrayList<String>()
                        var n = if (hasInfo) name else ""
                        if (n.isBlank()) n = attrs["tvg-name"] ?: ""
                        if (n.isBlank()) n = l
                        n = bracketRe.replace(n) { m ->
                            val t = m.groupValues[1]
                            val tl = t.lowercase()
                            labels.add(
                                when {
                                    tl.contains("geo") -> "Có thể chặn vùng"
                                    tl.contains("24/7") -> "Không phát 24/7"
                                    else -> t
                                }
                            )
                            ""
                        }
                        n = resRe.replace(n) { m ->
                            labels.add(0, m.groupValues[1])
                            ""
                        }
                        n = spaces.replace(n, " ").trim()
                        val id = "tv:" + Integer.toHexString(l.hashCode()) + l.length.toString(36)
                        if (seen.add(id)) {
                            out.add(
                                Channel(
                                    id = id,
                                    kind = Kind.TV,
                                    name = n.ifBlank { "Không tên" },
                                    url = l,
                                    logo = attrs["tvg-logo"].orEmpty().trim(),
                                    group = groupName(attrs["group-title"]),
                                    labels = labels,
                                    referrer = ref.ifBlank { attrs["http-referrer"].orEmpty() },
                                    userAgent = ua.ifBlank { attrs["http-user-agent"] ?: attrs["user-agent"].orEmpty() },
                                )
                            )
                        }
                    }
                    hasInfo = false
                    name = ""
                    attrs = HashMap()
                    ref = ""
                    ua = ""
                }
            }
        }
        return out
    }

    private fun groupName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString(", ") { g -> Catalog.groupVi[g.lowercase()] ?: g }
    }
}
