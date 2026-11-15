using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;

namespace MayThu
{
    public partial class App : Application
    {
        public const string ShowEventName = "MayThu.ShowWindow.v1";
        private static Mutex single;

        public static bool StartInTray { get; private set; }

        protected override void OnStartup(StartupEventArgs e)
        {
            for (int i = 0; i < e.Args.Length; i++)
            {
                if (e.Args[i] == "--tray") StartInTray = true;
                if (e.Args[i] == "--selftest" && i + 1 < e.Args.Length) Store.SelfTestDir = e.Args[i + 1];
            }
            if (Store.SelfTestDir != null) Directory.CreateDirectory(Store.SelfTestDir);

            // Chỉ chạy một Máy Thu: mở lần hai thì đưa cửa sổ đang chạy lên trước
            if (Store.SelfTestDir == null)
            {
                single = new Mutex(true, "MayThu.SingleInstance.v1", out bool created);
                if (!created)
                {
                    try
                    {
                        using (var ev = EventWaitHandle.OpenExisting(ShowEventName)) ev.Set();
                    }
                    catch
                    {
                    }
                    Shutdown();
                    return;
                }
            }

            DispatcherUnhandledException += OnUiError;
            AppDomain.CurrentDomain.UnhandledException += (s, ex) => Store.LogError(ex.ExceptionObject as Exception);
            TaskScheduler.UnobservedTaskException += (s, ex) =>
            {
                Store.LogError(ex.Exception);
                ex.SetObserved();
            };

            base.OnStartup(e);
            Store.Init();

            try
            {
                LibVLCSharp.Shared.Core.Initialize();
            }
            catch (Exception ex)
            {
                Store.LogError(ex);
                MessageBox.Show("Không khởi động được bộ phát (thiếu thư mục libvlc cạnh MayThu.exe).\n\n" + ex.Message,
                    "Máy Thu", MessageBoxButton.OK, MessageBoxImage.Error);
                Shutdown();
                return;
            }

            var w = new MainWindow();
            MainWindow = w;
            if (StartInTray && Store.SelfTestDir == null) w.StartHidden();
            else w.Show();
        }

        private void OnUiError(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            Store.LogError(e.Exception);
            e.Handled = true;
            if (MainWindow is MainWindow mw) mw.Toast("Có lỗi nhỏ, app vẫn chạy tiếp: " + e.Exception.Message);
        }

        protected override void OnExit(ExitEventArgs e)
        {
            try
            {
                Store.SaveNow();
            }
            catch
            {
            }
            base.OnExit(e);
        }
    }
}
