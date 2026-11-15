using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;

namespace MayThu
{
    public enum Kind { TV, RADIO }

    /// <summary>Một kênh truyền hình hoặc một đài radio.</summary>
    public class Channel
    {
        public string Id { get; set; } = "";
        public Kind Kind { get; set; } = Kind.TV;
        public string Name { get; set; } = "";
        public string Url { get; set; } = "";
        public string Logo { get; set; } = "";
        public string Group { get; set; } = "";
        public string Country { get; set; } = "";
        public string Tags { get; set; } = "";
        public string Codec { get; set; } = "";
        public int Bitrate { get; set; }
        public bool Hls { get; set; }
        public string Referrer { get; set; } = "";
        public string UserAgent { get; set; } = "";
        public string Uuid { get; set; } = "";
        public List<string> Labels { get; set; } = new List<string>();

        [JsonIgnore]
        public string Subtitle
        {
            get
            {
                if (Kind == Kind.TV)
                {
                    var parts = new List<string> { string.IsNullOrWhiteSpace(Group) ? "Truyền hình" : Group };
                    if (Labels != null) parts.AddRange(Labels);
                    return string.Join(", ", parts);
                }
                var p = new List<string>();
                if (!string.IsNullOrWhiteSpace(Country)) p.Add(Country);
                if (!string.IsNullOrWhiteSpace(Codec) && !Codec.Equals("UNKNOWN", StringComparison.OrdinalIgnoreCase))
                    p.Add(Bitrate > 0 ? Codec + " " + Bitrate + " kbps" : Codec);
                if (!string.IsNullOrWhiteSpace(Tags)) p.Add(Tags);
                return p.Count == 0 ? "Radio" : string.Join(", ", p);
            }
        }

        private string searchKey;

        [JsonIgnore]
        public string SearchKey => searchKey ??= Txt.Norm(Name + " " + Group + " " + Country + " " + Tags);
    }

    /// <summary>Danh sách IPTV (.m3u) người dùng tự thêm.</summary>
    public class Playlist
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public string Url { get; set; } = "";
    }

    public static class Txt
    {
        /// <summary>Bỏ dấu tiếng Việt, chữ thường: "nghe an" tìm ra "Nghệ An".</summary>
        public static string Norm(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            string d = s.Normalize(NormalizationForm.FormD);
            var sb = new StringBuilder(d.Length);
            foreach (char ch in d)
            {
                if (CharUnicodeInfo.GetUnicodeCategory(ch) == UnicodeCategory.NonSpacingMark) continue;
                if (ch == 'đ') sb.Append('d');
                else if (ch == 'Đ') sb.Append('D');
                else sb.Append(ch);
            }
            return sb.ToString().ToLowerInvariant();
        }

        public static string Initials(string name)
        {
            if (string.IsNullOrWhiteSpace(name)) return "?";
            var sb = new StringBuilder();
            foreach (string w in name.Split(new[] { ' ', '-', '_', '.', '/' }, StringSplitOptions.RemoveEmptyEntries))
            {
                foreach (char c in w)
                {
                    if (char.IsLetterOrDigit(c))
                    {
                        sb.Append(c);
                        break;
                    }
                }
                if (sb.Length == 2) break;
            }
            return sb.Length == 0 ? "?" : sb.ToString().ToUpperInvariant();
        }

        /// <summary>Mã băm ổn định (giống Java) để mã kênh không đổi giữa các lần mở app.</summary>
        public static int StableHash(string s)
        {
            int h = 0;
            foreach (char c in s) h = unchecked(31 * h + c);
            return h;
        }

        public static string Base36(int n)
        {
            const string digits = "0123456789abcdefghijklmnopqrstuvwxyz";
            if (n <= 0) return "0";
            var sb = new StringBuilder();
            while (n > 0)
            {
                sb.Insert(0, digits[n % 36]);
                n /= 36;
            }
            return sb.ToString();
        }
    }

    public class Category
    {
        public string Key;
        public string Title;
        public string Path;
        public Dictionary<string, string> Params;

        public Category(string key, string title, string path = "", Dictionary<string, string> ps = null)
        {
            Key = key;
            Title = title;
            Path = path;
            Params = ps;
        }
    }

    public class OfficialLink
    {
        public string Title;
        public string Url;
        public string Note;

        public OfficialLink(string title, string url, string note)
        {
            Title = title;
            Url = url;
            Note = note;
        }
    }

    /// <summary>Nguồn kênh: danh mục công khai iptv-org (TV) và Radio Browser (radio).</summary>
    public static class Catalog
    {
        public const string Iptv = "https://iptv-org.github.io/iptv/";

        private static Dictionary<string, string> P(params string[] kv)
        {
            var d = new Dictionary<string, string>();
            for (int i = 0; i + 1 < kv.Length; i += 2) d[kv[i]] = kv[i + 1];
            return d;
        }

        public static readonly List<Category> Tv = new List<Category>
        {
            new Category("vn", "Việt Nam", "countries/vn.m3u"),
            new Category("vie", "Tiếng Việt", "languages/vie.m3u"),
            new Category("news", "Tin tức", "categories/news.m3u"),
            new Category("movies", "Phim", "categories/movies.m3u"),
            new Category("music", "Âm nhạc", "categories/music.m3u"),
            new Category("sports", "Thể thao", "categories/sports.m3u"),
            new Category("kids", "Thiếu nhi", "categories/kids.m3u"),
            new Category("animation", "Hoạt hình", "categories/animation.m3u"),
            new Category("documentary", "Tài liệu", "categories/documentary.m3u"),
            new Category("education", "Giáo dục", "categories/education.m3u"),
            new Category("cooking", "Ẩm thực", "categories/cooking.m3u"),
            new Category("travel", "Du lịch", "categories/travel.m3u"),
        };

        public static readonly List<Category> Radio = new List<Category>
        {
            new Category("vn", "Việt Nam", ps: P("countrycode", "VN", "limit", "500")),
            new Category("vie", "Tiếng Việt toàn cầu", ps: P("language", "vietnamese", "limit", "300")),
            new Category("top", "Nghe nhiều nhất", ps: P("limit", "300")),
            new Category("news", "Tin tức", ps: P("tag", "news", "limit", "300")),
            new Category("pop", "Nhạc Pop", ps: P("tag", "pop", "limit", "300")),
            new Category("easy", "Nhạc nhẹ", ps: P("tag", "easy listening", "limit", "300")),
            new Category("jazz", "Jazz", ps: P("tag", "jazz", "limit", "300")),
            new Category("classical", "Cổ điển", ps: P("tag", "classical", "limit", "300")),
            new Category("lofi", "Lofi", ps: P("tag", "lofi", "limit", "300")),
            new Category("chill", "Thư giãn", ps: P("tag", "chillout", "limit", "300")),
            new Category("rock", "Rock", ps: P("tag", "rock", "limit", "300")),
        };

        public static readonly (string Code, string Name)[] Countries =
        {
            ("US", "Mỹ"), ("GB", "Anh"), ("JP", "Nhật Bản"), ("KR", "Hàn Quốc"), ("CN", "Trung Quốc"),
            ("TW", "Đài Loan"), ("HK", "Hồng Kông"), ("TH", "Thái Lan"), ("LA", "Lào"), ("KH", "Campuchia"),
            ("SG", "Singapore"), ("MY", "Malaysia"), ("ID", "Indonesia"), ("PH", "Philippines"), ("IN", "Ấn Độ"),
            ("AU", "Úc"), ("CA", "Canada"), ("FR", "Pháp"), ("DE", "Đức"), ("IT", "Ý"), ("ES", "Tây Ban Nha"),
            ("RU", "Nga"), ("BR", "Brazil"),
        };

        public static string CountryName(string cc)
        {
            foreach (var c in Countries) if (c.Code == cc) return c.Name;
            return cc;
        }

        /// <summary>Đường dẫn tệp M3U trên iptv-org cho một nhóm kênh TV.</summary>
        public static string TvPath(string cat)
        {
            if (cat == "all") return "index.m3u";
            if (cat.StartsWith("c:"))
            {
                string cc = cat.Substring(2);
                return "countries/" + (cc == "GB" ? "uk" : cc.ToLowerInvariant()) + ".m3u";
            }
            return Tv.FirstOrDefault(c => c.Key == cat)?.Path;
        }

        /// <summary>Tham số truy vấn Radio Browser cho một nhóm đài.</summary>
        public static Dictionary<string, string> RadioParams(string cat)
        {
            if (cat.StartsWith("q:")) return P("name", cat.Substring(2), "limit", "200");
            if (cat.StartsWith("c:")) return P("countrycode", cat.Substring(2), "limit", "400");
            return Radio.FirstOrDefault(c => c.Key == cat)?.Params;
        }

        public static readonly Dictionary<string, string> GroupVi = new Dictionary<string, string>
        {
            ["news"] = "Tin tức", ["general"] = "Tổng hợp", ["entertainment"] = "Giải trí", ["movies"] = "Phim",
            ["music"] = "Âm nhạc", ["sports"] = "Thể thao", ["kids"] = "Thiếu nhi", ["documentary"] = "Tài liệu",
            ["education"] = "Giáo dục", ["religious"] = "Tôn giáo", ["lifestyle"] = "Đời sống", ["culture"] = "Văn hóa",
            ["business"] = "Kinh tế", ["cooking"] = "Ẩm thực", ["travel"] = "Du lịch", ["weather"] = "Thời tiết",
            ["legislative"] = "Chính luận", ["undefined"] = "Khác", ["series"] = "Phim bộ", ["comedy"] = "Hài",
            ["animation"] = "Hoạt hình", ["family"] = "Gia đình", ["classic"] = "Kinh điển", ["science"] = "Khoa học",
            ["auto"] = "Xe cộ", ["outdoor"] = "Ngoài trời", ["shop"] = "Mua sắm", ["relax"] = "Thư giãn",
            ["public"] = "Công cộng",
        };

        public static readonly List<OfficialLink> Official = new List<OfficialLink>
        {
            new OfficialLink("VTVgo", "https://vtvgo.vn", "VTV1 đến VTV9 và các kênh của Đài Truyền hình Việt Nam"),
            new OfficialLink("HTV Online", "https://htvonline.com.vn", "Các kênh của Đài Truyền hình TP.HCM"),
            new OfficialLink("VOV", "https://vov.vn", "Đài Tiếng nói Việt Nam"),
            new OfficialLink("FPT Play", "https://fptplay.vn", "Truyền hình, thể thao, phim"),
            new OfficialLink("TV360", "https://tv360.vn", "Truyền hình của Viettel"),
            new OfficialLink("MyTV", "https://mytv.com.vn", "Truyền hình của VNPT"),
        };
    }

    /// <summary>Đọc danh sách kênh định dạng M3U / M3U8 (kiểu IPTV).</summary>
    public static class M3u
    {
        private static readonly Regex AttrRe = new Regex("([\\w-]+)=\"([^\"]*)\"", RegexOptions.Compiled);
        private static readonly Regex BracketRe = new Regex("\\[([^\\]]+)]", RegexOptions.Compiled);
        private static readonly Regex ResRe = new Regex("\\((\\d{3,4}[pi])\\)", RegexOptions.Compiled | RegexOptions.IgnoreCase);
        private static readonly Regex Spaces = new Regex("\\s{2,}", RegexOptions.Compiled);

        public static List<Channel> Parse(string text)
        {
            var list = new List<Channel>();
            var seen = new HashSet<string>();
            bool hasInfo = false;
            string name = "";
            var attrs = new Dictionary<string, string>();
            string refr = "";
            string ua = "";

            using (var sr = new StringReader(text ?? ""))
            {
                string raw;
                while ((raw = sr.ReadLine()) != null)
                {
                    string l = raw.Trim().TrimStart('﻿');
                    if (l.Length == 0) continue;
                    if (l.StartsWith("#EXTINF", StringComparison.OrdinalIgnoreCase))
                    {
                        hasInfo = true;
                        attrs = new Dictionary<string, string>();
                        refr = "";
                        ua = "";
                        bool inQuote = false;
                        int cut = -1;
                        for (int i = 8; i < l.Length; i++)
                        {
                            char c = l[i];
                            if (c == '"') inQuote = !inQuote;
                            else if (c == ',' && !inQuote)
                            {
                                cut = i;
                                break;
                            }
                        }
                        string head = cut >= 0 ? l.Substring(0, cut) : l;
                        name = cut >= 0 ? l.Substring(cut + 1).Trim() : "";
                        foreach (Match m in AttrRe.Matches(head)) attrs[m.Groups[1].Value.ToLowerInvariant()] = m.Groups[2].Value;
                    }
                    else if (l.StartsWith("#EXTVLCOPT:", StringComparison.OrdinalIgnoreCase))
                    {
                        string opt = l.Substring(11);
                        int eq = opt.IndexOf('=');
                        if (eq > 0)
                        {
                            string k = opt.Substring(0, eq).Trim().ToLowerInvariant();
                            string v = opt.Substring(eq + 1).Trim();
                            if (k == "http-referrer") refr = v;
                            if (k == "http-user-agent") ua = v;
                        }
                    }
                    else if (l.StartsWith("#EXTGRP:", StringComparison.OrdinalIgnoreCase))
                    {
                        attrs["group-title"] = l.Substring(8).Trim();
                    }
                    else if (l.StartsWith("#"))
                    {
                        // dòng chú thích khác: bỏ qua
                    }
                    else
                    {
                        if (l.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                            l.StartsWith("https://", StringComparison.OrdinalIgnoreCase) ||
                            l.StartsWith("rtmp://", StringComparison.OrdinalIgnoreCase) ||
                            l.StartsWith("rtsp://", StringComparison.OrdinalIgnoreCase))
                        {
                            var labels = new List<string>();
                            string n = hasInfo ? name : "";
                            if (string.IsNullOrWhiteSpace(n)) n = Get(attrs, "tvg-name");
                            if (string.IsNullOrWhiteSpace(n)) n = l;
                            n = BracketRe.Replace(n, m =>
                            {
                                string t = m.Groups[1].Value;
                                string tl = t.ToLowerInvariant();
                                if (tl.Contains("geo")) labels.Add("Có thể chặn vùng");
                                else if (tl.Contains("24/7")) labels.Add("Không phát 24/7");
                                else labels.Add(t);
                                return "";
                            });
                            n = ResRe.Replace(n, m =>
                            {
                                labels.Insert(0, m.Groups[1].Value);
                                return "";
                            });
                            n = Spaces.Replace(n, " ").Trim();
                            string id = "tv:" + Txt.StableHash(l).ToString("x") + Txt.Base36(l.Length);
                            if (seen.Add(id))
                            {
                                list.Add(new Channel
                                {
                                    Id = id,
                                    Kind = Kind.TV,
                                    Name = n.Length == 0 ? "Không tên" : n,
                                    Url = l,
                                    Logo = Get(attrs, "tvg-logo").Trim(),
                                    Group = GroupName(Get(attrs, "group-title")),
                                    Labels = labels,
                                    Referrer = refr.Length > 0 ? refr : Get(attrs, "http-referrer"),
                                    UserAgent = ua.Length > 0 ? ua : (Get(attrs, "http-user-agent").Length > 0 ? Get(attrs, "http-user-agent") : Get(attrs, "user-agent")),
                                });
                            }
                        }
                        hasInfo = false;
                        name = "";
                        attrs = new Dictionary<string, string>();
                        refr = "";
                        ua = "";
                    }
                }
            }
            return list;
        }

        private static string Get(Dictionary<string, string> d, string k) => d.TryGetValue(k, out var v) && v != null ? v : "";

        private static string GroupName(string raw)
        {
            if (string.IsNullOrWhiteSpace(raw)) return "";
            var parts = raw.Split(';').Select(g => g.Trim()).Where(g => g.Length > 0).Take(2)
                .Select(g => Catalog.GroupVi.TryGetValue(g.ToLowerInvariant(), out var vi) ? vi : g);
            return string.Join(", ", parts);
        }
    }

    /// <summary>
    /// Tải danh sách kênh. Mỗi lần mở app đều lấy bản mới nhất từ nguồn,
    /// đồng thời lưu một bản trên máy để hiện ngay và dùng khi mất mạng.
    /// </summary>
    public static class Repo
    {
        private static readonly HttpClient Http = CreateClient();
        private static readonly string[] RbHosts =
        {
            "de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info",
            "nl1.api.radio-browser.info", "at1.api.radio-browser.info", "all.api.radio-browser.info",
        };
        private static string rbHost;
        private static readonly ConcurrentDictionary<string, List<Channel>> Mem = new ConcurrentDictionary<string, List<Channel>>();
        private static readonly ConcurrentDictionary<string, bool> Fresh = new ConcurrentDictionary<string, bool>();
        private static readonly Regex Ws = new Regex("\\s+", RegexOptions.Compiled);
        private static readonly Regex NonFile = new Regex("[^A-Za-z0-9_.-]", RegexOptions.Compiled);

        private static HttpClient CreateClient()
        {
            var h = new HttpClientHandler
            {
                AllowAutoRedirect = true,
                MaxAutomaticRedirections = 8,
                AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
            };
            var c = new HttpClient(h) { Timeout = TimeSpan.FromSeconds(90) };
            c.DefaultRequestHeaders.UserAgent.ParseAdd("MayThu/1.0 (Windows)");
            return c;
        }

        /// <summary>Tải văn bản qua HTTP(S), hoặc đọc tệp trên máy nếu là đường dẫn tệp.</summary>
        public static async Task<string> GetText(string url, int timeoutSec = 45)
        {
            if (url.StartsWith("file:", StringComparison.OrdinalIgnoreCase) && !url.StartsWith("file://", StringComparison.OrdinalIgnoreCase))
            {
                string local = Path.Combine(Store.Dir, url.Substring(5).Replace('/', Path.DirectorySeparatorChar));
                if (!File.Exists(local)) throw new IOException("Không tìm thấy tệp danh sách trên máy");
                return await File.ReadAllTextAsync(local);
            }
            if (File.Exists(url)) return await File.ReadAllTextAsync(url);
            using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSec)))
            {
                HttpResponseMessage resp;
                try
                {
                    resp = await Http.GetAsync(url, HttpCompletionOption.ResponseContentRead, cts.Token);
                }
                catch (TaskCanceledException)
                {
                    throw new IOException("Máy chủ không trả lời (quá thời gian chờ)");
                }
                catch (HttpRequestException e)
                {
                    throw new IOException("Không kết nối được máy chủ: " + e.Message);
                }
                using (resp)
                {
                    if (!resp.IsSuccessStatusCode) throw new IOException("Máy chủ báo lỗi " + (int)resp.StatusCode);
                    byte[] bytes = await resp.Content.ReadAsByteArrayAsync();
                    return Encoding.UTF8.GetString(bytes);
                }
            }
        }

        public static List<Channel> Cached(string key)
        {
            if (Mem.TryGetValue(key, out var m)) return m;
            try
            {
                string f = FileFor(key);
                if (!File.Exists(f)) return null;
                var list = JsonSerializer.Deserialize<List<Channel>>(File.ReadAllText(f), Store.Json);
                if (list == null || list.Count == 0) return null;
                Mem[key] = list;
                return list;
            }
            catch
            {
                return null;
            }
        }

        public static bool IsFresh(string key) => Fresh.ContainsKey(key);

        /// <summary>Tải bản mới; lỗi thì ném ra để nơi gọi quyết định (thường là dùng bản lưu).</summary>
        public static async Task<List<Channel>> Load(string key, bool force, Func<Task<List<Channel>>> fetch)
        {
            if (!force && Fresh.ContainsKey(key) && Mem.TryGetValue(key, out var m)) return m;
            var raw = await fetch();
            var seen = new HashSet<string>();
            var list = new List<Channel>(raw.Count);
            foreach (var c in raw) if (seen.Add(c.Id)) list.Add(c);
            Mem[key] = list;
            Fresh[key] = true;
            _ = Task.Run(() =>
            {
                try
                {
                    File.WriteAllText(FileFor(key), JsonSerializer.Serialize(list, Store.Json));
                }
                catch
                {
                    // ổ đĩa đầy hoặc không ghi được: bỏ qua, lần sau tải lại
                }
            });
            return list;
        }

        public static void ClearAll()
        {
            Mem.Clear();
            Fresh.Clear();
            try
            {
                foreach (var f in Directory.GetFiles(Store.CacheDir, "*.json")) File.Delete(f);
            }
            catch
            {
            }
        }

        private static string FileFor(string key) => Path.Combine(Store.CacheDir, NonFile.Replace(key, "_") + ".json");

        public static async Task<List<Channel>> TvList(string path)
        {
            string text = await GetText(Catalog.Iptv + path, 60);
            return await Task.Run(() => M3u.Parse(text));
        }

        public static async Task<List<Channel>> PlaylistItems(string url)
        {
            string text = await GetText(url, 60);
            return await Task.Run(() => M3u.Parse(text));
        }

        public static async Task<List<Channel>> Radio(Dictionary<string, string> ps)
        {
            var all = new List<KeyValuePair<string, string>>
            {
                new KeyValuePair<string, string>("hidebroken", "true"),
                new KeyValuePair<string, string>("order", "votes"),
                new KeyValuePair<string, string>("reverse", "true"),
            };
            foreach (var kv in ps) all.Add(kv);
            string qs = string.Join("&", all.Select(kv => Uri.EscapeDataString(kv.Key) + "=" + Uri.EscapeDataString(kv.Value)));
            var hosts = new List<string>();
            if (rbHost != null) hosts.Add(rbHost);
            foreach (var h in RbHosts) if (!hosts.Contains(h)) hosts.Add(h);
            Exception last = null;
            foreach (var h in hosts)
            {
                foreach (var scheme in new[] { "https", "http" })
                {
                    try
                    {
                        string text = await GetText(scheme + "://" + h + "/json/stations/search?" + qs, 20);
                        var list = await Task.Run(() => ParseStations(text));
                        rbHost = h;
                        return list;
                    }
                    catch (Exception e)
                    {
                        last = e;
                    }
                }
            }
            throw last ?? new IOException("Không kết nối được danh bạ radio");
        }

        /// <summary>Báo cho Radio Browser biết đài được nghe (giúp xếp hạng đài phổ biến).</summary>
        public static void CountClick(string uuid)
        {
            if (string.IsNullOrWhiteSpace(uuid)) return;
            _ = Task.Run(async () =>
            {
                try
                {
                    await GetText("https://" + (rbHost ?? RbHosts[0]) + "/json/url/" + Uri.EscapeDataString(uuid), 8);
                }
                catch
                {
                }
            });
        }

        private static string S(JsonElement o, string k) =>
            o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? (v.GetString() ?? "") : "";

        private static int I(JsonElement o, string k) =>
            o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out int n) ? n : 0;

        private static List<Channel> ParseStations(string text)
        {
            var list = new List<Channel>();
            var seen = new HashSet<string>();
            using (var doc = JsonDocument.Parse(text))
            {
                if (doc.RootElement.ValueKind != JsonValueKind.Array) return list;
                foreach (var o in doc.RootElement.EnumerateArray())
                {
                    string url = S(o, "url_resolved");
                    if (string.IsNullOrWhiteSpace(url)) url = S(o, "url");
                    url = url.Trim();
                    string name = Ws.Replace(S(o, "name"), " ").Trim();
                    string uuid = S(o, "stationuuid");
                    if (url.Length == 0 || name.Length == 0 || uuid.Length == 0) continue;
                    if (!seen.Add(Txt.Norm(name) + "|" + url)) continue;
                    string tags = string.Join(", ", S(o, "tags").Split(',').Select(t => t.Trim()).Where(t => t.Length > 0).Take(3));
                    list.Add(new Channel
                    {
                        Id = "r:" + uuid,
                        Kind = Kind.RADIO,
                        Name = name,
                        Url = url,
                        Logo = S(o, "favicon").Trim(),
                        Country = S(o, "country"),
                        Tags = tags,
                        Codec = S(o, "codec"),
                        Bitrate = I(o, "bitrate"),
                        Hls = I(o, "hls") == 1,
                        Uuid = uuid,
                    });
                }
            }
            return list;
        }
    }

    // ============================== BÁO THỨC ==============================

    public enum AlarmAction { PLAY, STOP }

    /// <summary>
    /// Một lịch hẹn: bật kênh (báo thức bằng radio/TV) hoặc tắt app.
    /// Days: bit 0 = Thứ Hai … bit 6 = Chủ Nhật; 0 = không lặp.
    /// Date: "yyyy-MM-dd" khi hẹn đúng một ngày cụ thể.
    /// </summary>
    public class AlarmItem
    {
        public const int ALL = 0b1111111;
        public const int WEEKDAYS = 0b0011111;
        public const int WEEKEND = 0b1100000;

        public int Id { get; set; }
        public bool Enabled { get; set; } = true;
        public AlarmAction Action { get; set; } = AlarmAction.PLAY;
        public int Hour { get; set; } = 6;
        public int Minute { get; set; }
        public int Days { get; set; }
        public string Date { get; set; } = "";
        public Channel Channel { get; set; }
        public int Volume { get; set; } = 70;
        public bool Ramp { get; set; } = true;
        public int AutoStopMin { get; set; } = 60;
        public string Label { get; set; } = "";

        [JsonIgnore]
        public string TimeText => Hour.ToString("00") + ":" + Minute.ToString("00");

        public AlarmItem Clone() => (AlarmItem)MemberwiseClone();

        public string RepeatText()
        {
            if (!string.IsNullOrWhiteSpace(Date))
                return AlarmTime.TryParseDate(Date, out var d) ? "Một lần, " + AlarmTime.LongDate(d) : "Một lần";
            if (Days == 0) return "Một lần";
            if (Days == ALL) return "Hằng ngày";
            if (Days == WEEKDAYS) return "Thứ 2 đến thứ 6";
            if (Days == WEEKEND) return "Thứ 7 và Chủ nhật";
            var parts = new List<string>();
            for (int i = 0; i < 7; i++) if ((Days & (1 << i)) != 0) parts.Add(AlarmTime.Short[i]);
            return string.Join(", ", parts);
        }

        public DateTime? NextTrigger(DateTime now) => AlarmTime.Next(this, now);
    }

    /// <summary>Tính giờ reo và hiển thị ngày giờ kiểu Việt Nam.</summary>
    public static class AlarmTime
    {
        public static readonly string[] Short = { "T2", "T3", "T4", "T5", "T6", "T7", "CN" };
        public static readonly string[] Long = { "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật" };

        /// <summary>0 = Thứ Hai … 6 = Chủ Nhật</summary>
        public static int DayIndex(DateTime d) => ((int)d.DayOfWeek + 6) % 7;

        public static bool TryParseDate(string s, out DateTime d) =>
            DateTime.TryParseExact((s ?? "").Trim(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out d);

        public static string FormatDate(DateTime d) => d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

        public static string LongDate(DateTime d) => Long[DayIndex(d)] + ", " + d.ToString("dd'/'MM'/'yyyy", CultureInfo.InvariantCulture);

        public static DateTime? Next(AlarmItem a, DateTime now)
        {
            if (!string.IsNullOrWhiteSpace(a.Date))
            {
                if (!TryParseDate(a.Date, out var d)) return null;
                var t = d.Date.AddHours(a.Hour).AddMinutes(a.Minute);
                return t > now ? t : (DateTime?)null;
            }
            var b = now.Date.AddHours(a.Hour).AddMinutes(a.Minute);
            if (a.Days == 0) return b > now ? b : b.AddDays(1);
            for (int i = 0; i <= 7; i++)
            {
                var c = b.AddDays(i);
                if (c > now && (a.Days & (1 << DayIndex(c))) != 0) return c;
            }
            return null;
        }

        /// <summary>"Còn 7 giờ 12 phút"</summary>
        public static string UntilText(DateTime t, DateTime now)
        {
            long mins = (long)Math.Ceiling((t - now).TotalMinutes);
            if (mins < 0) mins = 0;
            long d = mins / 1440, h = mins % 1440 / 60, m = mins % 60;
            if (d > 0) return "Còn " + d + " ngày " + h + " giờ";
            if (h > 0) return "Còn " + h + " giờ " + m + " phút";
            return "Còn " + m + " phút";
        }

        /// <summary>"06:30 ngày mai", "06:30 hôm nay", "06:30 Thứ Hai 17/11"</summary>
        public static string WhenText(DateTime t, DateTime now)
        {
            string time = t.ToString("HH':'mm", CultureInfo.InvariantCulture);
            int diff = (t.Date - now.Date).Days;
            string day = diff == 0 ? "hôm nay" : diff == 1 ? "ngày mai" : Long[DayIndex(t)] + " " + t.ToString("dd'/'MM", CultureInfo.InvariantCulture);
            return time + " " + day;
        }
    }

    // ============================== LƯU TRỮ ==============================

    public class Settings
    {
        public List<Channel> Favorites { get; set; } = new List<Channel>();
        public List<Channel> Recent { get; set; } = new List<Channel>();
        public List<Channel> Custom { get; set; } = new List<Channel>();
        public List<Playlist> Playlists { get; set; } = new List<Playlist>();
        public List<AlarmItem> Alarms { get; set; } = new List<AlarmItem>();
        public int Volume { get; set; } = 80;
        public string Page { get; set; } = "tv";
        public string TvCat { get; set; } = "vn";
        public string RadioCat { get; set; } = "vn";
        public string FavCat { get; set; } = "fav";
        public string MineCat { get; set; } = "custom";
        public bool CloseToTray { get; set; } = true;
        public bool TrayTipShown { get; set; }
        public double ListWidth { get; set; } = 440;
        public double WinLeft { get; set; } = double.NaN;
        public double WinTop { get; set; } = double.NaN;
        public double WinWidth { get; set; }
        public double WinHeight { get; set; }
        public bool WinMax { get; set; }
    }

    /// <summary>
    /// Dữ liệu (yêu thích, báo thức, danh sách đã tải) nằm trong thư mục "MayThu-data" cạnh MayThu.exe,
    /// nên chép cả thư mục sang máy khác là mang theo được. Không ghi được ở đó thì dùng %AppData%\MayThu.
    /// </summary>
    public static class Store
    {
        public static string Dir { get; private set; } = "";
        public static string CacheDir { get; private set; } = "";
        public static Settings S { get; private set; } = new Settings();
        public static string SelfTestDir { get; set; }

        public static readonly JsonSerializerOptions Json = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true,
            NumberHandling = JsonNumberHandling.AllowNamedFloatingPointLiterals,
            Converters = { new JsonStringEnumConverter() },
        };

        private static readonly JsonSerializerOptions JsonPretty = new JsonSerializerOptions
        {
            WriteIndented = true,
            PropertyNameCaseInsensitive = true,
            NumberHandling = JsonNumberHandling.AllowNamedFloatingPointLiterals,
            Converters = { new JsonStringEnumConverter() },
        };

        private static DispatcherTimer saveTimer;

        public static void Init()
        {
            if (SelfTestDir != null)
            {
                Dir = Path.Combine(SelfTestDir, "data");
            }
            else
            {
                string local = Path.Combine(AppContext.BaseDirectory, "MayThu-data");
                Dir = CanWrite(local)
                    ? local
                    : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MayThu");
            }
            Directory.CreateDirectory(Dir);
            CacheDir = Path.Combine(Dir, "cache");
            Directory.CreateDirectory(CacheDir);
            try
            {
                string f = Path.Combine(Dir, "settings.json");
                if (File.Exists(f)) S = JsonSerializer.Deserialize<Settings>(File.ReadAllText(f), Json) ?? new Settings();
            }
            catch (Exception e)
            {
                LogError(e);
                S = new Settings();
            }
            if (S.Favorites == null) S.Favorites = new List<Channel>();
            if (S.Recent == null) S.Recent = new List<Channel>();
            if (S.Custom == null) S.Custom = new List<Channel>();
            if (S.Playlists == null) S.Playlists = new List<Playlist>();
            if (S.Alarms == null) S.Alarms = new List<AlarmItem>();
        }

        private static bool CanWrite(string dir)
        {
            try
            {
                Directory.CreateDirectory(dir);
                string f = Path.Combine(dir, ".kiem-tra-ghi");
                File.WriteAllText(f, "ok");
                File.Delete(f);
                return true;
            }
            catch
            {
                return false;
            }
        }

        public static void SaveSoon()
        {
            if (saveTimer == null)
            {
                saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
                saveTimer.Tick += (s, e) =>
                {
                    saveTimer.Stop();
                    SaveNow();
                };
            }
            saveTimer.Stop();
            saveTimer.Start();
        }

        public static void SaveNow()
        {
            try
            {
                saveTimer?.Stop();
                string f = Path.Combine(Dir, "settings.json");
                string tmp = f + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(S, JsonPretty));
                File.Move(tmp, f, true);
            }
            catch (Exception e)
            {
                LogError(e);
            }
        }

        public static void LogError(Exception e)
        {
            if (e == null) return;
            string line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture) + "  " + e + Environment.NewLine + Environment.NewLine;
            try
            {
                if (!string.IsNullOrEmpty(Dir))
                {
                    string f = Path.Combine(Dir, "loi.log");
                    if (File.Exists(f) && new FileInfo(f).Length > 512 * 1024) File.Delete(f);
                    File.AppendAllText(f, line);
                }
            }
            catch
            {
            }
            try
            {
                if (SelfTestDir != null) File.AppendAllText(Path.Combine(SelfTestDir, "errors.txt"), line);
            }
            catch
            {
            }
        }
    }
}
