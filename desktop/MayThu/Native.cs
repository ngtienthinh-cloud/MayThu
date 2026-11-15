using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;

namespace MayThu
{
    /// <summary>Các lệnh Windows: thanh tiêu đề tối, giữ máy không ngủ, hẹn giờ đánh thức máy, chạy cùng Windows.</summary>
    public static class Native
    {
        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        [DllImport("kernel32.dll")]
        private static extern uint SetThreadExecutionState(uint flags);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr CreateWaitableTimer(IntPtr attributes, bool manualReset, string name);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetWaitableTimer(IntPtr timer, ref long dueTime, int period, IntPtr completion, IntPtr arg, bool resume);

        [DllImport("kernel32.dll")]
        private static extern bool CancelWaitableTimer(IntPtr timer);

        private static IntPtr wakeTimer = IntPtr.Zero;

        /// <summary>Thanh tiêu đề màu tối cho hợp với app (Windows 10 2004 trở lên, Windows 11).</summary>
        public static void DarkTitle(Window w)
        {
            try
            {
                IntPtr h = new WindowInteropHelper(w).Handle;
                if (h == IntPtr.Zero) return;
                int on = 1;
                if (DwmSetWindowAttribute(h, 20, ref on, sizeof(int)) != 0) DwmSetWindowAttribute(h, 19, ref on, sizeof(int));
                int caption = 0x002A2013; // #13202A dạng 0x00BBGGRR
                DwmSetWindowAttribute(h, 35, ref caption, sizeof(int));
            }
            catch
            {
            }
        }

        /// <summary>Giữ máy không tự ngủ khi đang phát; giữ màn hình sáng khi xem hình.</summary>
        public static void KeepAwake(bool system, bool display)
        {
            try
            {
                uint f = 0x80000000; // ES_CONTINUOUS
                if (system) f |= 0x00000001; // ES_SYSTEM_REQUIRED
                if (display) f |= 0x00000002; // ES_DISPLAY_REQUIRED
                SetThreadExecutionState(f);
            }
            catch
            {
            }
        }

        /// <summary>Hẹn giờ đánh thức máy khỏi chế độ ngủ (Sleep) cho báo thức; null để hủy.</summary>
        public static void SetWake(DateTime? when)
        {
            try
            {
                if (wakeTimer == IntPtr.Zero) wakeTimer = CreateWaitableTimer(IntPtr.Zero, true, null);
                if (wakeTimer == IntPtr.Zero) return;
                if (when == null || when.Value <= DateTime.Now)
                {
                    CancelWaitableTimer(wakeTimer);
                    return;
                }
                long due = when.Value.ToFileTime();
                SetWaitableTimer(wakeTimer, ref due, 0, IntPtr.Zero, IntPtr.Zero, true);
            }
            catch
            {
            }
        }
    }

    /// <summary>Mở Máy Thu cùng Windows (chạy ngầm ở khay để báo thức).</summary>
    public static class AutoStart
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string Name = "MayThu";

        private static string Exe => Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "MayThu.exe");

        public static bool IsOn()
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(RunKey))
                {
                    string v = k?.GetValue(Name) as string;
                    return v != null && v.IndexOf(Exe, StringComparison.OrdinalIgnoreCase) >= 0;
                }
            }
            catch
            {
                return false;
            }
        }

        public static bool Set(bool on)
        {
            try
            {
                using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
                {
                    if (on) k.SetValue(Name, "\"" + Exe + "\" --tray");
                    else k.DeleteValue(Name, false);
                }
                return true;
            }
            catch
            {
                return false;
            }
        }
    }

    /// <summary>Tải và nhớ logo kênh (tải ngầm, không làm chậm danh sách).</summary>
    public static class LogoCache
    {
        private static readonly Dictionary<string, ImageSource> Map = new Dictionary<string, ImageSource>();

        public static ImageSource Get(string url, int px = 96)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            url = url.Trim();
            if (!url.StartsWith("http", StringComparison.OrdinalIgnoreCase)) return null;
            if (url.EndsWith(".svg", StringComparison.OrdinalIgnoreCase)) return null;
            string key = px + "|" + url;
            if (Map.TryGetValue(key, out var cached)) return cached;
            ImageSource img = null;
            try
            {
                var b = new BitmapImage();
                b.BeginInit();
                b.UriSource = new Uri(url, UriKind.Absolute);
                b.DecodePixelWidth = px;
                b.CacheOption = BitmapCacheOption.OnLoad;
                b.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
                b.EndInit();
                img = b;
            }
            catch
            {
                img = null;
            }
            if (Map.Count > 4000) Map.Clear();
            Map[key] = img;
            return img;
        }
    }

    /// <summary>Một dòng trong danh sách kênh.</summary>
    public class Row : INotifyPropertyChanged
    {
        private bool isFav;
        private bool isCurrent;

        public Row(Channel ch, bool fav, bool current)
        {
            Ch = ch;
            isFav = fav;
            isCurrent = current;
        }

        public Channel Ch { get; }
        public string Name => Ch.Name;
        public string Subtitle => Ch.Subtitle;
        public string Initials => Txt.Initials(Ch.Name);
        public ImageSource LogoImage => LogoCache.Get(Ch.Logo);

        public bool IsFav
        {
            get => isFav;
            set
            {
                if (isFav == value) return;
                isFav = value;
                On(nameof(IsFav));
                On(nameof(StarIcon));
            }
        }

        public bool IsCurrent
        {
            get => isCurrent;
            set
            {
                if (isCurrent == value) return;
                isCurrent = value;
                On(nameof(IsCurrent));
            }
        }

        public Geometry StarIcon => (Geometry)Application.Current.FindResource(isFav ? "IcStar" : "IcStarBorder");

        public event PropertyChangedEventHandler PropertyChanged;

        private void On(string n) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(n));
    }

    /// <summary>Một dòng trong mục Báo thức.</summary>
    public class AlarmRow
    {
        public AlarmItem A { get; set; }
        public string TimeText { get; set; }
        public string RepeatText { get; set; }
        public string ActionText { get; set; }
        public string NextText { get; set; }
        public bool Enabled { get; set; }
        public Geometry ActionIcon { get; set; }
        public Brush NextBrush { get; set; }
    }

    /// <summary>Hộp thoại nhập nhanh (thêm danh sách, thêm kênh).</summary>
    public static class Ask
    {
        public static string[] Show(Window owner, string title, string[] labels, string[] hints = null, string okText = "Thêm")
        {
            var res = Application.Current.Resources;
            var w = new Window
            {
                Title = title,
                Owner = owner,
                Width = 520,
                SizeToContent = SizeToContent.Height,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                ResizeMode = ResizeMode.NoResize,
                ShowInTaskbar = false,
                Background = (Brush)res["InkBrush"],
                Foreground = (Brush)res["TextBrush"],
                FontFamily = new FontFamily("Segoe UI"),
                FontSize = 14,
            };
            w.SourceInitialized += (s, e) => Native.DarkTitle(w);
            var root = new StackPanel { Margin = new Thickness(24, 20, 24, 20) };
            root.Children.Add(new TextBlock { Text = title, FontSize = 20, FontWeight = FontWeights.Bold });
            var boxes = new List<TextBox>();
            for (int i = 0; i < labels.Length; i++)
            {
                root.Children.Add(new TextBlock { Text = labels[i], Style = (Style)res["Label"] });
                var tb = new TextBox();
                boxes.Add(tb);
                root.Children.Add(tb);
                if (hints != null && i < hints.Length && !string.IsNullOrEmpty(hints[i]))
                {
                    root.Children.Add(new TextBlock
                    {
                        Text = hints[i],
                        Foreground = (Brush)res["MutedBrush"],
                        FontSize = 12.5,
                        TextWrapping = TextWrapping.Wrap,
                        Margin = new Thickness(2, 6, 0, 0),
                    });
                }
            }
            var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 22, 0, 0) };
            var cancel = new Button { Content = "Hủy", IsCancel = true };
            var ok = new Button { Content = okText, IsDefault = true, Margin = new Thickness(8, 0, 0, 0), Style = (Style)res["AccentBtn"] };
            ok.Click += (s, e) => w.DialogResult = true;
            row.Children.Add(cancel);
            row.Children.Add(ok);
            root.Children.Add(row);
            w.Content = root;
            w.Loaded += (s, e) => boxes[0].Focus();
            return w.ShowDialog() == true ? boxes.Select(b => b.Text.Trim()).ToArray() : null;
        }
    }
}
