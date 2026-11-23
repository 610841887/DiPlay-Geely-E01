package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.view.Surface
import com.shilapi.xcertplay.compat.realSdkInt
import com.shilapi.xcertplay.aap.AapAudioStream
import com.shilapi.xcertplay.aap.AapAudioStreamType
import com.shilapi.xcertplay.aap.AapHeadUnitInfo
import com.shilapi.xcertplay.aap.AapIdentity
import com.shilapi.xcertplay.aap.AapInputMessages
import com.shilapi.xcertplay.aap.AapMediaMessages
import com.shilapi.xcertplay.aap.AapMessage
import com.shilapi.xcertplay.aap.AapMessageIds
import com.shilapi.xcertplay.aap.AapMicrophoneSpec
import com.shilapi.xcertplay.aap.AapResolution
import com.shilapi.xcertplay.aap.AapSensorMessages
import com.shilapi.xcertplay.aap.AapSensorType
import com.shilapi.xcertplay.aap.AapServiceSet
import com.shilapi.xcertplay.aap.AapSession
import com.shilapi.xcertplay.aap.AapTouchScreen
import com.shilapi.xcertplay.aap.AapVideoConfig
import com.shilapi.xcertplay.aap.AapTransport
import com.shilapi.xcertplay.aap.TcpTransport
import com.shilapi.xcertplay.host.R
import java.util.concurrent.Executors

/**
 * Runs one Android Auto session end to end on the head unit: connect, negotiate, decode video, play
 * audio, send input back.
 *
 * Split out of the activity so the activity is only a view. Everything here happens on a worker
 * thread except [sendTouch] and [setSurface], which are called from the UI thread and hand off.
 *
 * The media-channel handshake is the part that is easy to get subtly wrong, so it is spelled out:
 *
 * ```
 * phone sends SETUP          -> we answer CONFIG (READY, window, which configs we accept)
 * phone sends START          -> we remember its session id and the config it chose
 * phone sends CODEC_CONFIG   -> straight to the decoder, it carries SPS and PPS
 * phone sends DATA           -> decode, then ACK or the phone stops after `max_unacked` frames
 * ```
 *
 * An unacknowledged frame is not a lost frame: the phone stops sending entirely once the window
 * fills, which from the outside looks like a frozen picture rather than a stalled protocol.
 */
class AapProjectionController(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onStatus(text: String)
        fun onVideoSize(width: Int, height: Int)

        /** The phone has opened its video channel; frames have not arrived yet. */
        fun onReady()

        /**
         * The first frame has actually been drawn.
         *
         * Kept separate from [onReady] on purpose: hiding the diagnostics overlay when the channel
         * opens leaves a black screen with no explanation on it when the stream then fails to
         * decode, which is the one moment the overlay is worth reading.
         */
        fun onFirstFrame() {}

        /** What the phone called itself, once it has said. Shown on the car's waiting screen. */
        fun onPhone(name: String) {}

        fun onEnded(reason: String)
    }

    /** The screen this unit is projecting onto, which decides the one video configuration offered. */
    private val screenWidth: Int = context.resources.displayMetrics.widthPixels
    private val screenHeight: Int = context.resources.displayMetrics.heightPixels

    /**
     * The single video configuration the phone may use.
     *
     * Exactly one on purpose. The touch panel is advertised in this coordinate space, and the
     * phone maps the car's touches from that space onto whatever configuration it picked — two
     * configurations would mean two coordinate systems on one touchscreen, and every tap would
     * land somewhere between them. A 1024x600 panel therefore picks 1280x720 and letterboxes,
     * which is also what an aftermarket unit does.
     */
    // Legacy head units (e.g. Allwinner T3 / API <= 21) or compact displays default to 800x480@30fps
    // to protect weaker hardware H.264 decoders from OOM or stalls.
    private val videoConfig: AapVideoConfig =
        if (realSdkInt() > Build.VERSION_CODES.LOLLIPOP && maxOf(screenWidth, screenHeight) > 1024) {
            AapVideoConfig(AapResolution.P1280x720, frameRate = 30, density = 160)
        } else {
            AapVideoConfig(AapResolution.P800x480, frameRate = 30, density = 160)
        }

    /**
     * What the head unit offers.
     *
     * Three audio sinks, because Android Auto keeps them apart and a car that mixes them loses the
     * one thing the split is for: navigation prompts stay audible while music ducks. The
     * microphone is what lets the phone's assistant listen through the car.
     */
    val services: AapServiceSet = AapServiceSet(
        displayName = "DiPlay",
        video = listOf(videoConfig),
        audio = listOf(
            AapAudioStream(AapAudioStreamType.MEDIA, sampleRate = 48_000, channels = 2),
            AapAudioStream(AapAudioStreamType.GUIDANCE, sampleRate = 16_000, channels = 1),
            AapAudioStream(AapAudioStreamType.SYSTEM, sampleRate = 16_000, channels = 1),
        ),
        touchscreen = AapTouchScreen(videoConfig.resolution.width, videoConfig.resolution.height),
        sensors = listOf(AapSensorType.NIGHT_MODE, AapSensorType.DRIVING_STATUS),
        microphone = AapMicrophoneSpec(),
        headUnit = AapHeadUnitInfo(
            make = "DiPlay",
            model = "Android Auto head unit",
            year = "2026",
            headUnitMake = "DiPlay",
            headUnitModel = "DiPlay",
            softwareVersion = "1",
        ),
        navigationStatus = true,
        mediaPlayback = true,
        pingIntervalMs = 1_000,
        pingTimeoutMs = 5_000,
    )

    private val identity: AapIdentity by lazy { AapIdentityStore.load(context) }
    private val decoder = AapVideoDecoder(
        log = { note(it) },
        onFirstFrame = {
            AapDiagnostics.updateSessionMetrics(resolution = "${videoWidth}x${videoHeight}")
            listener.onFirstFrame()
        },
        onStallDetected = {
            sendFromUi("keyframe recovery") { target ->
                target.send(
                    services.videoChannel,
                    AapMessageIds.Media.VIDEO_FOCUS_NOTIFICATION,
                    AapMediaMessages.encodeVideoFocusNotification(unsolicited = true),
                )
            }
        },
    )

    /** One player per audio sink: the three streams run at different rates and must not share one. */
    private val audioPlayers = HashMap<Int, AapAudioPlayer>()
    @Volatile
    private var guidanceActive = false
    @Volatile
    private var focusDucked = false
    private val audioFocus = AapAudioFocusHelper(
        context = context,
        onDuckChanged = { duck ->
            focusDucked = duck
            updateMediaDucking()
        },
        onMuteChanged = { mute ->
            synchronized(audioPlayers) {
                audioPlayers.values.filter { it.role == AapAudioPlayer.AudioRole.MEDIA }
                    .forEach { it.setMuted(mute) }
            }
        },
        log = { note(it) },
    )
    private val microphone = AapMicrophone { note(it) }
    private var microphoneSession = 0

    @Volatile
    private var session: AapSession? = null
    @Volatile
    private var stopping = false
    private var worker: Thread? = null
    private val transportLock = Any()
    private var transport: AapTransport? = null

    /** Single thread for sends that start on the UI thread; see [sendFromUi]. */
    private val sender = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aap-send").apply { isDaemon = true }
    }

    private var videoSessionId = 0
    private val audioSessionIds = HashMap<Int, Int>()
    private var videoConfigIndex = 0
    private var nightMode = false
    private var drivingStatus = 0

    /** Frames seen on the video channel, for the throttled progress line. */
    private var dataFrames = 0

    /** Size the phone announced in its START, which is what touch coordinates are relative to. */
    @Volatile
    var videoWidth: Int = videoConfig.resolution.width
        private set

    @Volatile
    var videoHeight: Int = videoConfig.resolution.height
        private set

    val isRunning: Boolean get() = session?.isOpen == true

    /** Fingerprint the phone will pin, shown so a driver can compare it on the phone's prompt. */
    val fingerprint: String get() = identity.fingerprint

    // ------------------------------------------------------------------ lifecycle

    /**
     * Every status line goes through the activity's resources.
     *
     * The strings used to be Chinese literals here while the surrounding waiting panel came from
     * `aa_desktop_*`, so a unit set to English showed a Chinese status row above English help
     * text. A controller that owns the session still owns the wording, but not the language.
     */
    private fun say(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun connectUsb() = startWorker("aap-usb") {
        serve(AndroidAutoUsb(context, ::notify).connect())
    }

    fun connect(host: String, port: Int = TcpTransport.DEFAULT_PORT) = startWorker("aap-connect") {
        notify(say(R.string.aa_status_dialing, host, port))
        serve(TcpTransport.connect(host, port))
    }

    private fun serve(existing: AapTransport) {
        synchronized(transportLock) {
            if (stopping) {
                existing.close()
                return
            }
            transport = existing
        }
        val created = AapSession(
            transport = existing,
            identity = identity,
            services = services,
            log = { note(it) },
        ).apply { listener = this@AapProjectionController.sessionListener }
        session = created
        try {
            if (stopping) return
            notify(say(R.string.aa_status_negotiating))
            if (created.connect()) {
                notify(say(R.string.aa_status_waiting_projection))
                while (!stopping && created.pump()) {
                    // Projection messages flow through the listener.
                }
            }
        } finally {
            created.close()
            session = null
        }
        if (!stopping) listener.onEnded(lastFailure)
    }

    fun setSurface(surface: Surface?) {
        decoder.setSurface(surface)
    }

    /** Sends a touch in **video** coordinates; the activity has already removed the letterbox. */
    fun sendTouch(x: Int, y: Int, action: Int) {
        sendFromUi("touch") { target ->
            // Down and up only: a drag would otherwise put a line in the log per pixel it crosses.
            if (action != AapInputMessages.Action.MOVED) note("touch action=$action at $x,$y")
            target.sendTouch(System.nanoTime() / 1_000, x, y, action)
        }
    }

    /** Sends a key the car's own hardware produced (steering wheel, panel buttons). */
    fun sendKey(keycode: Int, down: Boolean) {
        sendFromUi("key $keycode") { target -> target.sendKey(System.nanoTime() / 1_000, keycode, down) }
    }

    fun setNightMode(night: Boolean) {
        nightMode = night
        sendFromUi("night mode") { target -> target.sendNightMode(night) }
    }

    /**
     * Queues a send that originates on the UI thread.
     *
     * Writing to the socket straight from a touch handler throws `NetworkOnMainThreadException`
     * under StrictMode, and that exception surfaces inside the session as a write failure — which
     * closes the whole session on the driver's first tap. One thread also keeps the order of the
     * events intact, which matters for a drag.
     */
    private fun sendFromUi(what: String, body: (AapSession) -> Unit) {
        val target = session
        if (target == null) {
            note("$what dropped: no session")
            return
        }
        if (stopping) return
        try {
            sender.execute {
                if (!stopping) runCatching { body(target) }
                    .onFailure { note("$what failed: ${it.javaClass.simpleName}") }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The projection was stopped between enqueue and submission.
        }
    }

    fun stop() {
        val current = synchronized(transportLock) {
            stopping = true
            transport.also { transport = null }
        }
        worker?.interrupt()
        runCatching { current?.close() }
        sender.shutdownNow()
        decoder.close()
        microphone.stop()
        stopAllAudio()
    }

    // ------------------------------------------------------------------ session callbacks

    private val sessionListener = object : AapSession.Listener {
        override fun onState(state: AapSession.State) {
            // The waiting screen says "negotiating" and stays there: a status row that flips
            // through four protocol stages reads as noise on a head unit. The stages stay in the
            // diagnostics log, where they are actually useful.
            when (state) {
                AapSession.State.VERSION -> note("stage: protocol version")
                AapSession.State.HANDSHAKE -> note("stage: TLS handshake")
                AapSession.State.AUTH -> note("stage: phone certificate")
                AapSession.State.DISCOVERY -> note("stage: capability discovery")
                AapSession.State.RUNNING -> note("stage: channels up")
                else -> Unit
            }
        }

        override fun onPhoneDiscovered(request: com.shilapi.xcertplay.aap.AapControlMessages.ServiceDiscoveryRequest) {
            notify(say(R.string.aa_desktop_phone_line, request.display))
            listener.onPhone(request.display)
        }

        override fun onReady(session: AapSession) {
            // Night mode first: the phone decides its palette from this before it draws anything.
            session.sendNightMode(nightMode)
            session.sendDrivingStatus(drivingStatus)
            AapDiagnostics.recordSessionStart("Android Auto", "${videoWidth}x${videoHeight}", "H.264")
            notify(say(R.string.aa_status_running_waiting_video))
        }

        override fun onServiceMessage(channel: Int, message: AapMessage) {
            when {
                channel == services.videoChannel -> onVideoMessage(message)
                channel == services.inputChannel -> onInputMessage(message)
                channel == services.sensorChannel -> onSensorMessage(message)
                services.microphone != null && channel == services.microphoneChannel -> onMicrophoneMessage(message)
                services.audioChannels.containsKey(channel) -> onAudioMessage(channel, message)
                // Navigation status and now-playing are one-way: the phone reports, the car only
                // has to keep the channel open. A head unit with an instrument cluster would draw
                // them; this one has no second display, so the messages are counted and dropped.
                channel == services.navigationChannel -> note("navigation status ${message.body.size}B")
                channel == services.playbackChannel -> note("now playing ${message.body.size}B")
                else -> note("message on unknown channel $channel")
            }
        }

        override fun onClosed(reason: String, cause: Throwable?) {
            lastFailure = reason
            note("closed: $reason")
            val (played, droppedAudio) = synchronized(audioPlayers) {
                audioPlayers.values.sumOf { it.playedChunks } to audioPlayers.values.sumOf { it.droppedChunks }
            }
            AapDiagnostics.updateSessionMetrics(
                resolution = "${videoWidth}x${videoHeight}",
                renderedFrames = decoder.renderedFrames,
                droppedVideoFrames = decoder.dropped.toLong(),
                decoderResets = decoder.stallRecoveries,
                playedAudioChunks = played,
                droppedAudioChunks = droppedAudio,
            )
            AapDiagnostics.recordSessionEnd(reason)
        }
    }

    // ------------------------------------------------------------------ channels

    private fun onVideoMessage(message: AapMessage) {
        when (message.id) {
            AapMessageIds.Media.SETUP -> {
                val codec = AapMediaMessages.decodeSetupCodec(message.body)
                note("video SETUP codec=$codec")
                val config = AapMediaMessages.encodeConfig(
                    maxUnacked = MAX_UNACKED,
                    configurationIndices = services.video.indices.toList(),
                )
                session?.send(services.videoChannel, AapMessageIds.Media.CONFIG, config)
            }

            AapMessageIds.Media.START -> {
                val start = AapMediaMessages.decodeStart(message.body)
                videoSessionId = start.sessionId
                videoConfigIndex = start.configurationIndex.coerceIn(0, services.video.lastIndex)
                val resolution = services.video[videoConfigIndex].resolution
                videoWidth = resolution.width
                videoHeight = resolution.height
                note("video START $start at ${resolution.width}x${resolution.height}")
                decoder.setVideoSize(videoWidth, videoHeight)
                listener.onVideoSize(videoWidth, videoHeight)
                notify(say(R.string.aa_status_video_size, resolution.width, resolution.height))
                // The phone asked for focus by now in most builds; answering here as well is
                // harmless and covers the ones that wait for the car to speak first.
                session?.sendVideoFocus(AapMediaMessages.VideoFocusMode.PROJECTED)
                listener.onReady()
            }

            AapMessageIds.Media.STOP -> {
                note("video STOP")
                videoSessionId = 0
            }

            AapMessageIds.Media.CODEC_CONFIG ->
                decoder.setCodecConfig(message.body, 0, message.body.size)

            AapMessageIds.Media.DATA -> {
                if (message.body.size <= AapMediaMessages.DATA_HEADER) return
                val timestamp = AapMediaMessages.dataTimestamp(message.body)
                decoder.queue(
                    message.body,
                    AapMediaMessages.DATA_HEADER,
                    message.body.size - AapMediaMessages.DATA_HEADER,
                    timestamp,
                )
                // One line every thirty frames: enough to tell "no frames" from "frames that never
                // reach the screen" without turning a 20 fps stream into a log flood.
                if (++dataFrames % 30 == 1) {
                    note("video frame #$dataFrames ${message.body.size}B dropped=${decoder.dropped}")
                }
                // One ack per frame: the phone's window is what keeps it sending, and the ack also
                // tells it how far behind the car is.
                session?.ackVideo(videoSessionId, 1)
            }

            AapMessageIds.Media.VIDEO_FOCUS_REQUEST -> {
                val mode = AapMediaMessages.decodeVideoFocusRequestMode(message.body)
                note("phone wants video focus mode=$mode")
                session?.sendVideoFocus(
                    if (mode == AapMediaMessages.VideoFocusMode.NATIVE) {
                        AapMediaMessages.VideoFocusMode.NATIVE
                    } else {
                        AapMediaMessages.VideoFocusMode.PROJECTED
                    },
                )
            }

            AapMessageIds.Media.VIDEO_FOCUS_NOTIFICATION -> {
                val focus = AapMediaMessages.decodeVideoFocusNotification(message.body)
                note("video focus $focus")
                if (focus.projected) {
                    // Focus coming back means the phone will start again from a key frame.
                    decoder.reset()
                    listener.onReady()
                }
            }

            AapMessageIds.Media.ACK -> Unit
            else -> note("video 0x${message.id.toString(16)} ignored")
        }
    }

    private fun onAudioMessage(channel: Int, message: AapMessage) {
        val stream = services.audioChannels[channel] ?: return
        when (message.id) {
            AapMessageIds.Media.SETUP -> {
                note("audio SETUP on $channel (${stream.type})")
                session?.send(
                    channel,
                    AapMessageIds.Media.CONFIG,
                    AapMediaMessages.encodeConfig(maxUnacked = MAX_UNACKED, configurationIndices = listOf(0)),
                )
            }

            AapMessageIds.Media.START -> {
                val start = AapMediaMessages.decodeStart(message.body)
                audioSessionIds[channel] = start.sessionId
                if (stream.type == AapAudioStreamType.MEDIA) {
                    audioFocus.requestFocus()
                } else if (stream.type == AapAudioStreamType.GUIDANCE) {
                    guidanceActive = true
                    updateMediaDucking()
                }
                playerFor(channel).start(stream.sampleRate, stream.channels, stream.bitDepth)
                note("audio START $start on $channel (${stream.type})")
            }

            AapMessageIds.Media.STOP -> {
                audioSessionIds.remove(channel)
                playerFor(channel).stop()
                if (stream.type == AapAudioStreamType.GUIDANCE) {
                    guidanceActive = false
                    updateMediaDucking()
                }
                note("audio STOP on $channel (${stream.type})")
            }

            AapMessageIds.Media.DATA -> {
                if (message.body.size <= AapMediaMessages.DATA_HEADER) return
                playerFor(channel).write(
                    message.body,
                    AapMediaMessages.DATA_HEADER,
                    message.body.size - AapMediaMessages.DATA_HEADER,
                )
                session?.send(
                    channel,
                    AapMessageIds.Media.ACK,
                    AapMediaMessages.encodeAck(audioSessionIds[channel] ?: 0, 1),
                )
            }

            // The car's mixer ran dry: the phone refills a little ahead next time.
            AapMessageIds.Media.AUDIO_UNDERFLOW_NOTIFICATION ->
                note("audio underflow on $channel")

            else -> note("audio 0x${message.id.toString(16)} ignored")
        }
    }

    /**
     * The phone wants to record from the car.
     *
     * `SETUP`/`CONFIG` first, then `MicrophoneRequest{open}`. A close must not stop a capture the
     * next request has already restarted, so the answer echoes the session id the phone was given
     * rather than a fresh one.
     */
    private fun onMicrophoneMessage(message: AapMessage) {
        val spec = services.microphone ?: return
        when (message.id) {
            AapMessageIds.Media.SETUP -> {
                note("microphone SETUP")
                session?.send(
                    services.microphoneChannel,
                    AapMessageIds.Media.CONFIG,
                    AapMediaMessages.encodeConfig(maxUnacked = 1, configurationIndices = listOf(0)),
                )
            }

            AapMessageIds.Media.MICROPHONE_REQUEST -> {
                val open = AapMediaMessages.decodeMicrophoneRequestOpen(message.body)
                if (open) {
                    microphoneSession = (microphoneSession + 1).coerceAtLeast(1)
                    session?.send(
                        services.microphoneChannel,
                        AapMessageIds.Media.MICROPHONE_RESPONSE,
                        AapMediaMessages.encodeMicrophoneResponse(microphoneSession, status = 0),
                    )
                    microphone.start(spec.sampleRate, spec.channels, spec.bitDepth) { data, length ->
                        sendMicrophoneChunk(data, length)
                    }
                    note("microphone opened as session $microphoneSession")
                } else {
                    microphone.stop()
                    session?.send(
                        services.microphoneChannel,
                        AapMessageIds.Media.MICROPHONE_RESPONSE,
                        AapMediaMessages.encodeMicrophoneResponse(microphoneSession, status = 0),
                    )
                    note("microphone closed")
                }
            }

            else -> note("microphone 0x${message.id.toString(16)} ignored")
        }
    }

    /** Forwards one captured chunk with the 8-byte timestamp every DATA body starts with. */
    private fun sendMicrophoneChunk(data: ByteArray, length: Int) {
        val target = session ?: return
        val body = ByteArray(AapMediaMessages.DATA_HEADER + length)
        val timestamp = System.nanoTime() / 1_000
        for (i in 0 until AapMediaMessages.DATA_HEADER) {
            body[i] = (timestamp ushr (56 - 8 * i)).toByte()
        }
        System.arraycopy(data, 0, body, AapMediaMessages.DATA_HEADER, length)
        sender.execute {
            runCatching { target.send(services.microphoneChannel, AapMessageIds.Media.DATA, body) }
        }
    }

    private fun onInputMessage(message: AapMessage) {
        when (message.id) {
            AapMessageIds.Input.KEY_BINDING_REQUEST -> {
                // Status 0, no keys re-bound: the car sends Android key codes directly.
                session?.send(
                    services.inputChannel,
                    AapMessageIds.Input.KEY_BINDING_RESPONSE,
                    AapInputMessages.encodeKeyBindingResponse(),
                )
                note("key binding accepted")
            }

            AapMessageIds.Input.INPUT_FEEDBACK -> Unit
            else -> note("input 0x${message.id.toString(16)} ignored")
        }
    }

    private fun onSensorMessage(message: AapMessage) {
        if (message.id != AapMessageIds.Sensor.REQUEST) {
            note("sensor 0x${message.id.toString(16)} ignored")
            return
        }
        val request = AapSensorMessages.decodeRequest(message.body) ?: return
        val body = when (request.type) {
            AapSensorType.NIGHT_MODE -> AapSensorMessages.encodeBatch(nightMode = nightMode)
            AapSensorType.DRIVING_STATUS -> AapSensorMessages.encodeBatch(drivingStatus = drivingStatus)
            else -> null
        }
        if (body == null) {
            // A sensor this unit does not have. Saying so is what stops the phone waiting for a
            // first value that would never come.
            session?.send(
                services.sensorChannel,
                AapMessageIds.Sensor.ERROR,
                AapSensorMessages.encodeResponse(AapSensorMessages.SENSOR_NOT_SUPPORTED),
            )
            return
        }
        session?.send(
            services.sensorChannel,
            AapMessageIds.Sensor.RESPONSE,
            AapSensorMessages.encodeResponse(0),
        )
        session?.send(services.sensorChannel, AapMessageIds.Sensor.BATCH, body)
    }

    // ------------------------------------------------------------------ plumbing

    private fun playerFor(channel: Int): AapAudioPlayer = synchronized(audioPlayers) {
        audioPlayers.getOrPut(channel) {
            val role = when (services.audioChannels[channel]?.type) {
                AapAudioStreamType.GUIDANCE -> AapAudioPlayer.AudioRole.GUIDANCE
                AapAudioStreamType.SYSTEM -> AapAudioPlayer.AudioRole.SYSTEM
                else -> AapAudioPlayer.AudioRole.MEDIA
            }
            AapAudioPlayer(role = role) { note(it) }
        }
    }

    private fun updateMediaDucking() {
        val shouldDuck = guidanceActive || focusDucked
        synchronized(audioPlayers) {
            audioPlayers.values.filter { it.role == AapAudioPlayer.AudioRole.MEDIA }
                .forEach { it.setDucking(shouldDuck) }
        }
    }

    private fun stopAllAudio() {
        guidanceActive = false
        focusDucked = false
        audioFocus.abandonFocus()
        synchronized(audioPlayers) {
            for (player in audioPlayers.values) player.stop()
            audioPlayers.clear()
        }
        audioSessionIds.clear()
    }

    /** Reason for the last failure, so the UI can say something better than "disconnected". */
    @Volatile
    var lastFailure: String = context.getString(R.string.aa_status_not_connected)
        private set

    private fun startWorker(name: String, body: () -> Unit) {
        check(worker == null && !stopping)
        worker = Thread({
            try {
                body()
            } catch (e: Exception) {
                if (!stopping) {
                    note("worker failed: ${e.message}")
                    lastFailure = e.message ?: context.getString(R.string.aa_error_connect_failed)
                    listener.onEnded(lastFailure)
                }
            } finally {
                stop()
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    private fun notify(text: String) {
        AapDiagnostics.log("ui", text)
        if (!stopping) listener.onStatus(text)
    }

    private fun note(line: String) {
        AapDiagnostics.log("aap", line)
    }

    companion object {
        /**
         * How many frames the phone may send before an ack. Four bounds the latency the decoder can
         * accumulate to about a tenth of a second at 30 fps.
         */
        const val MAX_UNACKED = 4

    }
}
