package vn.maythu.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import vn.maythu.app.MainActivity
import vn.maythu.app.R
import vn.maythu.app.data.AlarmAction
import vn.maythu.app.data.AlarmItem
import vn.maythu.app.data.Repo
import vn.maythu.app.player.Playback
import vn.maythu.app.player.Status

/**
 * Dịch vụ báo thức: chạy nền ở chế độ ưu tiên cao, phát kênh đã chọn với âm lượng tăng dần,
 * hiện thông báo báo thức (có nút Tắt, Báo lại), mở màn hình báo thức trên màn hình khóa.
 * Không phát được kênh trong 30 giây thì chuyển sang chuông báo thức của máy.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_START = "vn.maythu.app.alarm.START"
        const val ACTION_STOP = "vn.maythu.app.alarm.STOP"
        const val ACTION_SNOOZE = "vn.maythu.app.alarm.SNOOZE"
        const val EXTRA_ALARM = "alarm_json"
        const val SNOOZE_MIN = 10
        private const val CHANNEL_ID = "maythu_alarm"
        private const val NOTIF_ID = 4242

        @Volatile
        var instance: AlarmService? = null
            private set

        fun start(ctx: Context, a: AlarmItem) {
            val i = Intent(ctx, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ALARM, a.toJson().toString())
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Nút "Tắt báo thức" trên màn hình. */
        fun stopFromUi() {
            val s = instance
            if (s != null) {
                s.stopAlarm(true)
            } else {
                if (Playback.isReady) Playback.close()
                AlarmState.ringing = null
            }
        }

        /** Nút "Báo lại" trên màn hình. */
        fun snoozeFromUi(ctx: Context) {
            val a = AlarmState.ringing
            if (a != null) AlarmScheduler.snooze(ctx, a, SNOOZE_MIN)
            stopFromUi()
        }

        /** Nút "Nghe tiếp": tắt báo thức nhưng giữ kênh đang phát. */
        fun keepFromUi() {
            val s = instance
            if (s != null) s.stopAlarm(false) else AlarmState.ringing = null
        }

        /** Hẹn giờ tắt: dừng mọi thứ và đóng app. */
        fun turnOffEverything() {
            instance?.stopAlarm(true)
            if (Playback.isReady) Playback.fadeOutAndClose()
            AlarmState.exitTick++
        }
    }

    private val scope = MainScope()
    private var current: AlarmItem? = null
    private var watchdog: Job? = null
    private var autoStop: Job? = null
    private var toneJob: Job? = null
    private var fallback: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var savedVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Repo.init(applicationContext)
        Playback.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val a = intent.getStringExtra(EXTRA_ALARM)?.let { s ->
                    try {
                        AlarmItem.fromJson(JSONObject(s))
                    } catch (e: Exception) {
                        null
                    }
                }
                goForeground(a)
                if (a == null) stopAlarm(true) else startAlarm(a)
            }
            ACTION_STOP -> stopAlarm(true)
            ACTION_SNOOZE -> {
                val a = current
                if (a != null) AlarmScheduler.snooze(this, a, SNOOZE_MIN)
                stopAlarm(true)
            }
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(a: AlarmItem?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            val c = NotificationChannel(CHANNEL_ID, "Báo thức", NotificationManager.IMPORTANCE_HIGH)
            c.description = "Báo thức bằng radio và hẹn giờ của Máy Thu"
            c.setSound(null, null)
            c.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(c)
        }
        val pf = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ALARM, true)
        val open = PendingIntent.getActivity(this, 41, openIntent, pf)
        val stop = PendingIntent.getService(this, 42, Intent(this, AlarmService::class.java).setAction(ACTION_STOP), pf)
        val snooze = PendingIntent.getService(this, 43, Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE), pf)
        val chName = a?.channel?.name ?: "Máy Thu"
        val label = a?.label.orEmpty()
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Báo thức " + (a?.timeText ?: ""))
            .setContentText(if (label.isBlank()) "Đang phát $chName" else "$label, đang phát $chName")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Tắt", stop)
            .addAction(0, "Báo lại $SNOOZE_MIN phút", snooze)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun startAlarm(a: AlarmItem) {
        cancelJobs()
        releaseFallback()
        current = a
        AlarmState.usingFallback = false
        AlarmState.ringing = a
        AlarmState.ringStartedAt = System.currentTimeMillis()
        acquireWake(a)

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * a.volume / 100).coerceIn(1, max), 0)
        } catch (e: Exception) {
            // máy đang ở chế độ Không làm phiền: giữ nguyên âm lượng
        }

        val ch = a.channel
        if (ch != null && a.action == AlarmAction.PLAY) Playback.playAlarm(ch, a.ramp) else startFallback()

        watchdog = scope.launch {
            delay(30_000)
            if (Playback.status != Status.PLAYING) startFallback()
            while (true) {
                delay(10_000)
                val dead = Playback.status == Status.ERROR || Playback.status == Status.IDLE
                if (fallback == null && toneJob == null && dead) startFallback()
            }
        }
        if (a.autoStopMin > 0) {
            autoStop = scope.launch {
                delay(a.autoStopMin * 60_000L)
                stopAlarm(true)
            }
        }
        // Android 9 trở xuống (nhiều TV box): mở thẳng màn hình báo thức
        if (Build.VERSION.SDK_INT < 29) {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_ALARM, true)
                )
            } catch (e: Exception) {
            }
        }
    }

    private fun startFallback() {
        if (fallback != null || toneJob != null) return
        AlarmState.usingFallback = true
        if (Playback.isReady) Playback.stop()
        val uri: Uri? = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri != null) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                fallback = mp
                return
            } catch (e: Exception) {
                fallback = null
            }
        }
        // Máy không có nhạc chuông (một số TV box): phát tiếng bíp
        toneJob = scope.launch {
            val tg = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 100)
            } catch (e: Exception) {
                null
            }
            try {
                while (true) {
                    tg?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
                    delay(1_400)
                }
            } finally {
                tg?.release()
            }
        }
    }

    fun stopAlarm(stopPlayback: Boolean) {
        cancelJobs()
        releaseFallback()
        releaseWake()
        if (Playback.isReady) {
            Playback.endRamp()
            if (stopPlayback) Playback.close()
        }
        if (stopPlayback && savedVolume >= 0) {
            try {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            } catch (e: Exception) {
            }
        }
        savedVolume = -1
        current = null
        AlarmState.ringing = null
        AlarmState.usingFallback = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelJobs() {
        watchdog?.cancel()
        autoStop?.cancel()
        toneJob?.cancel()
        watchdog = null
        autoStop = null
        toneJob = null
    }

    private fun releaseFallback() {
        val mp = fallback ?: return
        try {
            mp.stop()
        } catch (e: Exception) {
        }
        mp.release()
        fallback = null
    }

    private fun acquireWake(a: AlarmItem) {
        releaseWake()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val minutes = if (a.autoStopMin > 0) a.autoStopMin + 1 else 121
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MayThu:alarm").apply {
            setReferenceCounted(false)
            acquire(minutes * 60_000L)
        }
    }

    private fun releaseWake() {
        val w = wake ?: return
        if (w.isHeld) w.release()
        wake = null
    }

    override fun onDestroy() {
        cancelJobs()
        releaseFallback()
        releaseWake()
        scope.cancel()
        if (current != null) AlarmState.ringing = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
