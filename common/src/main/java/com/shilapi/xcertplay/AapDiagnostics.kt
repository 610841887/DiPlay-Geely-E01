package com.shilapi.xcertplay

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Protocol log and structured session telemetry for Android Auto (AAP) and CarLife projections.
 *
 * Records protocol lines, session duration, negotiated resolution, codec format, video drops,
 * and audio underrun/drop counts so [DiPlayActivity.buildDiagnosticReport] can include a
 * privacy-redacted Android mirroring section.
 */
object AapDiagnostics {

    private const val TAG = "DiPlayAap"
    private const val CAPACITY = 400

    data class SessionSnapshot(
        val protocol: String = "none",
        val state: String = "idle",
        val startedAtMs: Long = 0L,
        val endedAtMs: Long = 0L,
        val resolution: String = "none",
        val codec: String = "H.264",
        val renderedFrames: Long = 0L,
        val droppedVideoFrames: Long = 0L,
        val decoderResets: Int = 0,
        val playedAudioChunks: Long = 0L,
        val droppedAudioChunks: Long = 0L,
        val lastError: String? = null,
    ) {
        fun durationSeconds(nowMs: Long = System.currentTimeMillis()): Long {
            if (startedAtMs <= 0L) return 0L
            val end = if (endedAtMs > 0L) endedAtMs else nowMs
            return ((end - startedAtMs).coerceAtLeast(0L)) / 1000L
        }
    }

    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var session = SessionSnapshot()

    fun log(scope: String, message: String) {
        val line = "${clock.format(Date())} $scope: $message"
        runCatching { Log.i(TAG, line) }
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    fun recordSessionStart(protocol: String, resolution: String, codec: String = "H.264") {
        synchronized(lock) {
            session = SessionSnapshot(
                protocol = protocol,
                state = "active",
                startedAtMs = System.currentTimeMillis(),
                endedAtMs = 0L,
                resolution = resolution,
                codec = codec,
            )
        }
    }

    fun updateSessionMetrics(
        resolution: String? = null,
        renderedFrames: Long? = null,
        droppedVideoFrames: Long? = null,
        decoderResets: Int? = null,
        playedAudioChunks: Long? = null,
        droppedAudioChunks: Long? = null,
    ) {
        synchronized(lock) {
            session = session.copy(
                resolution = resolution ?: session.resolution,
                renderedFrames = renderedFrames ?: session.renderedFrames,
                droppedVideoFrames = droppedVideoFrames ?: session.droppedVideoFrames,
                decoderResets = decoderResets ?: session.decoderResets,
                playedAudioChunks = playedAudioChunks ?: session.playedAudioChunks,
                droppedAudioChunks = droppedAudioChunks ?: session.droppedAudioChunks,
            )
        }
    }

    fun recordSessionEnd(reason: String? = null) {
        synchronized(lock) {
            if (session.protocol == "none") return
            session = session.copy(
                state = if (reason.isNullOrBlank()) "stopped" else "disconnected",
                endedAtMs = System.currentTimeMillis(),
                lastError = reason?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun sessionSnapshot(): SessionSnapshot = synchronized(lock) { session }

    /** The most recent lines, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) {
        lines.clear()
        session = SessionSnapshot()
    }

    /** Formats a redacted diagnostic block for [DiPlayActivity.buildDiagnosticReport]. */
    fun diagnosticReport(maxLogLines: Int = 60): String = buildString {
        val s = sessionSnapshot()
        appendLine("protocol=${s.protocol} state=${s.state} durationSec=${s.durationSeconds()}")
        appendLine("resolution=${s.resolution} codec=${s.codec}")
        appendLine(
            "videoRendered=${s.renderedFrames} videoDropped=${s.droppedVideoFrames} " +
                "decoderResets=${s.decoderResets} audioPlayed=${s.playedAudioChunks} " +
                "audioDropped=${s.droppedAudioChunks}",
        )
        s.lastError?.let { err ->
            DiagnosticRedactor.redact(err)?.let { appendLine("lastError=$it") }
        }
        val recent = snapshot().takeLast(maxLogLines)
        if (recent.isEmpty()) {
            appendLine("recentLog=none")
        } else {
            appendLine("recentLog (${recent.size} lines):")
            for (line in recent) {
                DiagnosticRedactor.redact(line)?.let { appendLine("  $it") }
            }
        }
    }
}
