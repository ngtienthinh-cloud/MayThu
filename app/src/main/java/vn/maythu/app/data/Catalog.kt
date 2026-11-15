package vn.maythu.app.data

data class Category(
    val key: String,
    val title: String,
    val path: String = "",
    val params: Map<String, String> = emptyMap(),
)

data class OfficialLink(val title: String, val url: String, val note: String)

/** Các nguồn kênh: danh mục công khai iptv-org (TV) và Radio Browser (radio). */
object Catalog {
    const val IPTV = "https://iptv-org.github.io/iptv/"

    val tv = listOf(
        Category("vn", "Việt Nam", "countries/vn.m3u"),
        Category("vie", "Tiếng Việt", "languages/vie.m3u"),
        Category("news", "Tin tức", "categories/news.m3u"),
        Category("movies", "Phim", "categories/movies.m3u"),
        Category("music", "Âm nhạc", "categories/music.m3u"),
        Category("sports", "Thể thao", "categories/sports.m3u"),
        Category("kids", "Thiếu nhi", "categories/kids.m3u"),
        Category("animation", "Hoạt hình", "categories/animation.m3u"),
        Category("documentary", "Tài liệu", "categories/documentary.m3u"),
        Category("education", "Giáo dục", "categories/education.m3u"),
        Category("cooking", "Ẩm thực", "categories/cooking.m3u"),
        Category("travel", "Du lịch", "categories/travel.m3u"),
    )

    val radio = listOf(
        Category("vn", "Việt Nam", params = mapOf("countrycode" to "VN", "limit" to "500")),
        Category("vie", "Tiếng Việt toàn cầu", params = mapOf("language" to "vietnamese", "limit" to "300")),
        Category("top", "Nghe nhiều nhất", params = mapOf("limit" to "300")),
        Category("news", "Tin tức", params = mapOf("tag" to "news", "limit" to "300")),
        Category("pop", "Nhạc Pop", params = mapOf("tag" to "pop", "limit" to "300")),
        Category("easy", "Nhạc nhẹ", params = mapOf("tag" to "easy listening", "limit" to "300")),
        Category("jazz", "Jazz", params = mapOf("tag" to "jazz", "limit" to "300")),
        Category("classical", "Cổ điển", params = mapOf("tag" to "classical", "limit" to "300")),
        Category("lofi", "Lofi", params = mapOf("tag" to "lofi", "limit" to "300")),
        Category("chill", "Thư giãn", params = mapOf("tag" to "chillout", "limit" to "300")),
        Category("rock", "Rock", params = mapOf("tag" to "rock", "limit" to "300")),
    )

    val countries = listOf(
        "US" to "Mỹ", "GB" to "Anh", "JP" to "Nhật Bản", "KR" to "Hàn Quốc", "CN" to "Trung Quốc",
        "TW" to "Đài Loan", "HK" to "Hồng Kông", "TH" to "Thái Lan", "LA" to "Lào", "KH" to "Campuchia",
        "SG" to "Singapore", "MY" to "Malaysia", "ID" to "Indonesia", "PH" to "Philippines", "IN" to "Ấn Độ",
        "AU" to "Úc", "CA" to "Canada", "FR" to "Pháp", "DE" to "Đức", "IT" to "Ý", "ES" to "Tây Ban Nha",
        "RU" to "Nga", "BR" to "Brazil",
    )

    fun countryName(cc: String): String =
        if (cc == "INT") "Kênh quốc tế" else countries.firstOrNull { it.first == cc }?.second ?: cc

    /** Đường dẫn tệp M3U trên iptv-org cho một nhóm kênh TV. */
    fun tvPath(cat: String): String? = when {
        cat == "all" -> "index.m3u"
        cat.startsWith("c:") -> {
            val cc = cat.removePrefix("c:")
            "countries/" + (if (cc == "GB") "uk" else cc.lowercase()) + ".m3u"
        }
        else -> tv.firstOrNull { it.key == cat }?.path
    }

    /** Tham số truy vấn Radio Browser cho một nhóm đài. */
    fun radioParams(cat: String): Map<String, String>? = when {
        cat.startsWith("q:") -> mapOf("name" to cat.removePrefix("q:"), "limit" to "200")
        cat.startsWith("c:") -> mapOf("countrycode" to cat.removePrefix("c:"), "limit" to "400")
        else -> radio.firstOrNull { it.key == cat }?.params
    }

    val groupVi = mapOf(
        "news" to "Tin tức", "general" to "Tổng hợp", "entertainment" to "Giải trí", "movies" to "Phim",
        "music" to "Âm nhạc", "sports" to "Thể thao", "kids" to "Thiếu nhi", "documentary" to "Tài liệu",
        "education" to "Giáo dục", "religious" to "Tôn giáo", "lifestyle" to "Đời sống", "culture" to "Văn hóa",
        "business" to "Kinh tế", "cooking" to "Ẩm thực", "travel" to "Du lịch", "weather" to "Thời tiết",
        "legislative" to "Chính luận", "undefined" to "Khác", "series" to "Phim bộ", "comedy" to "Hài",
        "animation" to "Hoạt hình", "family" to "Gia đình", "classic" to "Kinh điển", "science" to "Khoa học",
        "auto" to "Xe cộ", "outdoor" to "Ngoài trời", "shop" to "Mua sắm", "relax" to "Thư giãn",
        "public" to "Công cộng",
    )

    val official = listOf(
        OfficialLink("VTVgo", "https://vtvgo.vn", "VTV1 đến VTV9 và các kênh của Đài Truyền hình Việt Nam"),
        OfficialLink("HTV Online", "https://htvonline.com.vn", "Các kênh của Đài Truyền hình TP.HCM"),
        OfficialLink("VOV", "https://vov.vn", "Đài Tiếng nói Việt Nam"),
        OfficialLink("FPT Play", "https://fptplay.vn", "Truyền hình, thể thao, phim"),
        OfficialLink("TV360", "https://tv360.vn", "Truyền hình của Viettel"),
        OfficialLink("MyTV", "https://mytv.com.vn", "Truyền hình của VNPT"),
    )
}
