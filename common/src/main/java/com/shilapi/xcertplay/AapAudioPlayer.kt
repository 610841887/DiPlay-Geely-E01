package com.shilapi.xcertplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the phone's PCM audio for Android Auto (AAP) and CarLife channels.
 *
 * Supports independent channel roles ([AudioRole.MEDIA], [AudioRole.GUIDANCE], [AudioRole.SYSTEM]),
 * smooth ducking gain control when navigation guidance or vehicle alerts speak, and non-blocking
 * bounded queueing so audio jitter never stalls the video frame loop.
 *
 * Compatible with Android 4.2+ (API 17).
 */
class AapAudioPlayer(
    val role: AudioRole = AudioRole.MEDIA,
    private val log: (String) -> Unit = {},
) {

    enum class AudioRole {
        MEDIA,
        GUIDANCE,
        SYSTEM,
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var queue: ArrayBlockingQueue<Chunk>? = null

    @Volatile
    private var running = false

    @Volatile
    private var ducked = false

    @Volatile
    private var muted = false

    /** Chunks dropped because the queue was full or the track was gone. */
    @Volatile
    var droppedChunks = 0L
        private set

    /** Chunks written to the track, for the diagnostics line. */
    @Volatile
    var playedChunks = 0L
        private set

    /** Timestamp (ms) of the most recent audio write, used to detect active guidance playback. */
    @Volatile
    var lastWriteMs = 0L
        private set

    private class Chunk(val bytes: ByteArray)

    val isRunning: Boolean get() = running
    val isDucked: Boolean get() = ducked
    val isMuted: Boolean get() = muted

    /** Starts (or restarts) playback at the phone's format. */
    @Synchronized
    fun start(sampleRate: Int, channels: Int, bitDepth: Int) {
        if (running) return
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val encoding = if (bitDepth == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        val bytesPerFrame = (bitDepth / 8).coerceAtLeast(1) * channels.coerceAtLeast(1)
        val bufferSize = (sampleRate / 4 * bytesPerFrame).coerceAtLeast(4096)
        val created = try {
            createTrack(sampleRate, channelMask, encoding, bufferSize)
        } catch (e: Exception) {
            log("audio(${role.name}) track unavailable: ${e.message}")
            return
        }
        track = created
        applyGain(created)
        created.play()
        val chunks = ArrayBlockingQueue<Chunk>(AUDIO_QUEUE)
        queue = chunks
        running = true
        thread = Thread({
            while (running) {
                val chunk = try {
                    chunks.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                try {
                    created.write(chunk.bytes, 0, chunk.bytes.size)
                    playedChunks++
                } catch (e: Exception) {
                    log("audio(${role.name}) write failed: ${e.message}")
                    break
                }
            }
        }, "aap-audio-${role.name.lowercase()}").apply { isDaemon = true; start() }
        log("audio(${role.name}) started ${sampleRate}Hz ${channels}ch ${bitDepth}bit")
    }

    /** Queues one PCM chunk. Drops instead of blocking when full. */
    fun write(data: ByteArray, offset: Int, length: Int) {
        if (!running) return
        val chunks = queue ?: return
        lastWriteMs = System.currentTimeMillis()
        if (!chunks.offer(Chunk(data.copyOfRange(offset, offset + length)))) {
            droppedChunks++
        }
    }

    /** Applies or releases ducking attenuation (e.g. when navigation guidance speaks). */
    fun setDucking(duck: Boolean) {
        if (ducked == duck) return
        ducked = duck
        track?.let { applyGain(it) }
    }

    /** Temporarily mutes or unmutes output (e.g. on transient system audio focus loss). */
    fun setMuted(mute: Boolean) {
        if (muted == mute) return
        muted = mute
        track?.let { applyGain(it) }
    }

    private fun applyGain(t: AudioTrack) {
        val gain = when {
            muted -> 0f
            ducked -> DUCK_GAIN
            else -> 1f
        }
        runCatching {
            if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) {
                t.setVolume(gain)
            } else {
                @Suppress("DEPRECATION")
                t.setStereoVolume(gain, gain)
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        queue?.clear()
        queue = null
        val current = track
        track = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }

    private fun createTrack(sampleRate: Int, channelMask: Int, encoding: Int, bufferSize: Int): AudioTrack =
        if (realSdkInt() >= Build.VERSION_CODES.M) {
            val (usage, contentType) = when (role) {
                AudioRole.MEDIA -> AudioAttributes.USAGE_MEDIA to AudioAttributes.CONTENT_TYPE_MUSIC
                AudioRole.GUIDANCE -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE to AudioAttributes.CONTENT_TYPE_SPEECH
                AudioRole.SYSTEM -> AudioAttributes.USAGE_NOTIFICATION to AudioAttributes.CONTENT_TYPE_SONIFICATION
            }
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            val streamType = when (role) {
                AudioRole.MEDIA -> AudioManager.STREAM_MUSIC
                AudioRole.GUIDANCE -> AudioManager.STREAM_NOTIFICATION
                AudioRole.SYSTEM -> AudioManager.STREAM_SYSTEM
            }
            @Suppress("DEPRECATION")
            AudioTrack(
                streamType,
                sampleRate,
                channelMask,
                encoding,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )
        }

    companion object {
        /** About half a second at the sizes involved; deeper only adds latency after a pause. */
        const val AUDIO_QUEUE = 32
        /** Attenuation factor applied to media while navigation guidance or duck-focus is active. */
        const val DUCK_GAIN = 0.28f
    }
}
