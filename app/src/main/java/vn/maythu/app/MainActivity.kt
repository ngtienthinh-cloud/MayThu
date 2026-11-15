package vn.maythu.app

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import vn.maythu.app.alarm.AlarmState
import vn.maythu.app.data.Repo
import vn.maythu.app.player.Playback
import vn.maythu.app.ui.AppRoot
import vn.maythu.app.ui.AppVm
import vn.maythu.app.ui.MayThuTheme

class MainActivity : ComponentActivity() {
    companion object {
        /** Mở từ báo thức: hiện trên màn hình khóa và bật sáng màn hình. */
        const val EXTRA_ALARM = "vn.maythu.app.EXTRA_ALARM"

        /** Mở thẳng mục Báo thức. */
        const val EXTRA_OPEN_ALARMS = "vn.maythu.app.EXTRA_OPEN_ALARMS"
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        Repo.init(applicationContext)
        Playback.init(applicationContext)
        handleIntent(intent)
        val detectedTv = isTvDevice()
        setContent {
            MayThuTheme {
                val vm: AppVm = viewModel()
                AppRoot(vm = vm, detectedTv = detectedTv)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra(EXTRA_ALARM, false)) lockScreenMode(true)
        if (i.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) AlarmState.openAlarmsTick++
    }

    /** Khi báo thức reo: hiện đè lên màn hình khóa, bật sáng và giữ sáng màn hình. */
    fun lockScreenMode(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(f) else window.clearFlags(f)
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        // Kết nối với dịch vụ phát nền để radio chạy tiếp khi tắt màn hình
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun isTvDevice(): Boolean {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
