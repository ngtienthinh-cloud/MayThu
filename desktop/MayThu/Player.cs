using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;
using LibVLCSharp.Shared;

namespace MayThu
{
    public enum PState { Idle, Connecting, Playing, Stopped, Error }

    /// <summary>
    /// Bộ phát dùng VLC (libVLC): phát được HLS, MP3, AAC, RTMP… như VLC.
    /// Mọi lệnh nặng (dừng, mở luồng) chạy ở luồng phụ để giao diện không bị đứng.
    /// </summary>
    public sealed class Player
    {
        public const string BrowserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

        public readonly LibVLC Vlc;
        public readonly MediaPlayer Mp;
        private readonly Dispatcher ui;
        private readonly object gate = new object();
        private readonly DispatcherTimer watchdog;
        private int gen;
        private Media media;
        private int attempt;
        private DateTime startedAt;
        private DateTime playingSince;
        private int lastBuffer = -1;
        private int volume = 80;
        private bool muted;
        private double gain = 1.0;

        public Channel Current { get; private set; }
        public List<Channel> Queue { get; private set; } = new List<Channel>();
        public PState State { get; private set; } = PState.Idle;
        public string Status { get; private set; } = "";
        public string NowPlaying { get; private set; } = "";
        public bool HasVideo { get; private set; }

        /// <summary>Có thay đổi trạng thái (gọi trên luồng giao diện).</summary>
        public event Action Changed;

        /// <summary>Bắt đầu phát một kênh (để ghi vào "gần đây").</summary>
        public event Action<Channel> Started;

        public Player(Dispatcher dispatcher)
        {
            ui = dispatcher;
            Vlc = new LibVLC("--no-video-title-show", "--http-reconnect", "--network-caching=1500", "--no-snapshot-preview");
            Mp = new MediaPlayer(Vlc)
            {
                EnableHardwareDecoding = true,
                EnableKeyInput = false,
                EnableMouseInput = false,
            };
            Mp.Vout += (s, e) =>
            {
                int g = Volatile.Read(ref gen);
                int count = e.Count;
                Post(() =>
                {
                    if (g != gen) return;
                    HasVideo = count > 0;
                    Raise();
                });
            };
            Mp.Buffering += (s, e) =>
            {
                int g = Volatile.Read(ref gen);
                int pct = (int)e.Cache;
                if (pct == lastBuffer || (pct % 10 != 0 && pct != 100)) return;
                lastBuffer = pct;
                Post(() =>
                {
                    if (g != gen || State != PState.Connecting) return;
                    Status = "Đang tải dữ liệu " + pct + "%";
                    Raise();
                });
            };
            watchdog = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Background, (s, e) => Tick(), ui);
            watchdog.Start();
        }

        public bool IsActive => State == PState.Playing || State == PState.Connecting;

        public int Volume
        {
            get => volume;
            set
            {
                volume = Math.Max(0, Math.Min(100, value));
                ApplyVolume();
            }
        }

        public bool Muted
        {
            get => muted;
            set
            {
                muted = value;
                ApplyVolume();
            }
        }

        /// <summary>Hệ số âm lượng 0..1 dùng cho tăng dần (báo thức) và giảm dần (hẹn giờ tắt).</summary>
        public double Gain
        {
            get => gain;
            set
            {
                gain = Math.Max(0, Math.Min(1, value));
                ApplyVolume();
            }
        }

        public void ApplyVolume()
        {
            try
            {
                Mp.Mute = muted;
                Mp.Volume = (int)Math.Round(volume * gain);
            }
            catch
            {
            }
        }

        /// <summary>Vị trí kênh đang phát trong danh sách (0..1) cho kim trên thang dò sóng.</summary>
        public double DialPosition
        {
            get
            {
                if (Current == null || Queue.Count <= 1) return 0.5;
                int i = Queue.FindIndex(c => c.Id == Current.Id);
                return i < 0 ? 0.5 : i / (double)(Queue.Count - 1);
            }
        }

        public void Play(Channel ch, IList<Channel> queue = null)
        {
            if (ch == null) return;
            if (queue != null && queue.Count > 0) Queue = queue.ToList();
            else if (!Queue.Any(c => c.Id == ch.Id)) Queue = new List<Channel> { ch };
            Current = ch;
            attempt = 0;
            gain = 1.0;
            Start();
            Started?.Invoke(ch);
            if (ch.Kind == Kind.RADIO) Repo.CountClick(ch.Uuid);
        }

        public void Retry()
        {
            if (Current == null) return;
            attempt = 0;
            gain = 1.0;
            Start();
        }

        public void Toggle()
        {
            if (IsActive) Stop();
            else Retry();
        }

        public void Next(int step)
        {
            if (Current == null || Queue.Count == 0) return;
            int i = Queue.FindIndex(c => c.Id == Current.Id);
            i = i < 0 ? 0 : ((i + step) % Queue.Count + Queue.Count) % Queue.Count;
            Play(Queue[i]);
        }

        public void Stop(string status = "Đã dừng")
        {
            Interlocked.Increment(ref gen);
            State = Current == null ? PState.Idle : PState.Stopped;
            Status = status;
            HasVideo = false;
            NowPlaying = "";
            Raise();
            Task.Run(() =>
            {
                lock (gate)
                {
                    try
                    {
                        Mp.Stop();
                    }
                    catch
                    {
                    }
                }
            });
        }

        /// <summary>Dừng hẳn khi thoát app (chờ tối đa 1,5 giây).</summary>
        public void Shutdown()
        {
            Interlocked.Increment(ref gen);
            watchdog.Stop();
            var t = Task.Run(() =>
            {
                lock (gate)
                {
                    try
                    {
                        Mp.Stop();
                    }
                    catch
                    {
                    }
                }
            });
            t.Wait(1500);
        }

        private void Start()
        {
            var ch = Current;
            if (ch == null) return;
            int g = Interlocked.Increment(ref gen);
            State = PState.Connecting;
            Status = attempt == 0 ? "Đang kết nối…" : "Mất tín hiệu, đang thử lại (" + attempt + "/2)…";
            NowPlaying = "";
            HasVideo = false;
            startedAt = DateTime.Now;
            lastBuffer = -1;
            Raise();
            Task.Run(() =>
            {
                lock (gate)
                {
                    if (g != Volatile.Read(ref gen)) return;
                    try
                    {
                        Mp.Stop();
                        var old = media;
                        var m = new Media(Vlc, ch.Url, FromType.FromLocation);
                        m.AddOption(":http-user-agent=" + (string.IsNullOrWhiteSpace(ch.UserAgent) ? BrowserUa : ch.UserAgent));
                        if (!string.IsNullOrWhiteSpace(ch.Referrer)) m.AddOption(":http-referrer=" + ch.Referrer);
                        m.AddOption(":network-caching=" + (ch.Kind == Kind.RADIO ? "2500" : "1800"));
                        m.StateChanged += (s, e) =>
                        {
                            var st = e.State;
                            Post(() => OnState(g, st));
                        };
                        m.MetaChanged += (s, e) =>
                        {
                            if (e.MetadataType != MetadataType.NowPlaying) return;
                            Post(() =>
                            {
                                if (g != gen) return;
                                string np = null;
                                try
                                {
                                    np = m.Meta(MetadataType.NowPlaying);
                                }
                                catch
                                {
                                }
                                OnMeta(g, np);
                            });
                        };
                        media = m;
                        if (g != Volatile.Read(ref gen)) return;
                        Mp.Play(m);
                        try
                        {
                            old?.Dispose();
                        }
                        catch
                        {
                        }
                    }
                    catch (Exception e)
                    {
                        Store.LogError(e);
                        Post(() => OnState(g, VLCState.Error));
                    }
                }
            });
        }

        private void OnState(int g, VLCState st)
        {
            if (g != gen) return;
            switch (st)
            {
                case VLCState.Playing:
                    State = PState.Playing;
                    Status = "Đang phát";
                    playingSince = DateTime.Now;
                    ApplyVolume();
                    var again = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.2) };
                    again.Tick += (s, e) =>
                    {
                        again.Stop();
                        if (g == gen) ApplyVolume();
                    };
                    again.Start();
                    break;
                case VLCState.Ended:
                case VLCState.Error:
                    Fail();
                    return;
                default:
                    return;
            }
            Raise();
        }

        private void OnMeta(int g, string np)
        {
            if (g != gen || string.IsNullOrWhiteSpace(np)) return;
            np = np.Trim();
            if (np == NowPlaying) return;
            NowPlaying = np;
            Raise();
        }

        private void Fail()
        {
            if (State == PState.Playing && (DateTime.Now - playingSince).TotalSeconds > 30) attempt = 0;
            if (attempt < 2)
            {
                attempt++;
                startedAt = DateTime.Now;
                State = PState.Connecting;
                Status = "Mất tín hiệu, đang thử lại (" + attempt + "/2)…";
                HasVideo = false;
                Raise();
                int g = gen;
                var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
                t.Tick += (s, e) =>
                {
                    t.Stop();
                    if (g == gen) Start();
                };
                t.Start();
                return;
            }
            Interlocked.Increment(ref gen);
            State = PState.Error;
            Status = "Không phát được kênh này. Kênh có thể đã ngừng phát, đổi địa chỉ hoặc chặn vùng.";
            HasVideo = false;
            NowPlaying = "";
            Raise();
            Task.Run(() =>
            {
                lock (gate)
                {
                    try
                    {
                        Mp.Stop();
                    }
                    catch
                    {
                    }
                }
            });
        }

        private void Tick()
        {
            if (State == PState.Connecting && (DateTime.Now - startedAt).TotalSeconds > 25) Fail();
        }

        private void Post(Action a) => ui.BeginInvoke(a);

        private void Raise() => Changed?.Invoke();
    }
}
