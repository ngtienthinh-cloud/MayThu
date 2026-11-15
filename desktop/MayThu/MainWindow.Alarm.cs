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
    /// <summary>Phần báo thức, cài đặt và chạy thử tự động của cửa sổ chính.</summary>
    public partial class MainWindow
    {
        // ============================== BÁO THỨC ==============================

        private void OnClock()
        {
            CheckAlarms();
            var now = DateTime.Now;
            if (sleepAt != null)
            {
                if (now >= sleepAt.Value)
                {
                    sleepAt = null;
                    sleepIdx = 0;
                    if (player.IsActive) FadeOut(() => player.Stop("Đã dừng theo hẹn giờ"));
                    Toast("Đã dừng phát theo hẹn giờ");
                }
                UpdateSleepUi();
            }
            if (ringing != null)
            {
                if (rampOn && player.State == PState.Playing)
                {
                    if (rampT0 == null) rampT0 = now;
                    double k = (now - rampT0.Value).TotalSeconds / 45.0;
                    player.Gain = 0.15 + 0.85 * Math.Min(1.0, k);
                    if (k >= 1) rampOn = false;
                }
                if (!usingFallback && player.State != PState.Playing &&
                    ((now - ringStart).TotalSeconds > 30 || player.State == PState.Error || player.Current == null))
                    StartFallback();
                if (usingFallback && fallbackSound == null && (now - lastBeep).TotalSeconds >= 2)
                {
                    lastBeep = now;
                    SystemSounds.Exclamation.Play();
                }
            }
            if (autoStopAt != null && now >= autoStopAt.Value)
            {
                autoStopAt = null;
                if (ringing != null || usingFallback)
                {
                    StopRinging(true);
                }
                else if (player.IsActive)
                {
                    FadeOut(() => player.Stop("Đã tự tắt theo cài đặt báo thức"));
                }
            }
            if (fullscreen && FsHint.Visibility == Visibility.Visible && now > fsHintUntil) FsHint.Visibility = Visibility.Collapsed;
            if (now.Minute != lastMinute)
            {
                lastMinute = now.Minute;
                RefreshNextAlarm();
                if (page == "alarm") RenderAlarms();
            }
        }

        private void CheckAlarms()
        {
            var now = DateTime.Now;
            bool changed = false;
            foreach (var kv in due.ToList())
            {
                if (now < kv.Value) continue;
                changed = true;
                var a = Store.S.Alarms.FirstOrDefault(x => x.Id == kv.Key);
                if (a == null) continue;
                if (a.Days == 0)
                {
                    a.Enabled = false;
                    Store.SaveSoon();
                }
                // Máy ngủ quá 15 phút sau giờ hẹn thì bỏ qua lần này
                if ((now - kv.Value).TotalMinutes <= 15) Fire(a, false);
            }
            if (snoozeAt != null && now >= snoozeAt.Value)
            {
                var a = snoozeItem;
                snoozeAt = null;
                snoozeItem = null;
                changed = true;
                if (a != null) Fire(a, false);
            }
            if (changed) RecomputeAlarms();
        }

        private void RecomputeAlarms()
        {
            due.Clear();
            var now = DateTime.Now;
            foreach (var a in Store.S.Alarms)
            {
                if (!a.Enabled) continue;
                var t = a.NextTrigger(now);
                if (t != null) due[a.Id] = t.Value;
            }
            // Hẹn giờ đánh thức máy khỏi chế độ ngủ trước giờ báo thức một chút
            DateTime? wake = null;
            foreach (var kv in due)
            {
                var a = Store.S.Alarms.FirstOrDefault(x => x.Id == kv.Key);
                if (a != null && a.Action == AlarmAction.PLAY && (wake == null || kv.Value < wake.Value)) wake = kv.Value;
            }
            if (snoozeAt != null && (wake == null || snoozeAt.Value < wake.Value)) wake = snoozeAt;
            Native.SetWake(wake?.AddSeconds(-45));
            RefreshNextAlarm();
            if (page == "alarm") RenderAlarms();
        }

        private void RefreshNextAlarm()
        {
            var now = DateTime.Now;
            AlarmItem best = null;
            DateTime bestT = DateTime.MaxValue;
            foreach (var kv in due)
            {
                var a = Store.S.Alarms.FirstOrDefault(x => x.Id == kv.Key);
                if (a == null || kv.Value >= bestT) continue;
                best = a;
                bestT = kv.Value;
            }
            if (best == null)
            {
                NextAlarmBox.Visibility = Visibility.Collapsed;
                return;
            }
            NextAlarmBox.Visibility = Visibility.Visible;
            NextAlarmTime.Text = best.TimeText;
            string what = best.Action == AlarmAction.PLAY ? "Bật " + (best.Channel?.Name ?? "kênh") : "Tắt app";
            string day = AlarmTime.WhenText(bestT, now).Substring(6);
            if (day.Length > 0) day = char.ToUpper(day[0]) + day.Substring(1);
            NextAlarmText.Text = day + ". " + what + ". " + AlarmTime.UntilText(bestT, now) + ".";
        }

        private void RenderAlarms()
        {
            var now = DateTime.Now;
            var amber = Res("AmberBrush");
            var muted = Res("MutedBrush");
            var list = new List<AlarmRow>();
            foreach (var a in Store.S.Alarms.OrderBy(x => x.Hour * 60 + x.Minute))
            {
                DateTime? t = a.Enabled ? a.NextTrigger(now) : null;
                string next = t != null
                    ? AlarmTime.WhenText(t.Value, now) + ". " + AlarmTime.UntilText(t.Value, now)
                    : (a.Enabled ? "Thời điểm đã qua" : "Đang tắt");
                if (!string.IsNullOrWhiteSpace(a.Label)) next += ". " + a.Label;
                list.Add(new AlarmRow
                {
                    A = a,
                    TimeText = a.TimeText,
                    RepeatText = a.RepeatText(),
                    ActionText = a.Action == AlarmAction.PLAY ? "Bật " + (a.Channel?.Name ?? "(chưa chọn kênh)") : "Dừng phát và tắt app",
                    ActionIcon = (Geometry)FindResource(a.Action == AlarmAction.PLAY ? "IcRadio" : "IcPower"),
                    Enabled = a.Enabled,
                    NextText = next,
                    NextBrush = t != null ? amber : muted,
                });
            }
            AlarmList.ItemsSource = list;
            AlarmEmpty.Visibility = list.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private AlarmItem NewAlarm(AlarmAction act)
        {
            int id = Store.S.Alarms.Count == 0 ? 1 : Store.S.Alarms.Max(x => x.Id) + 1;
            if (act == AlarmAction.STOP) return new AlarmItem { Id = id, Action = act, Hour = 23, Minute = 0, Days = AlarmItem.ALL };
            var ch = player.Current
                     ?? Store.S.Favorites.FirstOrDefault(c => c.Kind == Kind.RADIO)
                     ?? Store.S.Recent.FirstOrDefault(c => c.Kind == Kind.RADIO)
                     ?? Store.S.Favorites.FirstOrDefault()
                     ?? Store.S.Recent.FirstOrDefault()
                     ?? AlarmChoices().FirstOrDefault();
            return new AlarmItem { Id = id, Action = act, Hour = 6, Minute = 0, Days = AlarmItem.ALL, Channel = ch };
        }

        private List<Channel> AlarmChoices()
        {
            var list = new List<Channel>();
            void Add(IEnumerable<Channel> src)
            {
                if (src == null) return;
                foreach (var c in src) if (c != null && !list.Any(x => x.Id == c.Id)) list.Add(c);
            }
            if (player.Current != null) Add(new[] { player.Current });
            Add(Store.S.Favorites);
            Add(Store.S.Recent.Take(20));
            Add(Store.S.Custom);
            Add(Repo.Cached("radio:vn")?.Take(80));
            return list;
        }

        private void EditAlarm(AlarmItem a, bool exists)
        {
            var w = new AlarmWindow(a, exists, AlarmChoices(), x => Fire(x, true)) { Owner = this };
            if (w.ShowDialog() != true) return;
            if (w.Deleted)
            {
                DeleteAlarm(a);
                return;
            }
            if (w.Result != null) SaveAlarm(w.Result);
        }

        private void SaveAlarm(AlarmItem a)
        {
            int i = Store.S.Alarms.FindIndex(x => x.Id == a.Id);
            if (i >= 0) Store.S.Alarms[i] = a;
            else Store.S.Alarms.Add(a);
            Store.SaveSoon();
            RecomputeAlarms();
            var now = DateTime.Now;
            var t = a.NextTrigger(now);
            if (a.Enabled && t != null)
                Toast("Đã đặt " + (a.Action == AlarmAction.PLAY ? "báo thức" : "hẹn giờ tắt") + " lúc " + AlarmTime.WhenText(t.Value, now) + ". " + AlarmTime.UntilText(t.Value, now));
        }

        private void DeleteAlarm(AlarmItem a)
        {
            Store.S.Alarms.RemoveAll(x => x.Id == a.Id);
            Store.SaveSoon();
            RecomputeAlarms();
            Toast("Đã xóa hẹn giờ " + a.TimeText);
        }

        private void AddAlarm_Click(object sender, RoutedEventArgs e) => EditAlarm(NewAlarm(AlarmAction.PLAY), false);

        private void AddStop_Click(object sender, RoutedEventArgs e) => EditAlarm(NewAlarm(AlarmAction.STOP), false);

        private void AlarmRow_Click(object sender, MouseButtonEventArgs e)
        {
            if ((sender as FrameworkElement)?.DataContext is AlarmRow r) EditAlarm(r.A, true);
        }

        private void AlarmEdit_Click(object sender, RoutedEventArgs e)
        {
            if ((sender as FrameworkElement)?.DataContext is AlarmRow r) EditAlarm(r.A, true);
        }

        private void AlarmDelete_Click(object sender, RoutedEventArgs e)
        {
            if (!((sender as FrameworkElement)?.DataContext is AlarmRow r)) return;
            if (MessageBox.Show(this, "Xóa hẹn giờ " + r.A.TimeText + "?", "Máy Thu", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            DeleteAlarm(r.A);
        }

        private void AlarmToggle_Click(object sender, RoutedEventArgs e)
        {
            if (!((sender as FrameworkElement)?.DataContext is AlarmRow r)) return;
            var a = r.A;
            bool on = !a.Enabled;
            if (on && a.NextTrigger(DateTime.Now) == null)
            {
                Toast("Thời điểm này đã qua, hãy sửa lại giờ hoặc ngày");
                RenderAlarms();
                return;
            }
            a.Enabled = on;
            Store.SaveSoon();
            RecomputeAlarms();
            if (on)
            {
                var t = a.NextTrigger(DateTime.Now);
                if (t != null) Toast("Đã bật hẹn giờ " + a.TimeText + ". " + AlarmTime.UntilText(t.Value, DateTime.Now));
            }
        }

        /// <summary>Đến giờ: bật kênh (báo thức) hoặc tắt app.</summary>
        private void Fire(AlarmItem a, bool test)
        {
            if (a.Action == AlarmAction.STOP)
            {
                if (test) return;
                Toast("Đến giờ hẹn tắt " + a.TimeText);
                if (player.IsActive) FadeOut(() =>
                {
                    player.Stop("Đã tắt theo hẹn giờ");
                    AfterStopTimer();
                });
                else AfterStopTimer();
                return;
            }
            StopRinging(false);
            ringing = a;
            ringStart = DateTime.Now;
            usingFallback = false;
            rampT0 = null;
            int autoMin = test ? Math.Min(a.AutoStopMin > 0 ? a.AutoStopMin : 5, 5) : a.AutoStopMin;
            autoStopAt = autoMin > 0 ? DateTime.Now.AddMinutes(autoMin) : (DateTime?)null;
            if (fullscreen) SetFullscreen(false);
            ShowFromTray();
            BringToFront();
            player.Muted = false;
            VolSlider.Value = Math.Max(10, Math.Min(100, a.Volume));
            UpdateVolumeUi();
            RingTitle.Text = (test ? "Phát thử báo thức " : "Báo thức ") + a.TimeText;
            RingSub.Text = (string.IsNullOrWhiteSpace(a.Label) ? "" : a.Label + ". ") + (a.Channel != null ? "Đang bật " + a.Channel.Name : "");
            RingKeepBtn.Visibility = Visibility.Visible;
            RingBanner.Visibility = Visibility.Visible;
            if (a.Channel != null)
            {
                player.Play(a.Channel, shown.Any(c => c.Id == a.Channel.Id) ? shown : null);
                rampOn = a.Ramp;
                if (rampOn) player.Gain = 0.15;
            }
            else
            {
                StartFallback();
            }
            OnPlayerChanged();
        }

        private void AfterStopTimer()
        {
            if (Store.S.Alarms.Any(x => x.Enabled) && tray != null) HideToTray();
            else ExitApp();
        }

        private void StartFallback()
        {
            if (usingFallback) return;
            usingFallback = true;
            rampOn = false;
            player.Stop("Không phát được kênh");
            try
            {
                string media = IOPath.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "Media");
                foreach (var f in new[] { "Alarm01.wav", "Alarm02.wav", "Ring01.wav", "Windows Notify.wav" })
                {
                    string p = IOPath.Combine(media, f);
                    if (!File.Exists(p)) continue;
                    fallbackSound = new SoundPlayer(p);
                    fallbackSound.PlayLooping();
                    break;
                }
            }
            catch
            {
                fallbackSound = null;
            }
            RingSub.Text = "Không phát được kênh, đang dùng chuông báo thức của máy";
            RingKeepBtn.Visibility = Visibility.Collapsed;
            OnPlayerChanged();
        }

        private void StopFallback()
        {
            usingFallback = false;
            try
            {
                fallbackSound?.Stop();
            }
            catch
            {
            }
            fallbackSound = null;
        }

        private void StopRinging(bool stopPlayback)
        {
            bool was = ringing != null || usingFallback;
            ringing = null;
            rampOn = false;
            rampT0 = null;
            StopFallback();
            RingBanner.Visibility = Visibility.Collapsed;
            if (!was) return;
            if (stopPlayback) player.Stop();
            else player.Gain = 1;
            OnPlayerChanged();
        }

        private void RingStop_Click(object sender, RoutedEventArgs e)
        {
            autoStopAt = null;
            StopRinging(true);
        }

        private void RingSnooze_Click(object sender, RoutedEventArgs e)
        {
            var a = ringing;
            autoStopAt = null;
            StopRinging(true);
            if (a == null) return;
            snoozeItem = a;
            snoozeAt = DateTime.Now.AddMinutes(10);
            RecomputeAlarms();
            Toast("Sẽ báo lại lúc " + snoozeAt.Value.ToString("HH:mm"));
        }

        private void RingKeep_Click(object sender, RoutedEventArgs e)
        {
            autoStopAt = null;
            ringing = null;
            rampOn = false;
            player.Gain = 1;
            RingBanner.Visibility = Visibility.Collapsed;
            OnPlayerChanged();
        }

        // ============================== CÀI ĐẶT ==============================

        private void BuildSettingsStatic()
        {
            OfficialPanel.Children.Clear();
            foreach (var o in Catalog.Official)
            {
                var sp = new StackPanel();
                sp.Children.Add(new TextBlock { Text = o.Title, FontWeight = FontWeights.SemiBold });
                sp.Children.Add(new TextBlock { Text = o.Note, Foreground = Res("MutedBrush"), FontSize = 12.5, FontWeight = FontWeights.Normal, TextWrapping = TextWrapping.Wrap });
                var b = new Button
                {
                    Content = sp,
                    Height = double.NaN,
                    Padding = new Thickness(14, 9, 14, 9),
                    Margin = new Thickness(0, 0, 0, 8),
                    HorizontalContentAlignment = HorizontalAlignment.Left,
                    ToolTip = o.Url,
                };
                string url = o.Url;
                b.Click += (s, e) => OpenUrl(url);
                OfficialPanel.Children.Add(b);
            }

            var keys = new[]
            {
                ("Space", "Phát hoặc dừng"),
                ("N và B", "Kênh sau, kênh trước"),
                ("+ và -", "Tăng, giảm âm lượng"),
                ("M", "Tắt hoặc bật tiếng"),
                ("F", "Toàn màn hình, Esc để thoát"),
                ("Ctrl + F", "Tìm kênh"),
                ("F5", "Tải lại danh sách"),
                ("Enter", "Phát kênh đang chọn"),
            };
            for (int i = 0; i < keys.Length; i++)
            {
                KeysGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                var k = new TextBlock { Text = keys[i].Item1, FontWeight = FontWeights.SemiBold, Foreground = Res("AmberBrush"), Margin = new Thickness(0, 3, 0, 3) };
                var d = new TextBlock { Text = keys[i].Item2, Foreground = Res("MutedBrush"), Margin = new Thickness(0, 3, 0, 3) };
                Grid.SetRow(k, i);
                Grid.SetRow(d, i);
                Grid.SetColumn(d, 1);
                KeysGrid.Children.Add(k);
                KeysGrid.Children.Add(d);
            }

            var ver = Assembly.GetEntryAssembly()?.GetName().Version;
            if (ver != null) AboutTitle.Text = "Máy Thu cho Windows, phiên bản " + ver.Major + "." + ver.Minor + "." + ver.Build;
        }

        private void RenderSettings()
        {
            OptStartup.IsChecked = AutoStart.IsOn();
            OptTray.IsChecked = Store.S.CloseToTray;
            bool portable = Store.Dir.StartsWith(AppContext.BaseDirectory, StringComparison.OrdinalIgnoreCase);
            DataPathText.Text = "Kênh yêu thích, báo thức và danh sách đã tải được lưu tại " + Store.Dir + "." +
                                (portable ? " Chép cả thư mục Máy Thu sang máy khác là mang theo được." : "");
        }

        private void OptStartup_Click(object sender, RoutedEventArgs e)
        {
            bool on = OptStartup.IsChecked == true;
            if (!AutoStart.Set(on)) Toast("Không đổi được cài đặt khởi động cùng Windows");
            else Toast(on ? "Máy Thu sẽ chạy ngầm mỗi khi mở máy" : "Đã tắt chạy cùng Windows");
            RenderSettings();
        }

        private void OptTray_Click(object sender, RoutedEventArgs e)
        {
            Store.S.CloseToTray = OptTray.IsChecked == true;
            Store.SaveSoon();
        }

        private void OpenData_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                Process.Start(new ProcessStartInfo("explorer.exe", "\"" + Store.Dir + "\"") { UseShellExecute = true });
            }
            catch
            {
            }
        }

        private void ClearCache_Click(object sender, RoutedEventArgs e)
        {
            Repo.ClearAll();
            Toast("Đã xóa. Danh sách kênh sẽ được tải lại khi bạn mở.");
        }

        // ============================== CHẠY THỬ TỰ ĐỘNG (khi build) ==============================

        private async void RunSelfTest()
        {
            string dir = Store.SelfTestDir;
            var log = new StringBuilder();
            void L(string s)
            {
                log.AppendLine(DateTime.Now.ToString("HH:mm:ss") + "  " + s);
                try
                {
                    File.WriteAllText(IOPath.Combine(dir, "selftest.txt"), log.ToString());
                }
                catch
                {
                }
            }
            bool ok = true;
            try
            {
                L("Bắt đầu. libVLC " + player.Vlc.Version + ". Dữ liệu tại " + Store.Dir);
                await Task.Delay(12000);
                L("TV: " + rows.Count + " kênh. " + PageSub.Text);
                Snap(this, "01-tv.png");
                if (rows.Count == 0) L("CẢNH BÁO: danh sách TV trống");

                SelectNav("radio");
                await Task.Delay(10000);
                L("Radio: " + rows.Count + " đài. " + PageSub.Text);
                Snap(this, "02-radio.png");
                if (rows.Count > 0)
                {
                    ToggleFav(rows[0].Ch);
                    PlayRow(rows[0]);
                    await Task.Delay(10000);
                    L("Phát " + player.Current?.Name + ": " + player.State + ". " + player.Status + ". Bài: " + player.NowPlaying);
                    Snap(this, "03-radio-playing.png");
                }
                SearchBox.Text = "vov";
                await Task.Delay(900);
                L("Tìm vov: " + rows.Count);
                Snap(this, "04-search.png");
                SearchBox.Text = "";

                SelectNav("tv");
                await Task.Delay(3000);
                if (rows.Count > 0)
                {
                    PlayRow(rows[0]);
                    await Task.Delay(12000);
                    L("Phát TV " + player.Current?.Name + ": " + player.State + ", có hình: " + player.HasVideo + ". " + player.Status);
                    Snap(this, "05-tv-playing.png");
                }
                player.Stop();
                await Task.Delay(800);

                SelectNav("fav");
                await Task.Delay(800);
                Snap(this, "06-fav.png");

                SelectNav("alarm");
                await Task.Delay(500);
                var a = NewAlarm(AlarmAction.PLAY);
                SaveAlarm(a);
                SaveAlarm(NewAlarm(AlarmAction.STOP));
                await Task.Delay(800);
                Snap(this, "07-alarm.png");
                var w = new AlarmWindow(a, true, AlarmChoices(), null) { Owner = this };
                w.Show();
                await Task.Delay(1500);
                Snap(w, "08-alarm-editor.png");
                w.Close();

                Fire(a.Clone(), true);
                await Task.Delay(5000);
                L("Báo thức thử: " + player.State + ", chuông dự phòng: " + usingFallback);
                Snap(this, "09-ringing.png");
                StopRinging(true);

                SelectNav("mine");
                await Task.Delay(800);
                Snap(this, "10-mine.png");
                SelectNav("settings");
                await Task.Delay(800);
                Snap(this, "11-settings.png");
                SetFullscreen(true);
                await Task.Delay(1200);
                Snap(this, "12-fullscreen.png");
                SetFullscreen(false);
                await Task.Delay(500);
            }
            catch (Exception ex)
            {
                ok = false;
                L("LỖI: " + ex);
            }
            if (File.Exists(IOPath.Combine(dir, "errors.txt"))) L("Có lỗi được ghi trong errors.txt");
            L(ok ? "RESULT OK" : "RESULT FAIL");
            reallyExit = true;
            Close();
        }

        private static void Snap(Window w, string name)
        {
            try
            {
                if (!(w.Content is FrameworkElement root)) return;
                root.UpdateLayout();
                int width = (int)Math.Ceiling(root.ActualWidth);
                int height = (int)Math.Ceiling(root.ActualHeight);
                if (width < 10 || height < 10) return;
                var rtb = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
                rtb.Render(root);
                var enc = new PngBitmapEncoder();
                enc.Frames.Add(BitmapFrame.Create(rtb));
                using (var fs = File.Create(IOPath.Combine(Store.SelfTestDir, name))) enc.Save(fs);
            }
            catch (Exception ex)
            {
                Store.LogError(ex);
            }
        }
    }
}
