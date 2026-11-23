package com.shilapi.xcertplay.carlife

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.AapDiagnostics
import com.shilapi.xcertplay.AapVideoDecoder
import com.shilapi.xcertplay.AndroidProjectionActivity
import com.shilapi.xcertplay.isProjectionIpv4
import com.shilapi.xcertplay.compat.realSdkInt
import com.shilapi.xcertplay.host.R
import java.io.Closeable
import java.io.IOException

/**
 * The Baidu CarLife desktop activity for Android phones.
 *
 * Connects to the phone over Wi-Fi or its hotspot.
 *
 * Renders video on a letterboxed SurfaceView, maps touches to the encoded frame, and
 * forwards physical keys back to the mobile phone.
 *
 */
class CarLifeActivity : AndroidProjectionActivity() {
    override val projectionTitle = R.string.carlife_waiting_title


    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var waitingPanel: View
    private lateinit var waitingTitle: TextView
    private lateinit var waitingStatus: TextView
    private lateinit var waitingDetail: TextView
    private lateinit var waitingDot: View
    private lateinit var controlBar: View
    private lateinit var controlStatus: TextView
    private lateinit var touchStrip: View
    private lateinit var topHandle: View

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audioFocus: com.shilapi.xcertplay.AapAudioFocusHelper
    private var decoder: AapVideoDecoder? = null
    private var surface: Surface? = null
    private var carLifeSession: CarLifeSession? = null
    private var discoveryCloser: Closeable? = null
    private var currentHost: String? = null

    private var videoWidth = 0
    private var videoHeight = 0
    private var lastTouchX = -1
    private var lastTouchY = -1
    private var singleFingerDown = false
    private var pinchActive = false
    private var pinchZoomTriggered = false
    private var pinchScale = 1f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var projected = false
    private var controlBarVisible = false
    @Volatile private var attempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ownsProjection) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        audioFocus = com.shilapi.xcertplay.AapAudioFocusHelper(
            context = this,
            onDuckChanged = { duck -> carLifeSession?.setAudioDucking(duck) },
            onMuteChanged = { mute -> carLifeSession?.setAudioMuted(mute) },
            log = { log(it) },
        )

        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        root.addView(
            buildWaitingPanel(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            buildTouchStrip(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(24),
                Gravity.BOTTOM,
            ),
        )
        root.addView(
            buildTopHandle(),
            FrameLayout.LayoutParams(
                dp(128),
                dp(32),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(4) },
        )
        root.addView(
            buildControlBar(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(18) },
        )
        setContentView(root)

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutChrome() }
        root.post { layoutChrome() }

        surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                surface = holder.surface
                decoder?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                surface = holder.surface
                layoutSurface()
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                surface = null
                decoder?.setSurface(null)
            }
        })
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pinchActive = true
                    pinchZoomTriggered = false
                    pinchScale = 1f
                    if (singleFingerDown && lastTouchX >= 0 && lastTouchY >= 0) {
                        singleFingerDown = false
                        carLifeSession?.sendTouch(CarLifeTouchGesture.UP, lastTouchX, lastTouchY)
                    }
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    pinchScale *= detector.scaleFactor
                    if (!pinchZoomTriggered &&
                        videoWidth > 0 && videoHeight > 0 && surfaceView.width > 0 && surfaceView.height > 0
                    ) {
                        if (pinchScale >= 1.18f) {
                            pinchZoomTriggered = true
                            val fx = ((detector.focusX * videoWidth) / surfaceView.width).toInt().coerceIn(0, videoWidth - 1)
                            val fy = ((detector.focusY * videoHeight) / surfaceView.height).toInt().coerceIn(0, videoHeight - 1)
                            log("touch pinch-out zoom-in -> doubleTap ($fx, $fy) scale=${"%.2f".format(pinchScale)}")
                            carLifeSession?.sendDoubleTap(fx, fy)
                        } else if (pinchScale <= 0.84f) {
                            pinchZoomTriggered = true
                            val fx = ((detector.focusX * videoWidth) / surfaceView.width).toInt().coerceIn(0, videoWidth - 1)
                            val fy = ((detector.focusY * videoHeight) / surfaceView.height).toInt().coerceIn(0, videoHeight - 1)
                            log("touch pinch-in zoom-out -> doubleTapDragUp ($fx, $fy) scale=${"%.2f".format(pinchScale)}")
                            carLifeSession?.sendDoubleTapDragZoomOut(fx, fy, videoHeight)
                        }
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    pinchScale = 1f
                }
            },
        )
        surfaceView.setOnTouchListener { view, event -> onVideoTouch(view, event) }

        connect(savedInstanceState?.getString(EXTRA_HOST) ?: intent.getStringExtra(EXTRA_HOST))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_HOST, currentHost)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (ownsProjection) stopSession()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (projected) {
            val mapped = CarLifeKeyCodes.fromAndroidKeyEvent(keyCode)
            if (mapped != null) {
                log("hardkey sent: keyCode=$keyCode mapped=0x${mapped.toString(16)}")
                carLifeSession?.sendHardKey(mapped)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun connect(requestedHost: String?) {
        stopSession()
        val run = attempt
        currentHost = requestedHost
        if (requestedHost != null) {
            connectWireless(requestedHost, run)
            return
        }
        showWaiting(getString(R.string.carlife_wireless_searching))
        waitingTitle.text = getString(R.string.carlife_wireless_title)
        discoveryCloser = CarLifeDiscovery.autoDiscover(this) { host ->
            onUi(run) {
                discoveryCloser?.close()
                discoveryCloser = null
                if (host != null) connectWireless(host, run)
                else waitingStatus.text = getString(R.string.carlife_phone_not_found)
            }
        }
    }

    private fun connectWireless(host: String, run: Int) {
        currentHost = host
        showWaiting(getString(R.string.carlife_wireless_connecting, host))
        waitingTitle.text = getString(R.string.carlife_wireless_title)
        val carlife = CarLifeSession(CarLifeSocketTransport(host, ::log), ::log)
        carLifeSession = carlife
        val video = AapVideoDecoder(
            log = { log("decoder: $it") },
            onFirstFrame = {
                onUi(run) {
                    projected = true
                    waitingPanel.visibility = View.GONE
                    showControlBar(temporarily = true)
                }
            },
            onStallDetected = carlife::requestVideoReset,
        )
        video.setSurface(surface)
        decoder = video
        Thread({
            try {
                runCarLifeSession(carlife, video, run)
            } catch (e: Exception) {
                onSessionFailed(run, e.message ?: getString(R.string.aa_error_connect_failed))
            } finally {
                carlife.close()
            }
        }, "carlife-wireless").apply { isDaemon = true }.start()
    }

    private fun onUi(run: Int, action: () -> Unit) {
        main.post {
            if (run == attempt && ownsProjection && !isFinishing && !isDestroyed) action()
        }
    }

    private fun runCarLifeSession(carlife: CarLifeSession, video: AapVideoDecoder, run: Int) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val highResEligible = realSdkInt() > Build.VERSION_CODES.LOLLIPOP && maxOf(screenW, screenH) > 1024
        val targetW = if (highResEligible) 1280 else 800
        val targetH = if (highResEligible) 720 else 480

        val matched = carlife.handshake(
            deviceInfo = CarLifeDeviceInfo(
                os = "Android",
                board = android.os.Build.BOARD.ifBlank { "DiPlay" },
                bootloader = android.os.Build.BOOTLOADER.ifBlank { "unknown" },
                brand = android.os.Build.BRAND.ifBlank { "DiPlay" },
                cpuAbi = @Suppress("DEPRECATION") android.os.Build.CPU_ABI.ifBlank { "arm64-v8a" },
                device = android.os.Build.DEVICE.ifBlank { "DiPlay" },
                display = android.os.Build.DISPLAY.ifBlank { "DiPlay" },
                hardware = android.os.Build.HARDWARE.ifBlank { "unknown" },
                manufacturer = android.os.Build.MANUFACTURER.ifBlank { "DiPlay" },
                model = android.os.Build.MODEL.ifBlank { "DiPlay" },
                product = Build.PRODUCT.ifBlank { "DiPlay" },
                sdk = realSdkInt().toString(),
                sdkInt = realSdkInt(),
            ),
            width = targetW,
            height = targetH,
            frameRate = 30,
        )

        if (!matched) {
            throw IOException("CarLife protocol handshake timed out or refused")
        }

        carlife.onVideoSizeChanged = { width, height ->
            onUi(run) { setVideoSize(width, height, video) }
        }
        carlife.onTcpAudioActive = { onUi(run) { audioFocus.requestFocus() } }

        onUi(run) {
            setVideoSize(
                carlife.videoWidth.takeIf { it > 0 } ?: targetW,
                carlife.videoHeight.takeIf { it > 0 } ?: targetH,
                video,
            )
            AapDiagnostics.recordSessionStart("Baidu CarLife", "${videoWidth}x${videoHeight}")
            waitingStatus.text = getString(R.string.carlife_status_connected)
            waitingDetail.text = getString(R.string.carlife_waiting_screen_permission)
            showSessionNotification()
        }
        carlife.pumpVideo { nal ->
            if (run == attempt) video.queue(nal, 0, nal.size, System.nanoTime() / 1_000)
        }
        onSessionFailed(run, getString(R.string.carlife_disconnected))
    }

    private fun setVideoSize(width: Int, height: Int, video: AapVideoDecoder) {
        videoWidth = width
        videoHeight = height
        layoutSurface()
        video.setVideoSize(width, height)
    }

    private fun onSessionFailed(run: Int, reason: String) = onUi(run) {
        log(reason)
        stopSession(reason)
        showWaiting(reason)
        waitingTitle.text = getString(R.string.carlife_disconnected)
        waitingDetail.text = getString(R.string.carlife_xiaomi_hint)
        waitingDot.setBackgroundColor(WARN)
    }

    private fun stopSession(reason: String? = null) {
        attempt++
        hideSessionNotification()
        audioFocus.abandonFocus()
        discoveryCloser?.close()
        discoveryCloser = null
        carLifeSession?.let { session ->
            AapDiagnostics.updateSessionMetrics(
                resolution = "${videoWidth}x${videoHeight}",
                renderedFrames = decoder?.renderedFrames ?: 0L,
                droppedVideoFrames = (decoder?.dropped ?: 0).toLong(),
                decoderResets = decoder?.stallRecoveries ?: 0,
                playedAudioChunks = session.playedAudioChunks(),
                droppedAudioChunks = session.droppedAudioChunks(),
            )
            AapDiagnostics.recordSessionEnd(reason)
            session.close()
        }
        carLifeSession = null
        decoder?.close()
        decoder = null
        projected = false
        videoWidth = 0
        videoHeight = 0
        lastTouchX = -1
        lastTouchY = -1
        singleFingerDown = false
        pinchActive = false
        main.removeCallbacks(hideControlRunnable)
        hideControlRunnable.run()
    }

    private fun onVideoTouch(view: View, event: MotionEvent): Boolean {
        val session = carLifeSession ?: return false
        if (!projected) return false
        if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0 || view.height <= 0) return false

        scaleDetector.onTouchEvent(event)

        val frameX = ((event.x * videoWidth) / view.width).toInt().coerceIn(0, videoWidth - 1)
        val frameY = ((event.y * videoHeight) / view.height).toInt().coerceIn(0, videoHeight - 1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinchActive = false
                pinchZoomTriggered = false
                pinchScale = 1f
                singleFingerDown = true
                lastTouchX = frameX
                lastTouchY = frameY
                log("touch DOWN: ($frameX, $frameY)")
                session.sendTouch(CarLifeTouchGesture.DOWN, frameX, frameY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinchActive = true
                if (singleFingerDown && lastTouchX >= 0 && lastTouchY >= 0) {
                    singleFingerDown = false
                    session.sendTouch(CarLifeTouchGesture.UP, lastTouchX, lastTouchY)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinchActive || event.pointerCount > 1) return true
                if (!singleFingerDown || (frameX == lastTouchX && frameY == lastTouchY)) return true
                lastTouchX = frameX
                lastTouchY = frameY
                session.sendTouch(CarLifeTouchGesture.MOVE, frameX, frameY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (singleFingerDown) {
                    singleFingerDown = false
                    log("touch UP: ($frameX, $frameY)")
                    session.sendTouch(CarLifeTouchGesture.UP, frameX, frameY)
                }
                pinchActive = false
                pinchZoomTriggered = false
                pinchScale = 1f
                lastTouchX = -1
                lastTouchY = -1
            }
        }
        return true
    }

    private fun showWaiting(status: String) {
        controlBarVisible = false
        main.removeCallbacks(hideControlRunnable)
        controlBar.visibility = View.GONE
        topHandle.visibility = View.GONE
        waitingTitle.text = getString(R.string.carlife_waiting_title)
        waitingStatus.text = status
        waitingDetail.text = getString(R.string.carlife_desktop_hint)
        waitingDot.setBackgroundColor(ACCENT)
        waitingPanel.visibility = View.VISIBLE
        waitingPanel.alpha = 1f
    }

    private fun layoutSurface() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val rect = surfaceRect()
        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width == rect.width() && params.height == rect.height() && params.gravity == Gravity.CENTER) {
            return
        }
        params.width = rect.width()
        params.height = rect.height()
        params.gravity = Gravity.CENTER
        surfaceView.layoutParams = params
    }

    private fun surfaceRect(): android.graphics.Rect {
        val availableW = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val availableH = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (videoWidth <= 0 || videoHeight <= 0) return android.graphics.Rect(0, 0, availableW, availableH)

        val targetRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val screenRatio = availableW.toFloat() / availableH.toFloat()

        return if (screenRatio > targetRatio) {
            val w = (availableH * targetRatio).toInt()
            val left = (availableW - w) / 2
            android.graphics.Rect(left, 0, left + w, availableH)
        } else {
            val h = (availableW / targetRatio).toInt()
            val top = (availableH - h) / 2
            android.graphics.Rect(0, top, availableW, top + h)
        }
    }

    private fun layoutChrome() {
        if (root.width == 0 || root.height == 0) return
        layoutSurface()
        val targetW = minOf(root.width - dp(24), dp(430))
        val barParams = controlBar.layoutParams as? FrameLayout.LayoutParams ?: return
        if (barParams.width != targetW) {
            barParams.width = targetW
            controlBar.layoutParams = barParams
        }
    }

    private fun promptManualIp() {
        val prefs = getSharedPreferences("carlife", Context.MODE_PRIVATE)
        val current = currentHost ?: prefs.getString("manual_host", null).orEmpty()
        val input = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            setTextColor(INK)
            setSingleLine(true)
            hint = "192.168.1.100"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.carlife_wireless_ip_dialog_title)
            .setMessage(R.string.carlife_wireless_ip_dialog_message)
            .setView(input)
            .setPositiveButton(R.string.carlife_connect) { _, _ ->
                val host = input.text.toString().trim()
                if (isProjectionIpv4(host)) {
                    prefs.edit().putString("manual_host", host).apply()
                    connect(host)
                } else {
                    waitingStatus.text = getString(R.string.android_projection_invalid_ip)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildWaitingPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BACKGROUND)
        }
        waitingDot = View(this).apply { background = circle(16f, ACCENT) }
        panel.addView(waitingDot, LinearLayout.LayoutParams(dp(32), dp(32)).apply { bottomMargin = dp(20) })

        waitingTitle = TextView(this).apply {
            text = getString(R.string.carlife_waiting_title)
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        panel.addView(waitingTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        waitingStatus = TextView(this).apply {
            text = getString(R.string.carlife_waiting_status)
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        panel.addView(waitingStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        waitingDetail = TextView(this).apply {
            text = getString(R.string.carlife_desktop_hint)
            setTextColor(FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
        }
        panel.addView(waitingDetail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        // Action buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        buttonRow.addView(capsuleButton(getString(R.string.carlife_retry), quiet = true) {
            connect(currentHost)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
            rightMargin = dp(12)
        })

        buttonRow.addView(capsuleButton(getString(R.string.carlife_wireless_manual_ip), quiet = true) {
            promptManualIp()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
            rightMargin = dp(12)
        })

        buttonRow.addView(capsuleButton(getString(R.string.aa_desktop_show_log), quiet = true) {
            showLiveLogDialog()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
            rightMargin = dp(12)
        })

        buttonRow.addView(capsuleButton(getString(R.string.aa_desktop_leave), quiet = true) {
            finish()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))

        panel.addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })

        waitingPanel = panel
        return panel
    }

    private fun buildTouchStrip(): View {
        touchStrip = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (projected) showControlBar(temporarily = true)
            }
        }
        return touchStrip
    }

    private fun buildTopHandle(): View {
        val container = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (projected) showControlBar(temporarily = true)
            }
        }
        val pill = View(this).apply {
            background = rounded(4f, Color.argb(140, 220, 232, 250))
        }
        container.addView(
            pill,
            FrameLayout.LayoutParams(dp(48), dp(5), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = dp(8)
            },
        )
        topHandle = container
        return container
    }

    private fun buildControlBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(28f, CAPSULE, stroke = 1f, strokeColor = CAPSULE_STROKE)
            setPadding(dp(18), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        controlStatus = TextView(this).apply {
            text = "Baidu CarLife"
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
        }
        bar.addView(
            controlStatus,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(16)
            },
        )
        bar.addView(
            capsuleButton("刷新画面", quiet = true, compact = true) {
                log("user tapped refresh video")
                carLifeSession?.requestVideoReset()
                showControlBar(temporarily = true)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                rightMargin = dp(8)
            },
        )
        bar.addView(
            capsuleButton("诊断日志", quiet = true, compact = true) {
                showLiveLogDialog()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                rightMargin = dp(8)
            },
        )
        bar.addView(
            capsuleButton(getString(R.string.aa_desktop_leave), quiet = false, compact = true) {
                finish()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)),
        )
        controlBar = bar
        return bar
    }

    private fun showLiveLogDialog() {
        main.removeCallbacks(hideControlRunnable)
        val lines = com.shilapi.xcertplay.AapDiagnostics.snapshot().takeLast(45)
        val body = if (lines.isEmpty()) "暂无 CarLife 诊断日志" else lines.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("CarLife 实时日志")
            .setMessage(body)
            .setPositiveButton("刷新") { _, _ -> showLiveLogDialog() }
            .setNeutralButton("返回 DiPlay") { _, _ -> finish() }
            .setNegativeButton("关闭") { _, _ ->
                if (projected) showControlBar(temporarily = true)
            }
            .show()
    }

    private fun showControlBar(temporarily: Boolean) {
        controlBarVisible = true
        controlBar.visibility = View.VISIBLE
        controlBar.alpha = 1f
        topHandle.visibility = View.GONE
        main.removeCallbacks(hideControlRunnable)
        if (temporarily) {
            main.postDelayed(hideControlRunnable, 6_000L)
        }
    }

    private val hideControlRunnable = Runnable {
        controlBarVisible = false
        controlBar.visibility = View.GONE
        topHandle.visibility = if (projected) View.VISIBLE else View.GONE
    }

    private fun goImmersive() {
        if (realSdkInt() >= 19) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }

    private fun log(message: String) {
        android.util.Log.d("CarLifeActivity", message)
        AapDiagnostics.log("carlife", message)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(radiusDp: Float, fill: Int, stroke: Float = 0f, strokeColor: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(fill)
            if (stroke > 0f) setStroke(dp(stroke.toInt()).coerceAtLeast(1), strokeColor)
        }

    private fun circle(radiusDp: Float, fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setSize(dp(radiusDp.toInt() * 2), dp(radiusDp.toInt() * 2))
    }

    private fun capsuleButton(
        text: String,
        quiet: Boolean = false,
        compact: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        isSingleLine = true
        includeFontPadding = false
        val horizontalPadding = dp(if (compact) 14 else 18)
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (quiet) {
            setTextColor(MUTED)
            background = rounded(19f, QUIET)
        } else {
            setTextColor(Color.rgb(9, 14, 22))
            background = rounded(19f, ACCENT)
        }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val EXTRA_HOST = "host"

        private val BACKGROUND = Color.rgb(8, 12, 19)
        private val INK = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val FAINT = Color.rgb(122, 137, 158)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val WARN = Color.rgb(255, 176, 120)
        private val CAPSULE = Color.argb(232, 14, 21, 32)
        private val CAPSULE_STROKE = Color.argb(46, 190, 210, 240)
        private val QUIET = Color.argb(28, 255, 255, 255)

        fun intentForWireless(context: Context, host: String? = null): Intent =
            Intent(context, CarLifeActivity::class.java).putExtra(EXTRA_HOST, host)
    }
}
