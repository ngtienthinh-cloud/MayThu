using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Media;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using IOPath = System.IO.Path;

namespace MayThu
{
    public partial class MainWindow : Window
    {
        private static readonly int[] SleepSteps = { 0, 15, 30, 60, 90 };

        private readonly Player player;
        private readonly DispatcherTimer clock;
        private readonly DispatcherTimer searchTimer;
        private DispatcherTimer toastTimer;
        private DispatcherTimer fadeTimer;
        private System.Windows.Forms.NotifyIcon tray;

        private string page = "tv";
        private List<Channel> source = new List<Channel>();
        private List<Channel> shown = new List<Channel>();
        private ObservableCollection<Row> rows = new ObservableCollection<Row>();
        private bool listLoading;
        private string listNote = "";
        private string listErrTitle;
        private string listErrText;
        private int loadSeq;
        private bool suppressCat;
        private Action emptyAction;

        private bool reallyExit;
        private bool cleaned;
        private bool hiddenStart;
        private bool fullscreen;
        private WindowState fsPrevState = WindowState.Normal;
        private double fsPrevList = 440;
        private DateTime fsHintUntil;
        private int sleepIdx;
        private DateTime? sleepAt;

        // Báo thức
        private readonly Dictionary<int, DateTime> due = new Dictionary<int, DateTime>();
        private AlarmItem ringing;
        private DateTime ringStart;
        private DateTime? autoStopAt;
        private bool rampOn;
        private DateTime? rampT0;
        private bool usingFallback;
        private SoundPlayer fallbackSound;
        private DateTime lastBeep;
        private AlarmItem snoozeItem;
        private DateTime? snoozeAt;
        private int lastMinute = -1;

        public MainWindow()
        {
            InitializeComponent();
            player = new Player(Dispatcher);
            player.Changed += OnPlayerChanged;
            player.Started += AddRecent;
            ChannelList.ItemsSource = rows;
            ListCol.Width = new GridLength(Clamp(Store.S.ListWidth, 340, 760));
            RestorePlacement();
            player.Volume = Store.S.Volume;
            VolSlider.Value = Store.S.Volume;
            UpdateVolumeUi();

            Video.Loaded += (s, e) =>
            {
                try
                {
                    if (Video.MediaPlayer == null) Video.MediaPlayer = player.Mp;
                }
                catch (Exception ex)
                {
                    Store.LogError(ex);
                }
            };
            SourceInitialized += (s, e) => Native.DarkTitle(this);
            Loaded += OnLoaded;
            Closing += OnClosing;
            Closed += (s, e) =>
            {
                Cleanup();
                Application.Current.Shutdown();
            };
            PreviewKeyDown += OnKey;

            clock = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Normal, (s, e) => OnClock(), Dispatcher);
            clock.Start();
            searchTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(220) };
            searchTimer.Tick += (s, e) =>
            {
                searchTimer.Stop();
                ApplyFilter();
            };

            SetupTray();
            ListenForShow();
            BuildSettingsStatic();
            RecomputeAlarms();
            SelectNav(Store.S.Page);
            OnPlayerChanged();
        }

        private static double Clamp(double v, double lo, double hi) => double.IsNaN(v) ? lo : Math.Max(lo, Math.Min(hi, v));

        private Brush Res(string key) => (Brush)FindResource(key);

        // ============================== CỬA SỔ, KHAY ĐỒNG HỒ ==============================

        public void StartHidden()
        {
            hiddenStart = true;
            ShowInTaskbar = false;
            WindowState = WindowState.Minimized;
            Show();
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            if (hiddenStart)
            {
                Hide();
                ShowInTaskbar = true;
                hiddenStart = false;
            }
            else if (Store.S.WinMax && Store.SelfTestDir == null)
            {
                WindowState = WindowState.Maximized;
            }
            UpdateDial();
            if (Store.SelfTestDir != null) RunSelfTest();
        }

        private void RestorePlacement()
        {
            var s = Store.S;
            if (Store.SelfTestDir != null)
            {
                Width = 1240;
                Height = 780;
                return;
            }
            if (s.WinWidth >= 940 && s.WinHeight >= 600)
            {
                Width = s.WinWidth;
                Height = s.WinHeight;
            }
            if (!double.IsNaN(s.WinLeft) && !double.IsNaN(s.WinTop))
            {
                var vs = new Rect(SystemParameters.VirtualScreenLeft, SystemParameters.VirtualScreenTop,
                    SystemParameters.VirtualScreenWidth, SystemParameters.VirtualScreenHeight);
                if (vs.Contains(new Point(s.WinLeft + 80, s.WinTop + 30)))
                {
                    WindowStartupLocation = WindowStartupLocation.Manual;
                    Left = s.WinLeft;
                    Top = s.WinTop;
                }
            }
        }

        private void SavePlacement()
        {
            if (Store.SelfTestDir != null) return;
            try
            {
                bool max = fullscreen ? fsPrevState == WindowState.Maximized : WindowState == WindowState.Maximized;
                Rect r = (WindowState == WindowState.Normal && !fullscreen) ? new Rect(Left, Top, Width, Height) : RestoreBounds;
                if (r.Width > 0 && r.Height > 0 && !double.IsInfinity(r.Left))
                {
                    Store.S.WinLeft = r.Left;
                    Store.S.WinTop = r.Top;
                    Store.S.WinWidth = r.Width;
                    Store.S.WinHeight = r.Height;
                }
                Store.S.WinMax = max;
                Store.S.ListWidth = fullscreen ? fsPrevList : ListCol.ActualWidth;
                Store.SaveSoon();
            }
            catch
            {
            }
        }

        private void SetupTray()
        {
            try
            {
                tray = new System.Windows.Forms.NotifyIcon { Text = "Máy Thu" };
                var info = Application.GetResourceStream(new Uri("pack://application:,,,/app.ico"));
                try
                {
                    if (info != null) tray.Icon = new System.Drawing.Icon(info.Stream);
                }
                catch
                {
                    tray.Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath);
                }
                var menu = new System.Windows.Forms.ContextMenuStrip();
                menu.Items.Add("Mở Máy Thu", null, (s, e) => ShowFromTray());
                menu.Items.Add("Phát hoặc dừng", null, (s, e) => player.Toggle());
                menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
                menu.Items.Add("Thoát Máy Thu", null, (s, e) => ExitApp());
                tray.ContextMenuStrip = menu;
                tray.MouseClick += (s, e) =>
                {
                    if (e.Button == System.Windows.Forms.MouseButtons.Left) ShowFromTray();
                };
                tray.Visible = Store.SelfTestDir == null;
            }
            catch (Exception ex)
            {
                Store.LogError(ex);
                tray = null;
            }
        }

        private void UpdateTray()
        {
            if (tray == null) return;
            try
            {
                string t = player.Current != null && player.IsActive ? "Máy Thu: " + player.Current.Name : "Máy Thu";
                tray.Text = t.Length > 63 ? t.Substring(0, 63) : t;
            }
            catch
            {
            }
        }

        /// <summary>Mở lần hai (bấm lại biểu tượng) thì đưa cửa sổ đang chạy lên trước.</summary>
        private void ListenForShow()
        {
            if (Store.SelfTestDir != null) return;
            try
            {
                var ev = new EventWaitHandle(false, EventResetMode.AutoReset, App.ShowEventName);
                var t = new Thread(() =>
                {
                    while (true)
                    {
                        try
                        {
                            ev.WaitOne();
                        }
                        catch
                        {
                            return;
                        }
                        Dispatcher.BeginInvoke(new Action(() =>
                        {
                            ShowFromTray();
                            BringToFront();
                        }));
                    }
                })
                { IsBackground = true, Name = "MayThu-show" };
                t.Start();
            }
            catch (Exception ex)
            {
                Store.LogError(ex);
            }
        }

        public void ShowFromTray()
        {
            if (!IsVisible) Show();
            if (WindowState == WindowState.Minimized) WindowState = Store.S.WinMax ? WindowState.Maximized : WindowState.Normal;
            Activate();
        }

        private void BringToFront()
        {
            Topmost = true;
            Activate();
            var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
            t.Tick += (s, e) =>
            {
                t.Stop();
                Topmost = false;
            };
            t.Start();
        }

        private void HideToTray()
        {
            if (fullscreen) SetFullscreen(false);
            Hide();
        }

        private void OnClosing(object sender, CancelEventArgs e)
        {
            bool toTray = !reallyExit && Store.SelfTestDir == null && tray != null && Store.S.CloseToTray && Store.S.Alarms.Any(a => a.Enabled);
            SavePlacement();
            if (!toTray) return;
            e.Cancel = true;
            player.Stop();
            StopRinging(false);
            HideToTray();
            if (!Store.S.TrayTipShown)
            {
                Store.S.TrayTipShown = true;
                Store.SaveSoon();
                try
                {
                    tray.ShowBalloonTip(6000, "Máy Thu vẫn chạy ngầm để báo thức",
                        "Biểu tượng Máy Thu nằm ở khay đồng hồ, góc phải thanh tác vụ. Bấm chuột phải vào đó để thoát hẳn.",
                        System.Windows.Forms.ToolTipIcon.Info);
                }
                catch
                {
                }
            }
        }

        private void ExitApp()
        {
            reallyExit = true;
            Close();
        }

        private void Cleanup()
        {
            if (cleaned) return;
            cleaned = true;
            try
            {
                clock.Stop();
                StopFallback();
                if (tray != null)
                {
                    tray.Visible = false;
                    tray.Dispose();
                }
            }
            catch
            {
            }
            Native.KeepAwake(false, false);
            Store.SaveNow();
            try
            {
                player.Shutdown();
            }
            catch
            {
            }
        }

        public void Toast(string text)
        {
            if (ToastText == null || ToastBox == null) return;
            ToastText.Text = text;
            ToastBox.Visibility = Visibility.Visible;
            if (toastTimer == null)
            {
                toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3.5) };
                toastTimer.Tick += (s, e) =>
                {
                    toastTimer.Stop();
                    ToastBox.Visibility = Visibility.Collapsed;
                };
            }
            toastTimer.Stop();
            toastTimer.Start();
        }

        private static void OpenUrl(string url)
        {
            try
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch
            {
            }
        }

        // ============================== ĐIỀU HƯỚNG ==============================

        private RadioButton NavFor(string p)
        {
            switch (p)
            {
                case "radio": return NavRadio;
                case "fav": return NavFav;
                case "mine": return NavMine;
                case "alarm": return NavAlarm;
                case "settings": return NavSettings;
                default: return NavTv;
            }
        }

        private void SelectNav(string p)
        {
            var rb = NavFor(p);
            if (rb.IsChecked == true) ShowPage(p);
            else rb.IsChecked = true;
        }

        private void Nav_Checked(object sender, RoutedEventArgs e)
        {
            string p = sender == NavRadio ? "radio"
                : sender == NavFav ? "fav"
                : sender == NavMine ? "mine"
                : sender == NavAlarm ? "alarm"
                : sender == NavSettings ? "settings"
                : "tv";
            ShowPage(p);
        }

        private void NextAlarm_Click(object sender, MouseButtonEventArgs e) => SelectNav("alarm");

        private void ShowPage(string p)
        {
            page = p;
            Store.S.Page = p;
            Store.SaveSoon();
            bool browse = p == "tv" || p == "radio" || p == "fav" || p == "mine";
            BrowsePage.Visibility = browse ? Visibility.Visible : Visibility.Collapsed;
            AlarmPage.Visibility = p == "alarm" ? Visibility.Visible : Visibility.Collapsed;
            SettingsPage.Visibility = p == "settings" ? Visibility.Visible : Visibility.Collapsed;
            if (p == "alarm") RenderAlarms();
            else if (p == "settings") RenderSettings();
            else SetupBrowse(false);
        }

        // ============================== DANH SÁCH KÊNH ==============================

        private void SetupBrowse(bool force)
        {
            SearchBox.Text = "";
            switch (page)
            {
                case "tv":
                    PageTitle.Text = "Truyền hình";
                    FillCats(Catalog.Tv.Select(c => (c.Key, c.Title)), true, Store.S.TvCat);
                    break;
                case "radio":
                {
                    PageTitle.Text = "Radio";
                    var items = Catalog.Radio.Select(c => (c.Key, c.Title)).ToList();
                    if (Store.S.RadioCat.StartsWith("q:")) items.Insert(0, (Store.S.RadioCat, "Kết quả tìm: " + Store.S.RadioCat.Substring(2)));
                    FillCats(items, true, Store.S.RadioCat);
                    break;
                }
                case "fav":
                    PageTitle.Text = "Yêu thích";
                    FillCats(new[] { ("fav", "Kênh yêu thích"), ("recent", "Xem và nghe gần đây") }, false, Store.S.FavCat);
                    break;
                case "mine":
                {
                    PageTitle.Text = "Nguồn của tôi";
                    var mine = new List<(string, string)> { ("custom", "Kênh tự thêm") };
                    foreach (var pl in Store.S.Playlists) mine.Add(("pl:" + pl.Id, pl.Name));
                    FillCats(mine, false, Store.S.MineCat);
                    break;
                }
            }
            LoadCurrent(force);
        }

        private void FillCats(IEnumerable<(string Key, string Title)> items, bool countries, string selected)
        {
            suppressCat = true;
            CatBox.Items.Clear();
            foreach (var it in items) CatBox.Items.Add(new ComboBoxItem { Content = it.Title, Tag = it.Key });
            if (countries)
            {
                CatBox.Items.Add(new ComboBoxItem { Content = "Theo quốc gia", IsEnabled = false, FontSize = 12, FontWeight = FontWeights.SemiBold });
                foreach (var c in Catalog.Countries) CatBox.Items.Add(new ComboBoxItem { Content = c.Name, Tag = "c:" + c.Code });
            }
            int idx = 0;
            for (int i = 0; i < CatBox.Items.Count; i++)
            {
                if ((CatBox.Items[i] as ComboBoxItem)?.Tag as string == selected)
                {
                    idx = i;
                    break;
                }
            }
            CatBox.SelectedIndex = idx;
            suppressCat = false;
        }

        private string CurrentCat => (CatBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "";

        private void Cat_Changed(object sender, SelectionChangedEventArgs e)
        {
            if (suppressCat) return;
            string k = CurrentCat;
            if (k.Length == 0) return;
            switch (page)
            {
                case "tv": Store.S.TvCat = k; break;
                case "radio": Store.S.RadioCat = k; break;
                case "fav": Store.S.FavCat = k; break;
                case "mine": Store.S.MineCat = k; break;
            }
            Store.SaveSoon();
            SearchBox.Text = "";
            LoadCurrent(false);
        }

        private void LoadCurrent(bool force)
        {
            string k = CurrentCat;
            switch (page)
            {
                case "tv":
                {
                    string path = Catalog.TvPath(k) ?? Catalog.TvPath("vn");
                    LoadRemote("tv:" + k, () => Repo.TvList(path), force);
                    break;
                }
                case "radio":
                {
                    var ps = Catalog.RadioParams(k) ?? Catalog.RadioParams("vn");
                    LoadRemote("radio:" + k, () => Repo.Radio(ps), force);
                    break;
                }
                case "fav":
                    ShowLocal(k == "recent" ? Store.S.Recent : Store.S.Favorites);
                    break;
                case "mine":
                    if (k.StartsWith("pl:"))
                    {
                        var pl = Store.S.Playlists.FirstOrDefault(x => "pl:" + x.Id == k);
                        if (pl != null) LoadRemote(k, () => Repo.PlaylistItems(pl.Url), force);
                        else ShowLocal(new List<Channel>());
                    }
                    else
                    {
                        ShowLocal(Store.S.Custom);
                    }
                    break;
            }
        }

        private async void LoadRemote(string key, Func<Task<List<Channel>>> fetch, bool force)
        {
            int seq = ++loadSeq;
            var cached = force ? null : Repo.Cached(key);
            if (cached != null) SetSource(cached, false, Repo.IsFresh(key) ? "" : "đang cập nhật…");
            else SetSource(new List<Channel>(), true, "");
            if (!force && cached != null && Repo.IsFresh(key)) return;
            try
            {
                var list = await Repo.Load(key, force, fetch);
                if (seq != loadSeq) return;
                SetSource(list, false, "");
            }
            catch (Exception ex)
            {
                if (seq != loadSeq) return;
                var old = cached ?? Repo.Cached(key);
                if (old != null)
                {
                    SetSource(old, false, "chưa cập nhật được, đang dùng bản lưu trên máy");
                }
                else
                {
                    source = new List<Channel>();
                    listLoading = false;
                    listNote = "";
                    listErrTitle = "Không tải được danh sách";
                    listErrText = "Kiểm tra kết nối mạng rồi bấm Thử lại. (" + ex.Message + ")";
                    ApplyFilter();
                }
            }
        }

        private void ShowLocal(List<Channel> list)
        {
            ++loadSeq;
            SetSource(list.ToList(), false, "");
        }

        private void SetSource(List<Channel> list, bool loading, string note)
        {
            source = list;
            listLoading = loading;
            listNote = note;
            listErrTitle = null;
            listErrText = null;
            ApplyFilter();
        }

        private HashSet<string> FavIds() => new HashSet<string>(Store.S.Favorites.Select(c => c.Id));

        private bool IsFav(string id) => Store.S.Favorites.Any(c => c.Id == id);

        private void ApplyFilter()
        {
            string raw = SearchBox.Text.Trim();
            string q = Txt.Norm(raw);
            SearchHint.Visibility = SearchBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
            shown = q.Length == 0 ? source.ToList() : source.Where(c => c.SearchKey.Contains(q)).ToList();
            var fav = FavIds();
            string cur = player.Current?.Id;
            rows = new ObservableCollection<Row>(shown.Select(c => new Row(c, fav.Contains(c.Id), c.Id == cur)));
            ChannelList.ItemsSource = rows;

            string unit = page == "tv" ? "kênh" : page == "radio" ? "đài" : "mục";
            string sub;
            if (listLoading) sub = "Đang tải danh sách…";
            else if (q.Length > 0) sub = shown.Count + " trên " + source.Count + " " + unit;
            else sub = source.Count + " " + unit;
            if (!listLoading && listNote.Length > 0) sub += ", " + listNote;
            PageSub.Text = sub;

            string k = CurrentCat;
            if (listErrTitle != null && source.Count == 0)
                ShowEmptyBox(listErrTitle, listErrText, "Thử lại", () => LoadCurrent(true));
            else if (shown.Count > 0)
                EmptyBox.Visibility = Visibility.Collapsed;
            else if (listLoading)
                ShowEmptyBox("Đang tải danh sách…", "Lần đầu có thể mất vài giây.", null, null);
            else if (q.Length > 0 && page == "radio")
                ShowEmptyBox("Không thấy trong nhóm này", "Tìm “" + raw + "” trong toàn bộ danh bạ hơn 50.000 đài radio?", "Tìm trong toàn bộ danh bạ", SearchAllRadio);
            else if (q.Length > 0)
                ShowEmptyBox("Không tìm thấy", "Thử gõ từ khác, không cần gõ dấu.", null, null);
            else if (page == "fav" && k == "recent")
                ShowEmptyBox("Chưa xem hay nghe kênh nào", "Các kênh bạn mở sẽ hiện ở đây.", null, null);
            else if (page == "fav")
                ShowEmptyBox("Chưa có kênh yêu thích", "Bấm ngôi sao cạnh tên kênh để thêm vào đây.", null, null);
            else if (page == "mine" && k == "custom")
                ShowEmptyBox("Chưa có kênh tự thêm", "Dán đường dẫn phát (m3u8, mp3, aac…) bạn có để xem và nghe trong Máy Thu.", "Thêm kênh", AddChannel);
            else
                ShowEmptyBox("Danh sách trống", "Nguồn này chưa có kênh nào phát được.", null, null);

            BuildTools();
        }

        private void ShowEmptyBox(string title, string text, string btn, Action act)
        {
            EmptyTitle.Text = title;
            EmptyText.Text = text ?? "";
            EmptyText.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
            emptyAction = act;
            EmptyBtn.Content = btn ?? "";
            EmptyBtn.Visibility = btn == null ? Visibility.Collapsed : Visibility.Visible;
            EmptyBox.Visibility = Visibility.Visible;
        }

        private void EmptyBtn_Click(object sender, RoutedEventArgs e) => emptyAction?.Invoke();

        private void BuildTools()
        {
            ToolsRow.Children.Clear();
            string k = CurrentCat;
            string q = SearchBox.Text.Trim();
            if (page == "radio" && q.Length >= 2) AddTool("Tìm “" + q + "” trong toàn bộ danh bạ radio", "IcSearch", SearchAllRadio, true);
            if (page == "fav" && k == "recent" && Store.S.Recent.Count > 0) AddTool("Xóa lịch sử", "IcDelete", ClearRecent, false);
            if (page == "mine")
            {
                AddTool("Thêm kênh", "IcAdd", AddChannel, true);
                AddTool("Thêm danh sách M3U", "IcLink", AddPlaylistUrl, false);
                AddTool("Mở tệp M3U trên máy", "IcFolder", AddPlaylistFile, false);
                if (k.StartsWith("pl:")) AddTool("Xóa danh sách này", "IcDelete", RemovePlaylist, false);
            }
            ToolsRow.Visibility = ToolsRow.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private void AddTool(string text, string icon, Action act, bool accent)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            sp.Children.Add(new System.Windows.Shapes.Path
            {
                Data = (Geometry)FindResource(icon),
                Fill = Res(accent ? "AmberInkBrush" : "TextBrush"),
                Width = 14,
                Height = 14,
                Stretch = Stretch.Uniform,
                Margin = new Thickness(0, 0, 8, 0),
                VerticalAlignment = VerticalAlignment.Center,
            });
            sp.Children.Add(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 330 });
            var b = new Button
            {
                Content = sp,
                Height = 34,
                Padding = new Thickness(12, 0, 12, 0),
                Margin = new Thickness(0, 0, 8, 8),
                FontSize = 13,
            };
            if (accent) b.Style = (Style)FindResource("AccentBtn");
            b.Click += (s, e) => act();
            ToolsRow.Children.Add(b);
        }

        private void Search_Changed(object sender, TextChangedEventArgs e)
        {
            if (SearchHint == null || searchTimer == null) return;
            SearchHint.Visibility = SearchBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
            searchTimer.Stop();
            searchTimer.Start();
        }

        private void FocusSearch()
        {
            if (BrowsePage.Visibility != Visibility.Visible) SelectNav(page == "radio" ? "radio" : "tv");
            SearchBox.Focus();
            SearchBox.SelectAll();
        }

        private void SearchAllRadio()
        {
            string q = SearchBox.Text.Trim();
            if (q.Length < 2) return;
            Store.S.RadioCat = "q:" + q;
            Store.SaveSoon();
            if (page != "radio") SelectNav("radio");
            else SetupBrowse(false);
        }

        private void Refresh_Click(object sender, RoutedEventArgs e) => LoadCurrent(true);

        private void PlayRow(Row r)
        {
            if (r == null) return;
            player.Play(r.Ch, shown);
        }

        private void Row_Click(object sender, MouseButtonEventArgs e)
        {
            if (sender is ListBoxItem li && li.DataContext is Row r) PlayRow(r);
        }

        private void Row_RightDown(object sender, MouseButtonEventArgs e)
        {
            if (sender is ListBoxItem li) li.IsSelected = true;
        }

        private void RowStar_Click(object sender, RoutedEventArgs e)
        {
            if ((sender as FrameworkElement)?.DataContext is Row r) ToggleFav(r.Ch);
            e.Handled = true;
        }

        private void List_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter && ChannelList.SelectedItem is Row r)
            {
                PlayRow(r);
                e.Handled = true;
            }
        }

        private void FocusRow(int index)
        {
            if (index < 0 || index >= rows.Count) return;
            ChannelList.SelectedIndex = index;
            ChannelList.ScrollIntoView(rows[index]);
            ChannelList.UpdateLayout();
            if (ChannelList.ItemContainerGenerator.ContainerFromIndex(index) is ListBoxItem li) li.Focus();
        }

        private void RowMenu_Opened(object sender, RoutedEventArgs e)
        {
            var r = ChannelList.SelectedItem as Row;
            bool has = r != null;
            MiPlay.IsEnabled = has;
            MiFav.IsEnabled = has;
            MiCopy.IsEnabled = has;
            MiFav.Header = has && IsFav(r.Ch.Id) ? "Bỏ khỏi yêu thích" : "Thêm vào yêu thích";
            bool removable = page == "fav" || (page == "mine" && CurrentCat == "custom");
            MiRemove.Visibility = removable ? Visibility.Visible : Visibility.Collapsed;
            MiRemove.IsEnabled = has;
        }

        private void MiPlay_Click(object sender, RoutedEventArgs e) => PlayRow(ChannelList.SelectedItem as Row);

        private void MiFav_Click(object sender, RoutedEventArgs e)
        {
            if (ChannelList.SelectedItem is Row r) ToggleFav(r.Ch);
        }

        private void MiCopy_Click(object sender, RoutedEventArgs e)
        {
            if (!(ChannelList.SelectedItem is Row r)) return;
            try
            {
                Clipboard.SetText(r.Ch.Url);
                Toast("Đã sao chép đường dẫn phát của " + r.Ch.Name);
            }
            catch
            {
                Toast("Không sao chép được, hãy thử lại");
            }
        }

        private void MiRemove_Click(object sender, RoutedEventArgs e)
        {
            if (!(ChannelList.SelectedItem is Row r)) return;
            string id = r.Ch.Id;
            if (page == "fav" && CurrentCat == "recent") Store.S.Recent.RemoveAll(c => c.Id == id);
            else if (page == "fav") Store.S.Favorites.RemoveAll(c => c.Id == id);
            else if (page == "mine") Store.S.Custom.RemoveAll(c => c.Id == id);
            Store.SaveSoon();
            LoadCurrent(false);
            UpdateFavBtn();
        }

        private void ToggleFav(Channel ch)
        {
            if (ch == null) return;
            int i = Store.S.Favorites.FindIndex(c => c.Id == ch.Id);
            bool now;
            if (i >= 0)
            {
                Store.S.Favorites.RemoveAt(i);
                now = false;
                Toast("Đã bỏ " + ch.Name + " khỏi yêu thích");
            }
            else
            {
                Store.S.Favorites.Insert(0, ch);
                now = true;
                Toast("Đã thêm " + ch.Name + " vào yêu thích");
            }
            Store.SaveSoon();
            foreach (var r in rows) if (r.Ch.Id == ch.Id) r.IsFav = now;
            UpdateFavBtn();
            if (page == "fav" && CurrentCat == "fav") ShowLocal(Store.S.Favorites);
        }

        private void AddRecent(Channel ch)
        {
            Store.S.Recent.RemoveAll(c => c.Id == ch.Id);
            Store.S.Recent.Insert(0, ch);
            if (Store.S.Recent.Count > 50) Store.S.Recent.RemoveRange(50, Store.S.Recent.Count - 50);
            Store.SaveSoon();
        }

        private void ClearRecent()
        {
            Store.S.Recent.Clear();
            Store.SaveSoon();
            LoadCurrent(false);
        }

        // ============================== NGUỒN CỦA TÔI ==============================

        private static bool IsUrl(string u) =>
            Uri.TryCreate(u, UriKind.Absolute, out var x) &&
            (x.Scheme == "http" || x.Scheme == "https" || x.Scheme == "rtmp" || x.Scheme == "rtsp");

        private void AddChannel()
        {
            var r = Ask.Show(this, "Thêm kênh", new[] { "Tên kênh hoặc đài", "Đường dẫn phát" },
                new[] { null, "Ví dụ https://…/index.m3u8 hoặc https://…/stream.mp3" });
            if (r == null) return;
            string name = r[0];
            string url = r[1];
            if (!IsUrl(url))
            {
                Toast("Đường dẫn phát chưa đúng: cần bắt đầu bằng http, https, rtmp hoặc rtsp");
                return;
            }
            if (name.Length == 0) name = url;
            string lu = url.ToLowerInvariant();
            bool tv = lu.Contains(".m3u8") || lu.Contains(".mpd") || lu.Contains(".ts") || lu.StartsWith("rtmp") || lu.StartsWith("rtsp");
            var ch = new Channel
            {
                Id = "u:" + Txt.StableHash(url).ToString("x") + Txt.Base36(url.Length),
                Kind = tv ? Kind.TV : Kind.RADIO,
                Name = name,
                Url = url,
                Group = "Kênh tự thêm",
                Tags = "Kênh tự thêm",
            };
            Store.S.Custom.RemoveAll(c => c.Id == ch.Id);
            Store.S.Custom.Insert(0, ch);
            Store.S.MineCat = "custom";
            Store.SaveSoon();
            if (page == "mine") SetupBrowse(false);
            else SelectNav("mine");
            player.Play(ch, Store.S.Custom);
        }

        private void AddPlaylistUrl()
        {
            var r = Ask.Show(this, "Thêm danh sách IPTV", new[] { "Tên danh sách", "Đường dẫn tệp .m3u hoặc .m3u8" },
                new[] { null, "Danh sách kênh dạng M3U mà nhà cung cấp hoặc bạn bè gửi cho bạn." });
            if (r == null) return;
            if (!IsUrl(r[1]))
            {
                Toast("Đường dẫn chưa đúng: cần bắt đầu bằng http hoặc https");
                return;
            }
            AddPlaylist(r[0].Length > 0 ? r[0] : "Danh sách " + (Store.S.Playlists.Count + 1), r[1]);
        }

        private void AddPlaylistFile()
        {
            var dlg = new OpenFileDialog
            {
                Title = "Chọn tệp danh sách kênh",
                Filter = "Danh sách kênh (*.m3u, *.m3u8)|*.m3u;*.m3u8|Tất cả tệp (*.*)|*.*",
            };
            if (dlg.ShowDialog(this) != true) return;
            try
            {
                // Chép vào thư mục dữ liệu để mang sang máy khác vẫn dùng được
                string id = Guid.NewGuid().ToString("N").Substring(0, 8);
                string dir = IOPath.Combine(Store.Dir, "playlists");
                Directory.CreateDirectory(dir);
                string name = id + IOPath.GetExtension(dlg.FileName);
                File.Copy(dlg.FileName, IOPath.Combine(dir, name), true);
                AddPlaylist(IOPath.GetFileNameWithoutExtension(dlg.FileName), "file:playlists/" + name, id);
            }
            catch (Exception ex)
            {
                Toast("Không đọc được tệp: " + ex.Message);
            }
        }

        private void AddPlaylist(string name, string url, string id = null)
        {
            var pl = new Playlist { Id = id ?? Guid.NewGuid().ToString("N").Substring(0, 8), Name = name, Url = url };
            Store.S.Playlists.Add(pl);
            Store.S.MineCat = "pl:" + pl.Id;
            Store.SaveSoon();
            if (page == "mine") SetupBrowse(true);
            else SelectNav("mine");
        }

        private void RemovePlaylist()
        {
            string k = CurrentCat;
            var pl = Store.S.Playlists.FirstOrDefault(x => "pl:" + x.Id == k);
            if (pl == null) return;
            if (MessageBox.Show(this, "Xóa danh sách “" + pl.Name + "” khỏi Nguồn của tôi?", "Máy Thu",
                    MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            Store.S.Playlists.Remove(pl);
            Store.S.MineCat = "custom";
            Store.SaveSoon();
            SetupBrowse(false);
        }

        // ============================== PHÁT ==============================

        private void OnPlayerChanged()
        {
            var ch = player.Current;
            if (ch == null)
            {
                BarName.Text = "Chưa phát kênh nào";
                BarStatus.Text = "Chọn một kênh ở danh sách bên trái";
                BarInitials.Text = "";
                BarLogo.Source = null;
            }
            else
            {
                BarName.Text = ch.Name;
                bool song = player.State == PState.Playing && player.NowPlaying.Length > 0;
                BarStatus.Text = song ? "♪ " + player.NowPlaying : player.Status;
                BarInitials.Text = Txt.Initials(ch.Name);
                BarLogo.Source = LogoCache.Get(ch.Logo, 104);
            }
            PlayBtn.Tag = FindResource(player.IsActive ? "IcPause" : "IcPlay");
            Title = ch != null && player.IsActive ? ch.Name + " - Máy Thu" : "Máy Thu";
            UpdateFavBtn();
            UpdateDial();
            UpdateStage();
            string cur = ch?.Id;
            foreach (var r in rows) r.IsCurrent = r.Ch.Id == cur;
            UpdateTray();
            Native.KeepAwake(player.IsActive || ringing != null, (player.IsActive && player.HasVideo) || ringing != null);
        }

        private void UpdateStage()
        {
            var ch = player.Current;
            bool video = player.HasVideo && player.State == PState.Playing;
            Video.Visibility = video ? Visibility.Visible : Visibility.Hidden;
            VideoOverlay.Visibility = video ? Visibility.Visible : Visibility.Collapsed;
            CardRoot.Visibility = video ? Visibility.Collapsed : Visibility.Visible;
            if (video) return;
            if (ch == null)
            {
                CardBrand.Visibility = Visibility.Visible;
                CardLogoBox.Visibility = Visibility.Collapsed;
                CardName.Text = "Chọn một kênh để bắt đầu";
                CardSong.Visibility = Visibility.Collapsed;
                CardStatus.Text = "Bấm vào một kênh truyền hình hoặc đài radio ở danh sách bên trái.";
                CardStatus.Foreground = Res("MutedBrush");
                CardActions.Visibility = Visibility.Collapsed;
                CardHelp.Visibility = Visibility.Visible;
                return;
            }
            CardBrand.Visibility = Visibility.Collapsed;
            CardLogoBox.Visibility = Visibility.Visible;
            CardInitials.Text = Txt.Initials(ch.Name);
            CardLogo.Source = LogoCache.Get(ch.Logo, 256);
            CardName.Text = ch.Name;
            bool song = player.NowPlaying.Length > 0 && player.State == PState.Playing;
            CardSong.Text = song ? "♪ " + player.NowPlaying : "";
            CardSong.Visibility = song ? Visibility.Visible : Visibility.Collapsed;
            string status = player.Status;
            if (player.State == PState.Playing)
            {
                status = ch.Kind == Kind.TV ? "Đang phát, chờ hình ảnh hoặc kênh chỉ có tiếng" : "Đang phát. " + ch.Subtitle;
            }
            if (usingFallback) status = "Không phát được kênh, đang dùng chuông báo thức của máy";
            CardStatus.Text = status;
            CardStatus.Foreground = Res(player.State == PState.Error ? "RedBrush" : "MutedBrush");
            CardActions.Visibility = player.State == PState.Error ? Visibility.Visible : Visibility.Collapsed;
            CardHelp.Visibility = Visibility.Collapsed;
        }

        private void UpdateDial()
        {
            if (player.Current == null)
            {
                Needle.Visibility = Visibility.Collapsed;
                return;
            }
            Needle.Visibility = Visibility.Visible;
            double w = Math.Max(0, DialCanvas.ActualWidth - 3);
            Canvas.SetLeft(Needle, w * player.DialPosition);
        }

        private void Dial_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateDial();

        private void UpdateFavBtn()
        {
            var ch = player.Current;
            bool fav = ch != null && IsFav(ch.Id);
            FavBtn.Tag = FindResource(fav ? "IcStar" : "IcStarBorder");
            FavBtn.Foreground = Res(fav ? "AmberBrush" : "TextBrush");
            FavBtn.IsEnabled = ch != null;
            FavBtn.ToolTip = fav ? "Bỏ khỏi yêu thích" : "Thêm vào yêu thích";
        }

        private void UpdateVolumeUi()
        {
            VolText.Text = player.Muted ? "Tắt" : player.Volume + "%";
            MuteBtn.Tag = FindResource(player.Muted || player.Volume == 0 ? "IcMute" : "IcVolume");
            MuteBtn.ToolTip = player.Muted ? "Bật tiếng (M)" : "Tắt tiếng (M)";
        }

        private void Vol_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (player == null) return;
            int v = (int)Math.Round(e.NewValue);
            player.Volume = v;
            Store.S.Volume = v;
            Store.SaveSoon();
            UpdateVolumeUi();
        }

        private void ChangeVol(int d) => VolSlider.Value = Math.Max(0, Math.Min(100, VolSlider.Value + d));

        private void Mute_Click(object sender, RoutedEventArgs e)
        {
            player.Muted = !player.Muted;
            UpdateVolumeUi();
        }

        private void Play_Click(object sender, RoutedEventArgs e)
        {
            if (player.Current == null)
            {
                if (rows.Count > 0) PlayRow(rows[0]);
                return;
            }
            player.Toggle();
        }

        private void Prev_Click(object sender, RoutedEventArgs e) => player.Next(-1);

        private void Next_Click(object sender, RoutedEventArgs e)
        {
            if (player.Current == null)
            {
                if (rows.Count > 0) PlayRow(rows[0]);
                return;
            }
            player.Next(1);
        }

        private void Retry_Click(object sender, RoutedEventArgs e) => player.Retry();

        private void FavBtn_Click(object sender, RoutedEventArgs e) => ToggleFav(player.Current);

        private void Fs_Click(object sender, RoutedEventArgs e) => SetFullscreen(!fullscreen);

        private void Splitter_DragCompleted(object sender, DragCompletedEventArgs e)
        {
            Store.S.ListWidth = ListCol.ActualWidth;
            Store.SaveSoon();
            UpdateDial();
        }

        private void Sleep_Click(object sender, RoutedEventArgs e)
        {
            sleepIdx = (sleepIdx + 1) % SleepSteps.Length;
            int m = SleepSteps[sleepIdx];
            sleepAt = m == 0 ? (DateTime?)null : DateTime.Now.AddMinutes(m);
            Toast(m == 0 ? "Đã hủy hẹn giờ tắt" : "Máy Thu sẽ tự dừng phát sau " + m + " phút");
            UpdateSleepUi();
        }

        private void UpdateSleepUi()
        {
            if (sleepAt == null)
            {
                SleepText.Text = "";
                SleepBtn.Foreground = Res("TextBrush");
                return;
            }
            int left = (int)Math.Ceiling((sleepAt.Value - DateTime.Now).TotalMinutes);
            SleepText.Text = Math.Max(0, left) + " phút";
            SleepBtn.Foreground = Res("AmberBrush");
        }

        /// <summary>Giảm âm lượng dần trong 3 giây rồi làm tiếp việc sau.</summary>
        private void FadeOut(Action done)
        {
            fadeTimer?.Stop();
            double start = player.Gain;
            int step = 0;
            var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(150) };
            fadeTimer = t;
            t.Tick += (s, e) =>
            {
                step++;
                player.Gain = start * (1 - step / 20.0);
                if (step < 20) return;
                t.Stop();
                done();
            };
            t.Start();
        }

        private void SetFullscreen(bool on)
        {
            if (on == fullscreen) return;
            fullscreen = on;
            if (on)
            {
                fsPrevState = WindowState;
                fsPrevList = ListCol.ActualWidth;
                Sidebar.Visibility = Visibility.Collapsed;
                LeftPane.Visibility = Visibility.Collapsed;
                Splitter.Visibility = Visibility.Collapsed;
                PlayerBar.Visibility = Visibility.Collapsed;
                SideCol.Width = new GridLength(0);
                ListCol.MinWidth = 0;
                ListCol.Width = new GridLength(0);
                SplitCol.Width = new GridLength(0);
                WindowStyle = WindowStyle.None;
                ResizeMode = ResizeMode.NoResize;
                if (WindowState == WindowState.Maximized) WindowState = WindowState.Normal;
                WindowState = WindowState.Maximized;
                fsHintUntil = DateTime.Now.AddSeconds(3);
                FsHint.Visibility = Visibility.Visible;
            }
            else
            {
                WindowStyle = WindowStyle.SingleBorderWindow;
                ResizeMode = ResizeMode.CanResize;
                WindowState = WindowState.Normal;
                if (fsPrevState == WindowState.Maximized) WindowState = WindowState.Maximized;
                Sidebar.Visibility = Visibility.Visible;
                LeftPane.Visibility = Visibility.Visible;
                Splitter.Visibility = Visibility.Visible;
                PlayerBar.Visibility = Visibility.Visible;
                SideCol.Width = new GridLength(214);
                ListCol.MinWidth = 340;
                ListCol.Width = new GridLength(Clamp(fsPrevList, 340, 760));
                SplitCol.Width = new GridLength(6);
                FsHint.Visibility = Visibility.Collapsed;
            }
            FsBtn.Tag = FindResource(on ? "IcFullscreenExit" : "IcFullscreen");
            Activate();
        }

        private void Video_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ClickCount == 2) SetFullscreen(!fullscreen);
            Activate();
        }

        private void Card_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ClickCount == 2 && player.Current != null) SetFullscreen(!fullscreen);
        }

        private void OnKey(object sender, KeyEventArgs e)
        {
            bool ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            if (ctrl && e.Key == Key.F)
            {
                FocusSearch();
                e.Handled = true;
                return;
            }
            if (Keyboard.FocusedElement is TextBox tb)
            {
                if (e.Key == Key.Escape)
                {
                    if (tb.Text.Length > 0) tb.Text = "";
                    else ChannelList.Focus();
                    e.Handled = true;
                }
                else if (e.Key == Key.Down && tb == SearchBox && rows.Count > 0)
                {
                    FocusRow(0);
                    e.Handled = true;
                }
                else if (e.Key == Key.Enter && tb == SearchBox)
                {
                    searchTimer.Stop();
                    ApplyFilter();
                    if (rows.Count > 0) PlayRow(rows[0]);
                    else if (page == "radio") SearchAllRadio();
                    e.Handled = true;
                }
                return;
            }
            if (ctrl) return;
            switch (e.Key)
            {
                case Key.Space:
                case Key.MediaPlayPause:
                    if (player.Current == null && rows.Count > 0) PlayRow(rows[0]);
                    else player.Toggle();
                    break;
                case Key.N:
                case Key.MediaNextTrack:
                    player.Next(1);
                    break;
                case Key.B:
                case Key.MediaPreviousTrack:
                    player.Next(-1);
                    break;
                case Key.OemPlus:
                case Key.Add:
                    ChangeVol(5);
                    break;
                case Key.OemMinus:
                case Key.Subtract:
                    ChangeVol(-5);
                    break;
                case Key.M:
                    player.Muted = !player.Muted;
                    UpdateVolumeUi();
                    break;
                case Key.F:
                case Key.F11:
                    SetFullscreen(!fullscreen);
                    break;
                case Key.Escape:
                    if (!fullscreen) return;
                    SetFullscreen(false);
                    break;
                case Key.F5:
                    if (BrowsePage.Visibility == Visibility.Visible) LoadCurrent(true);
                    break;
                case Key.OemQuestion:
                    FocusSearch();
                    break;
                default:
                    return;
            }
            e.Handled = true;
        }
    }
}
