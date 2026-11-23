package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R

/** Main-thread ownership for the selected Android projection screen. */
internal object AndroidProjectionSession {
    @Volatile
    var owner: AndroidProjectionActivity? = null
        private set

    fun acquire(activity: AndroidProjectionActivity): Boolean {
        if (owner != null || CarPlayBackgroundSession.hasSession()) return false
        owner = activity
        return true
    }

    fun release(activity: AndroidProjectionActivity) {
        if (owner === activity) owner = null
    }
}

abstract class AndroidProjectionActivity : Activity() {
    protected val ownsProjection: Boolean get() = AndroidProjectionSession.owner === this
    abstract val projectionTitle: Int

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AndroidProjectionSession.acquire(this)) {
            Toast.makeText(this, R.string.android_projection_busy, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    protected fun showSessionNotification() {
        if (!ownsProjection || isFinishing || isDestroyed) return
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_START_ANDROID),
            )
        }.onFailure { AapDiagnostics.log("session", "Notification unavailable: ${it.javaClass.simpleName}") }
    }

    protected fun hideSessionNotification() {
        if (ownsProjection) stopService(Intent(this, DiPlaySessionService::class.java))
    }

    override fun onDestroy() {
        hideSessionNotification()
        AndroidProjectionSession.release(this)
        super.onDestroy()
    }
}
