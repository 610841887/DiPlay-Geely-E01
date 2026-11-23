package com.shilapi.xcertplay.carlife

import com.shilapi.xcertplay.AapAudioPlayer
import com.shilapi.xcertplay.AnnexB
import com.shilapi.xcertplay.media.AnnexBSplitter
import com.shilapi.xcertplay.carlife.CarLifeChannel.CMD
import com.shilapi.xcertplay.carlife.CarLifeChannel.CTRL
import com.shilapi.xcertplay.carlife.CarLifeChannel.MEDIA
import com.shilapi.xcertplay.carlife.CarLifeChannel.TTS
import com.shilapi.xcertplay.carlife.CarLifeChannel.VIDEO
import com.shilapi.xcertplay.carlife.CarLifeChannel.VR
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The head-unit half of the CarLife protocol.
 *
 * Uses a [CarLifeTransport] to reach the phone's command and media channels.
 *
 * Compatible with Android 4.2+ (API 17).
 */
open class CarLifeSession(
    private val transport: CarLifeTransport,
    private val log: (String) -> Unit = {},
) : Closeable {

    private var cmdStream: CarLifeStream? = null
    private var videoStream: CarLifeStream? = null
    private var ctrlStream: CarLifeStream? = null
    private var mediaStream: CarLifeStream? = null
    private var ttsStream: CarLifeStream? = null
    private var vrStream: CarLifeStream? = null

    private var cmdWriter: CarLifeFrameWriter? = null
    private var cmdReader: CarLifeFrameReader? = null
    private var videoReader: CarLifeFrameReader? = null
    private var videoWriter: CarLifeFrameWriter? = null
    private var ctrlWriter: CarLifeFrameWriter? = null
    private var mediaReader: CarLifeFrameReader? = null
    private var ttsReader: CarLifeFrameReader? = null

    @Volatile
    private var requestedWidth = 1280
    @Volatile
    private var requestedHeight = 720
    @Volatile
    private var requestedFrameRate = 30
    @Volatile
    var receivedVideoDataFrames = 0L
        private set
    @Volatile
    var receivedVideoHeartbeats = 0L
        private set

    private var mediaPlayer: AapAudioPlayer? = AapAudioPlayer(AapAudioPlayer.AudioRole.MEDIA) { log("carlife-media: $it") }
    private var ttsPlayer: AapAudioPlayer? = AapAudioPlayer(AapAudioPlayer.AudioRole.GUIDANCE) { log("carlife-tts: $it") }

    @Volatile
    private var ttsActive = false
    @Volatile
    private var focusDucked = false

    fun setAudioDucking(duck: Boolean) {
        focusDucked = duck
        mediaPlayer?.setDucking(ttsActive || focusDucked)
    }

    fun setAudioMuted(mute: Boolean) {
        mediaPlayer?.setMuted(mute)
    }

    fun playedAudioChunks(): Long = (mediaPlayer?.playedChunks ?: 0L) + (ttsPlayer?.playedChunks ?: 0L)

    fun droppedAudioChunks(): Long = (mediaPlayer?.droppedChunks ?: 0L) + (ttsPlayer?.droppedChunks ?: 0L)

    @Volatile
    private var closed = false
    private val ctrlExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "carlife-ctrl-out") }
    private var heartbeatThread: Thread? = null
    private var cmdPumpThread: Thread? = null
    private var mediaPumpThread: Thread? = null
    private var ttsPumpThread: Thread? = null

    /** Encoded size the phone agreed to, once `VIDEO_ENCODER_INIT_DONE` has arrived. */
    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    /** Invoked when the phone re-announces its encoder dimensions mid-session. */
    @Volatile
    var onVideoSizeChanged: ((Int, Int) -> Unit)? = null

    /** Invoked once if the phone actually streams PCM audio over TCP MEDIA (9240) / TTS (9241). */
    @Volatile
    var onTcpAudioActive: (() -> Unit)? = null
    @Volatile
    private var tcpAudioActivated = false

    /** True once the phone answered the version exchange with "match". */
    @Volatile
    var versionMatched = false
        private set

    /**
     * Opens the command channel, agrees a protocol version, asks for a video encoder and starts it.
     *
     * Returns false rather than throwing on a refusal: "the CarLife app is not running" is the ordinary
     * first outcome on a phone that has never been used this way, and the caller has to show a hint
     * instead of a stack trace.
     */
    fun handshake(
        deviceInfo: CarLifeDeviceInfo,
        width: Int,
        height: Int,
        frameRate: Int,
        timeoutMillis: Int = HANDSHAKE_TIMEOUT_MS,
    ): Boolean {
        requestedWidth = width
        requestedHeight = height
        requestedFrameRate = frameRate

        val cmd = openChannel(CMD, timeoutMillis) ?: return false
        cmdStream = cmd
        // Bound blocking reads during handshake so deadline loops actually expire instead of
        // hanging forever when the phone's CarLife app is stuck in a previous session or waiting
        // for VIDEO/CTRL sockets to connect first.
        cmd.setReadTimeout(HANDSHAKE_READ_SLICE_MS)
        val writer = CarLifeFrameWriter(CMD, cmd.outputStream())
        val reader = CarLifeFrameReader(CMD, cmd.inputStream())
        cmdWriter = writer
        cmdReader = reader

        // Open VIDEO, CTRL, and auxiliary sockets BEFORE sending VIDEO_ENCODER_INIT/START.
        // Baidu CarLife+'s ConnectManager waits for the data channels to be accepted before
        // binding its hardware screen encoder; sending VIDEO_ENCODER_START before port 8240
        // connects causes the app to emit only its static 1-frame splash logo.
        openDataChannels(timeoutMillis)

        // Version first: the phone drops every other message until the pair has been agreed.
        writer.write(CarLifeMessage.HU_PROTOCOL_VERSION, CarLifeProto.encodeProtocolVersion(1, 0))
        if (!awaitVersionMatch(timeoutMillis, writer)) {
            cmd.setReadTimeout(0)
            return false
        }

        // Proactively provide statistics info with authorized vehicle channel (prevents unauthorized dialog)
        writer.write(CarLifeMessage.HU_STATISTICAL_INFO, CarLifeProto.encodeStatisticsInfo())
        writer.write(CarLifeMessage.HU_INFO, CarLifeProto.encodeDeviceInfo(deviceInfo))
        writer.write(
            CarLifeMessage.VIDEO_ENCODER_INIT,
            CarLifeProto.encodeVideoEncoderInfo(width, height, frameRate),
        )

        val encoderWaitMs = minOf(timeoutMillis, ENCODER_READY_WAIT_MS)
        val deadline = System.currentTimeMillis() + encoderWaitMs
        var encoderReady = false
        while (System.currentTimeMillis() < deadline && !encoderReady && !closed) {
            val frame = try {
                reader.read()
            } catch (_: SocketTimeoutException) {
                continue
            } ?: break
            log("carlife rx ${hex(frame.type)} (payload=${frame.payload.size}b)")
            when (frame.type) {
                CarLifeMessage.MD_INFO -> {
                    writer.write(CarLifeMessage.HU_STATISTICAL_INFO, CarLifeProto.encodeStatisticsInfo())
                    writer.write(CarLifeMessage.HU_INFO, CarLifeProto.encodeDeviceInfo(deviceInfo))
                }
                CarLifeMessage.MD_AUTHEN_RESULT, CarLifeMessage.MD_AUTHEN_RESPONSE -> {
                    writer.write(CarLifeMessage.HU_AUTHEN_RESULT, byteArrayOf())
                    writer.write(
                        CarLifeMessage.VIDEO_ENCODER_INIT,
                        CarLifeProto.encodeVideoEncoderInfo(width, height, frameRate),
                    )
                }
                CarLifeMessage.MD_FEATURE_CONFIG_REQUEST -> {
                    log("carlife: replying HU_FEATURE_CONFIG_RESPONSE (0x00018019) AUDIO_TRANSMISSION_MODE=1")
                    writer.write(CarLifeMessage.HU_FEATURE_CONFIG_RESPONSE, CarLifeProto.encodeFeatureConfigList())
                }
                CarLifeMessage.MD_FEATURE_CONFIG_LIST -> {
                    log("carlife: replying HU_FEATURE_CONFIG_RESPONSE_V2 (0x00018052) AUDIO_TRANSMISSION_MODE=1")
                    writer.write(CarLifeMessage.HU_FEATURE_CONFIG_RESPONSE_V2, CarLifeProto.encodeFeatureConfigList())
                }
                CarLifeMessage.MD_FOREGROUND -> {
                    writer.write(CarLifeMessage.VEHICLE_FOREGROUND)
                    writer.write(CarLifeMessage.GO_TO_FOREGROUND)
                }
                CarLifeMessage.VIDEO_ENCODER_INIT_DONE, CarLifeMessage.VIDEO_ENCODER_READY -> {
                    if (frame.payload.isNotEmpty()) {
                        val info = CarLifeProto.decodeVideoEncoderInfo(frame.payload)
                        if (info.width > 0) videoWidth = info.width
                        if (info.height > 0) videoHeight = info.height
                        log("carlife encoder confirmed: ${videoWidth}x${videoHeight} @${info.frameRate}")
                    }
                    writer.write(CarLifeMessage.VIDEO_ENCODER_START)
                    writer.write(CarLifeMessage.GO_TO_FOREGROUND)
                    writer.write(CarLifeMessage.LAUNCH_MODE_NORMAL)
                    writer.write(CarLifeMessage.VEHICLE_FOREGROUND)
                    encoderReady = true
                }
                CarLifeMessage.CONNECT_EXCEPTION, CarLifeMessage.MD_EXIT -> {
                    log("carlife: phone aborted during setup (${hex(frame.type)})")
                    cmd.setReadTimeout(0)
                    return false
                }
                else -> {
                    log("carlife: unhandled handshake message ${hex(frame.type)}")
                }
            }
        }

        if (!encoderReady) {
            log("carlife: proceeding to start stream (fallback)")
            writer.write(CarLifeMessage.VIDEO_ENCODER_START)
            writer.write(CarLifeMessage.GO_TO_FOREGROUND)
            writer.write(CarLifeMessage.LAUNCH_MODE_NORMAL)
            writer.write(CarLifeMessage.VEHICLE_FOREGROUND)
        }

        cmd.setReadTimeout(0)
        startSessionPumps()
        return true
    }

    private fun awaitVersionMatch(timeoutMillis: Int, writer: CarLifeFrameWriter): Boolean {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutMillis
        var retried = false
        while (System.currentTimeMillis() < deadline && !closed) {
            val frame = try {
                cmdReader!!.read()
            } catch (_: SocketTimeoutException) {
                if (!retried && System.currentTimeMillis() - startedAt >= VERSION_RETRY_DELAY_MS) {
                    retried = true
                    log("carlife: no version reply yet; re-sending HU_PROTOCOL_VERSION")
                    runCatching {
                        writer.write(CarLifeMessage.HU_PROTOCOL_VERSION, CarLifeProto.encodeProtocolVersion(1, 0))
                    }
                }
                continue
            } ?: return false
            if (frame.type == CarLifeMessage.PROTOCOL_VERSION_MATCH_STATUS) {
                val status = CarLifeProto.decodeSingleInt(frame.payload)
                versionMatched = status == CarLifeMessage.VERSION_MATCH_OK || status == 1 || status == 0
                log("carlife version match status=$status (accepted=$versionMatched)")
                return versionMatched
            }
            log("carlife cmd ${hex(frame.type)} before the version match, ignored")
        }
        log("carlife: no version match within ${timeoutMillis}ms")
        return false
    }

    private fun openDataChannels(timeoutMillis: Int) {
        if (videoStream == null) {
            openChannel(VIDEO, timeoutMillis)?.let {
                videoStream = it
                videoReader = CarLifeFrameReader(VIDEO, it.inputStream())
                videoWriter = CarLifeFrameWriter(VIDEO, it.outputStream())
            }
        }
        if (ctrlStream == null) {
            openChannel(CTRL, timeoutMillis)?.let {
                ctrlStream = it
                ctrlWriter = CarLifeFrameWriter(CTRL, it.outputStream())
            }
        }
        if (videoReader == null || ctrlWriter == null) {
            log("carlife: video=${videoReader != null} control=${ctrlWriter != null}")
        }

        // Auxiliary channels (MEDIA, TTS, VR): connect asynchronously so all 6 CarLife
        // ports are accepted by the phone's ConnectManager without blocking video setup.
        Thread({
            try {
                openChannel(MEDIA, 2000)?.let { stream ->
                    mediaStream = stream
                    val reader = CarLifeFrameReader(MEDIA, stream.inputStream())
                    mediaReader = reader
                    log("carlife: media audio channel connected")
                    mediaPumpThread = Thread({ pumpMedia(reader) }, "carlife-media-pump").apply { start() }
                }
            } catch (e: Exception) {
                if (!closed) log("carlife media audio setup error: ${e.message}")
            }
            try {
                openChannel(TTS, 2000)?.let { stream ->
                    ttsStream = stream
                    val reader = CarLifeFrameReader(TTS, stream.inputStream())
                    ttsReader = reader
                    log("carlife: tts audio channel connected")
                    ttsPumpThread = Thread({ pumpTts(reader) }, "carlife-tts-pump").apply { start() }
                }
            } catch (e: Exception) {
                if (!closed) log("carlife tts audio setup error: ${e.message}")
            }
            try {
                openChannel(VR, 2000)?.let { stream ->
                    vrStream = stream
                    log("carlife: vr channel connected")
                }
            } catch (e: Exception) {
                if (!closed) log("carlife vr channel setup error: ${e.message}")
            }
        }, "carlife-aux-setup").apply { isDaemon = true; start() }
    }

    private fun startSessionPumps() {
        cmdPumpThread = Thread({
            val reader = cmdReader ?: return@Thread
            while (!closed) {
                val frame = try {
                    reader.read()
                } catch (e: Exception) {
                    if (!closed) log("carlife cmd ended: ${e.message}")
                    break
                } ?: break
                when (frame.type) {
                    CarLifeMessage.MD_FEATURE_CONFIG_REQUEST -> {
                        log("carlife cmd rx: MD_FEATURE_CONFIG_REQUEST (legacy)")
                        cmdWriter?.write(CarLifeMessage.HU_FEATURE_CONFIG_RESPONSE, CarLifeProto.encodeFeatureConfigList())
                    }
                    CarLifeMessage.MD_FEATURE_CONFIG_LIST -> {
                        log("carlife cmd rx: MD_FEATURE_CONFIG_LIST (0x00010051)")
                        cmdWriter?.write(CarLifeMessage.HU_FEATURE_CONFIG_RESPONSE_V2, CarLifeProto.encodeFeatureConfigList())
                    }
                    CarLifeMessage.MD_FOREGROUND -> {
                        cmdWriter?.write(CarLifeMessage.VEHICLE_FOREGROUND)
                        cmdWriter?.write(CarLifeMessage.GO_TO_FOREGROUND)
                        cmdWriter?.write(CarLifeMessage.VIDEO_ENCODER_START)
                    }
                    CarLifeMessage.MD_AUTHEN_RESULT, CarLifeMessage.MD_AUTHEN_RESPONSE -> {
                        cmdWriter?.write(CarLifeMessage.HU_AUTHEN_RESULT, byteArrayOf())
                        cmdWriter?.write(CarLifeMessage.VIDEO_ENCODER_START)
                        cmdWriter?.write(CarLifeMessage.GO_TO_FOREGROUND)
                    }
                    CarLifeMessage.VIDEO_ENCODER_INIT_DONE, CarLifeMessage.VIDEO_ENCODER_READY -> {
                        if (frame.payload.isNotEmpty()) {
                            val info = CarLifeProto.decodeVideoEncoderInfo(frame.payload)
                            val changed = (info.width > 0 && info.width != videoWidth) ||
                                (info.height > 0 && info.height != videoHeight)
                            if (info.width > 0) videoWidth = info.width
                            if (info.height > 0) videoHeight = info.height
                            log("carlife encoder re-confirmed mid-session: ${videoWidth}x${videoHeight} @${info.frameRate}")
                            if (changed && videoWidth > 0 && videoHeight > 0) {
                                onVideoSizeChanged?.invoke(videoWidth, videoHeight)
                            }
                        }
                        cmdWriter?.write(CarLifeMessage.VIDEO_ENCODER_START)
                        cmdWriter?.write(CarLifeMessage.GO_TO_FOREGROUND)
                        cmdWriter?.write(CarLifeMessage.VEHICLE_FOREGROUND)
                    }
                    CarLifeMessage.CARLIFE_MODULE_STATUS -> {
                        // ignore background status ticks
                    }
                    else -> {
                        log("carlife cmd rx: ${hex(frame.type)} (payload=${frame.payload.size}b)")
                    }
                }
            }
        }, "carlife-cmd-pump").apply { start() }

        heartbeatThread = Thread({
            var logoStallNudges = 0
            while (!closed) {
                try {
                    Thread.sleep(2000)
                    if (closed) break
                    cmdWriter?.write(CarLifeMessage.VEHICLE_FOREGROUND)
                    // If the phone only sent the initial splash logo frame (<= 1 frame) and stopped,
                    // re-trigger VIDEO_ENCODER_INIT / VIDEO_ENCODER_START so the phone binds its
                    // live screen encoder to the connected VIDEO socket.
                    if (receivedVideoDataFrames <= 1 && logoStallNudges < MAX_LOGO_STALL_NUDGES) {
                        logoStallNudges++
                        log("carlife: video stalled at $receivedVideoDataFrames frame(s) (hb=$receivedVideoHeartbeats); nudge #$logoStallNudges")
                        if (logoStallNudges >= 2) {
                            cmdWriter?.write(
                                CarLifeMessage.VIDEO_ENCODER_INIT,
                                CarLifeProto.encodeVideoEncoderInfo(requestedWidth, requestedHeight, requestedFrameRate),
                            )
                        }
                        cmdWriter?.write(CarLifeMessage.VIDEO_ENCODER_START)
                        cmdWriter?.write(CarLifeMessage.GO_TO_FOREGROUND)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!closed) log("carlife foreground ping error: ${e.message}")
                    break
                }
            }
        }, "carlife-foreground-ping").apply { start() }
    }

    private fun pumpMedia(reader: CarLifeFrameReader) {
        var mediaChunks = 0L
        while (!closed) {
            val frame = try {
                reader.read()
            } catch (e: Exception) {
                break
            } ?: break
            when (frame.type) {
                CarLifeMessage.MEDIA_INIT -> {
                    val info = decodeAudioInitInfo(frame.payload, defaultRate = 44100, defaultChannels = 2, defaultBits = 16)
                    log("carlife media audio init: ${info.sampleRate}Hz, ${info.channel}ch, ${info.sampleBits}bit")
                    mediaPlayer?.start(info.sampleRate, info.channel, info.sampleBits)
                }
                CarLifeMessage.MEDIA_DATA -> {
                    if (mediaPlayer?.isRunning != true) {
                        mediaPlayer?.start(44100, 2, 16)
                    }
                    if (frame.payload.isNotEmpty()) {
                        if (++mediaChunks == 1L) {
                            log("carlife media audio first chunk: ${frame.payload.size}B")
                            if (!tcpAudioActivated) {
                                tcpAudioActivated = true
                                onTcpAudioActive?.invoke()
                            }
                        }
                        mediaPlayer?.write(frame.payload, 0, frame.payload.size)
                    }
                }
                CarLifeMessage.MEDIA_STOP, CarLifeMessage.MEDIA_PAUSE -> {
                    mediaPlayer?.stop()
                }
            }
        }
    }

    private fun pumpTts(reader: CarLifeFrameReader) {
        var ttsChunks = 0L
        while (!closed) {
            val frame = try {
                reader.read()
            } catch (e: Exception) {
                break
            } ?: break
            when (frame.type) {
                CarLifeMessage.TTS_INIT -> {
                    val info = decodeAudioInitInfo(frame.payload, defaultRate = 16000, defaultChannels = 1, defaultBits = 16)
                    log("carlife tts audio init: ${info.sampleRate}Hz, ${info.channel}ch, ${info.sampleBits}bit")
                    ttsActive = true
                    mediaPlayer?.setDucking(true)
                    ttsPlayer?.start(info.sampleRate, info.channel, info.sampleBits)
                }
                CarLifeMessage.TTS_DATA -> {
                    if (!ttsActive) {
                        ttsActive = true
                        mediaPlayer?.setDucking(true)
                    }
                    if (ttsPlayer?.isRunning != true) {
                        ttsPlayer?.start(16000, 1, 16)
                    }
                    if (frame.payload.isNotEmpty()) {
                        if (++ttsChunks == 1L) {
                            log("carlife tts audio first chunk: ${frame.payload.size}B")
                            if (!tcpAudioActivated) {
                                tcpAudioActivated = true
                                onTcpAudioActive?.invoke()
                            }
                        }
                        ttsPlayer?.write(frame.payload, 0, frame.payload.size)
                    }
                }
                CarLifeMessage.TTS_STOP -> {
                    ttsPlayer?.stop()
                    ttsActive = false
                    mediaPlayer?.setDucking(focusDucked)
                }
            }
        }
    }

    private fun openChannel(channel: CarLifeChannel, timeoutMillis: Int): CarLifeStream? {
        val stream = try {
            transport.openChannel(channel, timeoutMillis)
        } catch (error: IOException) {
            log("carlife ${channel.name}: ${error.message}")
            null
        }
        if (stream == null && channel == CMD) {
            log("carlife: ${channel.name} channel refused — is the CarLife app running on the phone?")
        }
        return stream
    }

    /**
     * Reads the video channel until it ends, feeding complete Annex-B access units to [onNal].
     */
    fun pumpVideo(onNal: (ByteArray) -> Unit) {
        val reader = videoReader ?: return
        val splitter = AnnexBSplitter()
        while (true) {
            val frame = try {
                reader.read()
            } catch (error: IOException) {
                log("carlife video ended: ${error.message}")
                return
            } ?: return
            if (frame.type != CarLifeMessage.VIDEO_DATA) {
                if (frame.type == CarLifeMessage.VIDEO_HEARTBEAT) {
                    val hb = ++receivedVideoHeartbeats
                    if (hb == 1L || hb % 10L == 0L) {
                        log("carlife video rx VIDEO_HEARTBEAT #$hb")
                    }
                } else {
                    log("carlife video rx non-data: ${hex(frame.type)} len=${frame.payload.size}")
                }
                continue
            }
            val payload = frame.payload
            if (payload.isEmpty()) continue
            receivedVideoDataFrames++
            if (startsWithAnnexBStartCode(payload)) {
                // CarLife VIDEO_DATA packets are already framed as complete Annex-B access units
                // (e.g. SPS+PPS, SPS+PPS+IDR, or P-frame). Forwarding directly avoids both the
                // 1-frame holdback in AnnexBSplitter and splitting SPS/PPS away from their IDR.
                onNal(payload)
            } else {
                for (nal in splitter.feed(payload)) onNal(AnnexB.withStartCode(nal))
            }
        }
    }

    private fun startsWithAnnexBStartCode(data: ByteArray): Boolean =
        (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1) ||
            (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1)

    /** Sends one touch gesture, already mapped onto the encoded frame. Safe to call from the UI thread. */
    fun sendTouch(gesture: CarLifeTouchGesture, xInFrame: Int, yInFrame: Int) {
        if (closed) return
        val writer = ctrlWriter ?: return
        val type = CarLifeMessage.TOUCH_BY_GESTURE.getValue(gesture)
        val payload = CarLifeTouch.encode(xInFrame, yInFrame)
        sendControl {
            if (!closed) {
                try {
                    writer.write(type, payload)
                } catch (e: Exception) {
                    if (!closed) log("carlife touch send error: ${e.message}")
                }
            }
        }
    }

    /**
     * Sends a compound multi-touch action (`TOUCH_ACTION` `0x00068001`) carrying an Android
     * `MotionEvent` action code (with pointer index in high byte) and frame coordinates.
     */
    fun sendTouchAction(action: Int, xInFrame: Int, yInFrame: Int) {
        if (closed) return
        val writer = ctrlWriter ?: return
        val payload = CarLifeTouch.encodeAction(action, xInFrame, yInFrame)
        sendControl {
            if (!closed) {
                try {
                    writer.write(CarLifeMessage.TOUCH_ACTION, payload)
                } catch (e: Exception) {
                    if (!closed) log("carlife multi-touch send error: ${e.message}")
                }
            }
        }
    }

    /**
     * Synthesizes a quick single-finger double-tap at `(xInFrame, yInFrame)` using standard
     * `TOUCH_ACTION_DOWN` / `TOUCH_ACTION_UP` events so map apps (e.g. Baidu Map) zoom in.
     */
    fun sendDoubleTap(xInFrame: Int, yInFrame: Int) {
        if (closed) return
        val writer = ctrlWriter ?: return
        val payload = CarLifeTouch.encode(xInFrame, yInFrame)
        sendControl {
            if (!closed) {
                try {
                    writer.write(CarLifeMessage.TOUCH_ACTION_DOWN, payload)
                    Thread.sleep(25)
                    writer.write(CarLifeMessage.TOUCH_ACTION_UP, payload)
                    Thread.sleep(45)
                    writer.write(CarLifeMessage.TOUCH_ACTION_DOWN, payload)
                    Thread.sleep(25)
                    writer.write(CarLifeMessage.TOUCH_ACTION_UP, payload)
                } catch (e: Exception) {
                    if (!closed) log("carlife double-tap send error: ${e.message}")
                }
            }
        }
    }

    /**
     * Synthesizes a single-finger double-tap-and-drag-up gesture at `(xInFrame, yInFrame)`
     * to zoom out on map apps (Baidu Map / Amap / Google Maps) over CarLife's single-point
     * control channel.
     */
    fun sendDoubleTapDragZoomOut(xInFrame: Int, yInFrame: Int, frameHeight: Int) {
        if (closed) return
        val writer = ctrlWriter ?: return
        val dragDistance = (frameHeight * 0.22f).toInt().coerceIn(120, 260)
        val startY = yInFrame.coerceIn(dragDistance + 16, (frameHeight - 16).coerceAtLeast(dragDistance + 16))
        val endY = (startY - dragDistance).coerceAtLeast(8)
        sendControl {
            if (!closed) {
                try {
                    val startPayload = CarLifeTouch.encode(xInFrame, startY)
                    // First tap
                    writer.write(CarLifeMessage.TOUCH_ACTION_DOWN, startPayload)
                    Thread.sleep(25)
                    writer.write(CarLifeMessage.TOUCH_ACTION_UP, startPayload)
                    Thread.sleep(50)
                    // Second tap + smooth drag upward to zoom out
                    writer.write(CarLifeMessage.TOUCH_ACTION_DOWN, startPayload)
                    Thread.sleep(25)
                    val steps = 8
                    for (step in 1..steps) {
                        if (closed) return@sendControl
                        val curY = startY - ((startY - endY) * step) / steps
                        writer.write(CarLifeMessage.TOUCH_ACTION_MOVE, CarLifeTouch.encode(xInFrame, curY))
                        Thread.sleep(20)
                    }
                    writer.write(CarLifeMessage.TOUCH_ACTION_UP, CarLifeTouch.encode(xInFrame, endY))
                } catch (e: Exception) {
                    if (!closed) log("carlife double-tap-drag zoom-out error: ${e.message}")
                }
            }
        }
    }

    /** Sends a hard key, e.g. HOME or BACK, when the driver uses the dash buttons. */
    fun sendHardKey(keyCode: Int) {
        if (closed) return
        val writer = ctrlWriter ?: return
        val payload = CarLifeProto.encodeHardKeyCode(keyCode)
        sendControl {
            if (!closed) {
                try {
                    writer.write(CarLifeMessage.TOUCH_CAR_HARD_KEY_CODE, payload)
                } catch (e: Exception) {
                    if (!closed) log("carlife hardkey send error: ${e.message}")
                }
            }
        }
    }

    /** Asks the phone to pause its encoder; used when the head unit loses the foreground. */
    fun pauseVideo() {
        cmdWriter?.write(CarLifeMessage.VIDEO_ENCODER_PAUSE)
    }

    /** Asks the phone to reset and restart its video encoder to produce fresh SPS/PPS + IDR. */
    fun requestVideoReset() {
        if (closed) return
        sendControl {
            if (!closed) {
                try {
                    log("carlife: requesting video encoder reset + start")
                    cmdWriter?.write(CarLifeMessage.VIDEO_ENCODER_RESET)
                    cmdWriter?.write(CarLifeMessage.VIDEO_ENCODER_START)
                    cmdWriter?.write(CarLifeMessage.GO_TO_FOREGROUND)
                    cmdWriter?.write(CarLifeMessage.VEHICLE_FOREGROUND)
                } catch (e: Exception) {
                    if (!closed) log("carlife video reset error: ${e.message}")
                }
            }
        }
    }

    private fun sendControl(action: () -> Unit) {
        try {
            ctrlExecutor.execute(action)
        } catch (_: RejectedExecutionException) {
            // The phone disconnected between the caller's state check and enqueue.
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        ctrlExecutor.shutdownNow()
        heartbeatThread?.interrupt()
        cmdPumpThread?.interrupt()
        mediaPumpThread?.interrupt()
        ttsPumpThread?.interrupt()
        heartbeatThread = null
        cmdPumpThread = null
        mediaPumpThread = null
        ttsPumpThread = null
        videoWriter = null

        mediaPlayer?.stop()
        ttsPlayer?.stop()
        mediaPlayer = null
        ttsPlayer = null

        runCatching { cmdStream?.close() }
        runCatching { videoStream?.close() }
        runCatching { ctrlStream?.close() }
        runCatching { mediaStream?.close() }
        runCatching { ttsStream?.close() }
        runCatching { vrStream?.close() }
        cmdStream = null
        videoStream = null
        ctrlStream = null
        mediaStream = null
        ttsStream = null
        vrStream = null
        runCatching { transport.close() }
    }

    private fun hex(value: Int) = "0x" + value.toUInt().toString(16).padStart(8, '0')

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 8_000
        const val HANDSHAKE_READ_SLICE_MS = 1_000
        const val VERSION_RETRY_DELAY_MS = 2_500L
        const val ENCODER_READY_WAIT_MS = 3_500
        const val MAX_LOGO_STALL_NUDGES = 5
    }
}
