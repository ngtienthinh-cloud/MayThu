package vn.maythu.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.metadata.icy.IcyInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.maythu.app.data.Channel
import vn.maythu.app.data.Kind
import vn.maythu.app.data.Repo

enum class Status { IDLE, LOADING, PLAYING, PAUSED, ERROR }

/**
 * Một trình phát duy nhất cho cả app (ExoPlayer).
 * Tự thử kiểu luồng khác khi kênh không phát được, tự nối lại khi rớt mạng,
 * đọc tên bài hát (ICY) của đài radio, và hẹn giờ tắt.
 */
@OptIn(UnstableApi::class)
object Playback {
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    lateinit var player: ExoPlayer
        private set
    private var inited = false
    private val scope = MainScope()
    private var retryJob: Job? = null
    private var sleepJob: Job? = null
    private var rampJob: Job? = null

    val isReady: Boolean
        get() = inited

    var current by mutableStateOf<Channel?>(null)
        private set
    var queue by mutableStateOf<List<Channel>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    var status by mutableStateOf(Status.IDLE)
        private set
    var buffering by mutableStateOf(false)
        private set
    var nowTitle by mutableStateOf("")
        private set
    var sleepEndsAt by mutableLongStateOf(0L)
        private set

    /** App gọi để ghi lịch sử "gần đây". */
    var onStarted: ((Channel) -> Unit)? = null

    private var attempt = 0
    private var reconnects = 0
    private var hadPlayed = false
    private var sleepStep = 0
    val sleepSteps = listOf(0, 15, 30, 60, 90)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(ctx.applicationContext)
            .setLoadControl(load)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    buffering = true
                    if (status != Status.PLAYING && status != Status.PAUSED) status = Status.LOADING
                }
                Player.STATE_READY -> {
                    buffering = false
                    if (player.playWhenReady) {
                        status = Status.PLAYING
                        hadPlayed = true
                    }
                }
                Player.STATE_ENDED -> {
                    buffering = false
                    // luồng trực tiếp không có "hết bài": nếu dừng là do rớt, nối lại
                    if (current != null && hadPlayed) scheduleReconnect()
                }
                else -> buffering = false
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                status = Status.PLAYING
                hadPlayed = true
                reconnects = 0
            } else if (!player.playWhenReady && status == Status.PLAYING) {
                status = Status.PAUSED
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            if (hadPlayed && reconnects < 3) {
                reconnects++
                scheduleReconnect()
                return
            }
            attempt++
            startAttempt()
        }

        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is IcyInfo) {
                    val t = entry.title?.trim().orEmpty()
                    if (t.isNotEmpty() && t != nowTitle) nowTitle = t
                }
            }
        }
    }

    /** Thứ tự thử: true = luồng HLS (.m3u8), false = luồng thường (mp3, aac, mp4...). */
    private fun modes(ch: Channel): List<Boolean> {
        val lower = ch.url.lowercase()
        val path = lower.substringBefore('?')
        val hlsLike = ch.hls || lower.contains(".m3u8") || path.endsWith(".m3u")
        val fileLike = listOf(".mp3", ".aac", ".ogg", ".opus", ".m4a", ".flac", ".wav").any { path.endsWith(it) }
        return when {
            hlsLike -> listOf(true, false)
            fileLike -> listOf(false)
            ch.kind == Kind.RADIO -> listOf(false, true)
            else -> listOf(true, false)
        }
    }

    private fun originOf(url: String): String? = try {
        val u = Uri.parse(url)
        if (u.scheme != null && u.host != null) u.scheme + "://" + u.host else null
    } catch (e: Exception) {
        null
    }

    private fun startAttempt() {
        val ch = current ?: return
        val m = modes(ch)
        if (attempt >= m.size) {
            player.stop()
            status = Status.ERROR
            buffering = false
            return
        }
        val asHls = m[attempt]
        val headers = HashMap<String, String>()
        if (ch.referrer.isNotBlank()) {
            headers["Referer"] = ch.referrer
            val origin = originOf(ch.referrer)
            if (origin != null) headers["Origin"] = origin
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent.ifBlank { DEFAULT_UA })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(ch.name)
            .setArtist(if (ch.kind == Kind.RADIO) "Radio" else "Truyền hình")
        if (ch.logo.startsWith("http")) metaBuilder.setArtworkUri(Uri.parse(ch.logo))
        val itemBuilder = MediaItem.Builder()
            .setUri(ch.url)
            .setMediaId(ch.id)
            .setMediaMetadata(metaBuilder.build())
        if (asHls) itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val item = itemBuilder.build()
        val source: MediaSource = if (asHls) {
            HlsMediaSource.Factory(http).setAllowChunklessPreparation(true).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(http).createMediaSource(item)
        }
        status = Status.LOADING
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun scheduleReconnect() {
        val ch = current ?: return
        retryJob?.cancel()
        buffering = true
        retryJob = scope.launch {
            delay(2_000)
            if (current?.id == ch.id) startAttempt()
        }
    }

    /** Báo thức: phát kênh, âm lượng tăng dần từ nhỏ lên to trong khoảng 45 giây. */
    fun playAlarm(ch: Channel, ramp: Boolean) {
        play(ch, listOf(ch))
        if (!ramp) return
        player.volume = 0.12f
        rampJob = scope.launch {
            var waited = 0
            while (status != Status.PLAYING && waited < 120) {
                delay(500)
                waited++
            }
            for (k in 1..30) {
                player.volume = 0.12f + 0.88f * k / 30f
                delay(1_500)
            }
        }
    }

    fun endRamp() {
        rampJob?.cancel()
        rampJob = null
        if (inited) player.volume = 1f
    }

    /** Hẹn giờ tắt: giảm dần âm lượng rồi tắt. */
    fun fadeOutAndClose() {
        rampJob?.cancel()
        scope.launch {
            val v0 = player.volume
            for (k in 1..15) {
                player.volume = v0 * (1f - k / 15f)
                delay(150)
            }
            close()
            player.volume = 1f
        }
    }

    fun play(ch: Channel, list: List<Channel>? = null) {
        rampJob?.cancel()
        rampJob = null
        player.volume = 1f
        if (!list.isNullOrEmpty()) queue = list
        var i = queue.indexOfFirst { it.id == ch.id }
        if (i < 0) {
            queue = listOf(ch)
            i = 0
        }
        index = i
        current = ch
        nowTitle = ""
        attempt = 0
        reconnects = 0
        hadPlayed = false
        retryJob?.cancel()
        onStarted?.invoke(ch)
        if (ch.kind == Kind.RADIO && ch.uuid.isNotBlank()) {
            val id = ch.uuid
            scope.launch { Repo.countClick(id) }
        }
        startAttempt()
    }

    fun next() = step(1)

    fun prev() = step(-1)

    private fun step(d: Int) {
        val q = queue
        if (q.isEmpty()) return
        val base = if (index < 0) 0 else index
        val i = ((base + d) % q.size + q.size) % q.size
        play(q[i])
    }

    fun togglePlay() {
        val ch = current ?: return
        when (status) {
            Status.PLAYING, Status.LOADING -> if (ch.kind == Kind.RADIO) stop() else player.pause()
            Status.PAUSED -> player.play()
            Status.IDLE, Status.ERROR -> play(ch)
        }
    }

    fun stop() {
        retryJob?.cancel()
        player.stop()
        player.clearMediaItems()
        status = Status.IDLE
        buffering = false
    }

    fun close() {
        stop()
        current = null
        nowTitle = ""
    }

    /** Rời màn hình xem TV thì tắt luồng hình để đỡ tốn mạng; radio vẫn phát nền. */
    fun closeIfVideo() {
        if (current?.kind == Kind.TV) close()
    }

    val isActive: Boolean
        get() = status == Status.PLAYING || status == Status.LOADING

    /** Vị trí kênh trong danh sách, 0..1 — cho kim trên thang dò sóng. */
    val dialPosition: Float?
        get() {
            if (current == null) return null
            val n = queue.size
            return if (n > 1 && index >= 0) index.toFloat() / (n - 1) else 0.5f
        }

    fun statusText(): String {
        val ch = current ?: return ""
        return when (status) {
            Status.LOADING -> "Đang dò sóng…"
            Status.PAUSED -> "Tạm dừng"
            Status.IDLE -> "Đã dừng"
            Status.ERROR -> "Không phát được kênh này"
            Status.PLAYING -> when {
                buffering -> "Đang tải lại tín hiệu…"
                nowTitle.isNotEmpty() -> nowTitle
                ch.kind == Kind.RADIO -> ch.subtitle
                else -> "Đang phát trực tiếp"
            }
        }
    }

    /** Bấm lần lượt: 15 → 30 → 60 → 90 phút → tắt hẹn giờ. Trả về số phút. */
    fun cycleSleep(): Int {
        sleepStep = (sleepStep + 1) % sleepSteps.size
        val minutes = sleepSteps[sleepStep]
        sleepJob?.cancel()
        if (minutes <= 0) {
            sleepEndsAt = 0L
            return 0
        }
        sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            val v0 = player.volume
            for (k in 1..20) {
                player.volume = v0 * (1f - k / 20f)
                delay(150)
            }
            stop()
            player.volume = v0
            sleepEndsAt = 0L
            sleepStep = 0
        }
        return minutes
    }
}
