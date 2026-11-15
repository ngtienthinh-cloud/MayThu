using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace MayThu
{
    /// <summary>Hộp thoại đặt báo thức hoặc hẹn giờ tắt.</summary>
    public partial class AlarmWindow : Window
    {
        private readonly AlarmItem src;
        private readonly Action<AlarmItem> test;
        private readonly List<CheckBox> dayChecks = new List<CheckBox>();
        private bool ready;

        public AlarmItem Result { get; private set; }
        public bool Deleted { get; private set; }

        public AlarmWindow(AlarmItem item, bool exists, List<Channel> choices, Action<AlarmItem> test)
        {
            InitializeComponent();
            src = item.Clone();
            this.test = test;
            SourceInitialized += (s, e) => Native.DarkTitle(this);

            for (int h = 0; h < 24; h++) HourBox.Items.Add(h.ToString("00"));
            for (int m = 0; m < 60; m++) MinBox.Items.Add(m.ToString("00"));
            for (int d = 1; d <= 31; d++) DayBox.Items.Add(d.ToString("00"));
            for (int mo = 1; mo <= 12; mo++) MonthBox.Items.Add(mo.ToString("00"));
            int y0 = DateTime.Now.Year;
            for (int y = y0; y <= y0 + 5; y++) YearBox.Items.Add(y.ToString());

            for (int i = 0; i < 7; i++)
            {
                var cb = new CheckBox { Content = AlarmTime.Short[i], Style = (Style)FindResource("Chip"), Tag = i, MinWidth = 52 };
                cb.Click += (s, e) => UpdateUi();
                dayChecks.Add(cb);
                DaysPanel.Children.Add(cb);
            }

            foreach (var (min, text) in new[] { (15, "15 phút"), (30, "30 phút"), (60, "1 giờ"), (120, "2 giờ"), (0, "Không tự tắt") })
                AutoBox.Items.Add(new ComboBoxItem { Content = text, Tag = min });

            var list = new List<Channel>();
            if (src.Channel != null) list.Add(src.Channel);
            foreach (var c in choices) if (!list.Any(x => x.Id == c.Id)) list.Add(c);
            foreach (var c in list)
            {
                string kind = c.Kind == Kind.TV ? "TV" : "Radio";
                ChannelBox.Items.Add(new ComboBoxItem { Content = c.Name + "   (" + kind + ")", Tag = c });
            }

            // Đổ dữ liệu
            Heading.Text = exists ? "Sửa hẹn giờ" : (src.Action == AlarmAction.PLAY ? "Báo thức mới" : "Hẹn giờ tắt mới");
            Title = Heading.Text;
            ActPlay.IsChecked = src.Action == AlarmAction.PLAY;
            ActStop.IsChecked = src.Action == AlarmAction.STOP;
            HourBox.SelectedIndex = Math.Max(0, Math.Min(23, src.Hour));
            MinBox.SelectedIndex = Math.Max(0, Math.Min(59, src.Minute));

            DateTime date;
            if (!AlarmTime.TryParseDate(src.Date, out date))
            {
                date = DateTime.Now.Date.AddHours(src.Hour).AddMinutes(src.Minute);
                if (date <= DateTime.Now) date = date.AddDays(1);
                date = date.Date;
            }
            DayBox.SelectedIndex = date.Day - 1;
            MonthBox.SelectedIndex = date.Month - 1;
            YearBox.SelectedIndex = Math.Max(0, Math.Min(YearBox.Items.Count - 1, date.Year - y0));

            if (!string.IsNullOrWhiteSpace(src.Date)) RepDate.IsChecked = true;
            else if (src.Days == 0) RepOnce.IsChecked = true;
            else if (src.Days == AlarmItem.ALL) RepDaily.IsChecked = true;
            else if (src.Days == AlarmItem.WEEKDAYS) RepWork.IsChecked = true;
            else if (src.Days == AlarmItem.WEEKEND) RepWeekend.IsChecked = true;
            else RepCustom.IsChecked = true;
            for (int i = 0; i < 7; i++) dayChecks[i].IsChecked = (src.Days & (1 << i)) != 0;

            ChannelBox.SelectedIndex = list.Count > 0 ? 0 : -1;
            VolSlider.Value = Math.Max(10, Math.Min(100, src.Volume));
            VolText.Text = (int)VolSlider.Value + "%";
            RampBox.IsChecked = src.Ramp;
            int ai = 0;
            for (int i = 0; i < AutoBox.Items.Count; i++)
                if ((int)((ComboBoxItem)AutoBox.Items[i]).Tag == src.AutoStopMin) ai = i;
            AutoBox.SelectedIndex = ai;
            LabelBox.Text = src.Label ?? "";
            DeleteBtn.Visibility = exists ? Visibility.Visible : Visibility.Collapsed;
            if (list.Count == 0) ChannelHint.Text = "Chưa có kênh nào để chọn. Hãy phát thử một đài hoặc thêm vào Yêu thích trước, rồi mở lại hộp này.";

            ready = true;
            UpdateUi();
        }

        private void Any_Changed(object sender, RoutedEventArgs e) => UpdateUi();

        private void Vol_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (VolText != null) VolText.Text = (int)e.NewValue + "%";
        }

        private AlarmItem Read()
        {
            var a = src.Clone();
            a.Action = ActStop.IsChecked == true ? AlarmAction.STOP : AlarmAction.PLAY;
            a.Hour = Math.Max(0, HourBox.SelectedIndex);
            a.Minute = Math.Max(0, MinBox.SelectedIndex);
            a.Date = "";
            if (RepDaily.IsChecked == true) a.Days = AlarmItem.ALL;
            else if (RepWork.IsChecked == true) a.Days = AlarmItem.WEEKDAYS;
            else if (RepWeekend.IsChecked == true) a.Days = AlarmItem.WEEKEND;
            else if (RepCustom.IsChecked == true)
            {
                int d = 0;
                for (int i = 0; i < 7; i++) if (dayChecks[i].IsChecked == true) d |= 1 << i;
                a.Days = d;
            }
            else if (RepDate.IsChecked == true)
            {
                a.Days = 0;
                int y = DateTime.Now.Year + Math.Max(0, YearBox.SelectedIndex);
                int mo = Math.Max(0, MonthBox.SelectedIndex) + 1;
                int day = Math.Min(Math.Max(0, DayBox.SelectedIndex) + 1, DateTime.DaysInMonth(y, mo));
                a.Date = AlarmTime.FormatDate(new DateTime(y, mo, day));
            }
            else a.Days = 0;
            a.Channel = (ChannelBox.SelectedItem as ComboBoxItem)?.Tag as Channel;
            a.Volume = (int)VolSlider.Value;
            a.Ramp = RampBox.IsChecked == true;
            a.AutoStopMin = AutoBox.SelectedItem is ComboBoxItem ci && ci.Tag is int mins ? mins : 60;
            a.Label = (LabelBox.Text ?? "").Trim();
            return a;
        }

        private void UpdateUi()
        {
            if (!ready) return;
            bool play = ActStop.IsChecked != true;
            PlayPanel.Visibility = play ? Visibility.Visible : Visibility.Collapsed;
            TestBtn.Visibility = play ? Visibility.Visible : Visibility.Collapsed;
            DaysPanel.Visibility = RepCustom.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            DatePanel.Visibility = RepDate.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            var a = Read();
            var now = DateTime.Now;
            var t = a.NextTrigger(now);
            if (t == null)
            {
                Summary.Text = "Thời điểm này đã qua, hãy chọn giờ hoặc ngày khác.";
                Summary.Foreground = (Brush)FindResource("RedBrush");
            }
            else
            {
                Summary.Text = (play ? "Sẽ bật kênh lúc " : "Sẽ tắt app lúc ") + AlarmTime.WhenText(t.Value, now) + ". " + AlarmTime.UntilText(t.Value, now) + ".";
                Summary.Foreground = (Brush)FindResource("AmberBrush");
            }
        }

        private void Save_Click(object sender, RoutedEventArgs e)
        {
            var a = Read();
            if (a.Action == AlarmAction.PLAY && a.Channel == null)
            {
                Summary.Text = "Hãy chọn kênh sẽ phát khi báo thức.";
                Summary.Foreground = (Brush)FindResource("RedBrush");
                return;
            }
            if (a.NextTrigger(DateTime.Now) == null)
            {
                Summary.Text = "Thời điểm này đã qua, hãy chọn giờ hoặc ngày khác.";
                Summary.Foreground = (Brush)FindResource("RedBrush");
                return;
            }
            a.Enabled = true;
            Result = a;
            DialogResult = true;
        }

        private void Delete_Click(object sender, RoutedEventArgs e)
        {
            Deleted = true;
            DialogResult = true;
        }

        private void Test_Click(object sender, RoutedEventArgs e)
        {
            var a = Read();
            if (a.Channel == null)
            {
                Summary.Text = "Hãy chọn kênh trước khi phát thử.";
                return;
            }
            test?.Invoke(a);
        }
    }
}
