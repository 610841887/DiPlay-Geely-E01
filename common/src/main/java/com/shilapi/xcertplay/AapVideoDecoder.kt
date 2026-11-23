package com.shilapi.xcertplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.shilapi.xcertplay.compat.realSdkInt
import java.nio.ByteBuffer

/**
 * Decodes the phone's H.264 stream onto a [Surface].
 *
 * Android Auto sends Annex-B H.264: `CODEC_CONFIG` carries SPS and PPS with start codes, and every
 * `DATA` message is one access unit. MediaCodec wants the parameter sets as `csd-0`/`csd-1` on the
 * format rather than as buffers, so they are parsed out and the codec is configured once both are
 * known — which is why nothing is configured until the config arrives.
 *
 * Output goes straight to the surface (`render = true`), so there is no output loop: the decoder
 * hands frames to the compositor on its own. The only thing that has to be watched is the input
 * side, which is where frames are dropped when the decoder is behind — dropping is the right answer
 * for video, since a late frame carries no information the next one does not.
 */
class AapVideoDecoder(
    private val log: (String) -> Unit = {},
    /** Called once, when the decoder has actually put a frame on the surface. */
    private val onFirstFrame: () -> Unit = {},
    /** Called when consecutive input drops exceed threshold and decoder auto-resets. */
    private val onStallDetected: () -> Unit = {},
) {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var closed = false

    /** Frames that arrived with no input buffer free. */
    var dropped = 0
        private set

    /** Consecutive frames dropped without an available input buffer. */
    var consecutiveDrops = 0
        private set

    /** Total frames successfully rendered to the surface. */
    var renderedFrames = 0L
        private set

    /** Number of automatic decoder resets triggered by consecutive drops. */
    var stallRecoveries = 0
        private set

    /** True once the codec has been started and is taking frames. */
    val running: Boolean get() = codec != null

    @Synchronized
    fun setSurface(surface: Surface?) = configure(surface, width, height)

    @Synchronized
    fun setVideoSize(width: Int, height: Int) = configure(surface, width, height)

    private fun configure(surface: Surface?, width: Int, height: Int) {
        if (closed) return
        val changed = this.surface !== surface || this.width != width || this.height != height
        if (!changed) return
        release()
        this.surface = surface
        this.width = width
        this.height = height
        // Retain existing SPS/PPS if already known so startIfReady() can immediately launch
        // the decoder without waiting for a new keyframe or going silent.
        startIfReady()
    }

    /** Feeds `CODEC_CONFIG`: Annex-B SPS and/or PPS. */
    @Synchronized
    fun setCodecConfig(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (closed) return
        for (nal in AnnexB.split(data, offset, length)) {
            when (nal.type) {
                // The start code goes back on: what an encoder puts in `csd-0` is the whole NAL
                // unit including it, and decoders that receive a bare SPS without one accept the
                // config, report no error, and then never output a frame.
                7 -> sps = AnnexB.withStartCode(nal.bytes)
                8 -> pps = AnnexB.withStartCode(nal.bytes)
                else -> Unit
            }
        }
        log("codec config: sps=${sps?.size ?: 0}B pps=${pps?.size ?: 0}B")
        startIfReady()
    }

    /** Queues one access unit, in microseconds, as the phone stamped it. */
    @Synchronized
    fun queue(data: ByteArray, offset: Int, length: Int, presentationTimeUs: Long) {
        if (closed) return
        val nals = AnnexB.split(data, offset, length)
        if (!loggedFirstUnit) {
            loggedFirstUnit = true
            // NAL types say most of it: 5 is an IDR, 7/8 are the parameter sets, 6 is SEI. A first
            // unit without a 5 decodes to nothing until the next key frame.
            log("first access unit NALs ${nals.map { it.type }}")
        }

        // Handle initial or mid-stream in-band SPS/PPS (e.g. CarLife splash -> landscape map
        // transition where the phone restarts its encoder with updated dimensions/profile).
        var newSps: ByteArray? = null
        var newPps: ByteArray? = null
        for (nal in nals) {
            when (nal.type) {
                7 -> newSps = AnnexB.withStartCode(nal.bytes)
                8 -> newPps = AnnexB.withStartCode(nal.bytes)
            }
        }
        var reconfigured = false
        if (newSps != null || newPps != null) {
            val spsChanged = newSps != null && !newSps.contentEquals(sps)
            val ppsChanged = newPps != null && !newPps.contentEquals(pps)
            if (newSps != null) sps = newSps
            if (newPps != null) pps = newPps
            if (codec != null && (spsChanged || ppsChanged)) {
                log("codec config changed in-band (sps=${sps?.size ?: 0}B pps=${pps?.size ?: 0}B); reconfiguring decoder")
                release()
                startIfReady()
                reconfigured = true
            } else if (codec == null) {
                startIfReady()
                reconfigured = true
            }
            // Standalone SPS/PPS packets have already been applied via csd-0/csd-1; do not feed
            // them as regular video frames with flags=0 (some hardware decoders wedge on them).
            if (nals.isNotEmpty() && nals.all { it.type == 7 || it.type == 8 }) {
                return
            }
        }

        val codec = codec ?: return

        // Always drain decoded output frames BEFORE requesting an input buffer.
        // Releasing finished output buffers returns them to the pool, preventing input starvation.
        drain(codec)

        // If the codec was freshly started or reconfigured, give it a few retries (up to 50ms)
        // to acquire an input buffer so we never drop the crucial IDR keyframe bundled with SPS/PPS.
        val maxAttempts = if (reconfigured) 5 else 1
        var index = -1
        for (attempt in 0 until maxAttempts) {
            index = try {
                codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                log("decoder went away: ${e.message}")
                release()
                return
            }
            if (index >= 0) break
            if (attempt < maxAttempts - 1) {
                drain(codec)
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
            }
        }

        if (index < 0) {
            dropped++
            consecutiveDrops++
            if (consecutiveDrops >= CONSECUTIVE_DROP_RESET_THRESHOLD) {
                log("decoder stalled ($consecutiveDrops consecutive drops), auto-resetting for keyframe recovery")
                consecutiveDrops = 0
                stallRecoveries++
                reset()
                onStallDetected()
            }
            return
        }
        consecutiveDrops = 0
        val buffer = getInputBuffer(codec, index) ?: return
        buffer.clear()
        if (length > buffer.capacity()) {
            log("frame of $length bytes does not fit the decoder buffer (${buffer.capacity()})")
            dropped++
            codec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0)
            return
        }
        buffer.put(data, offset, length)
        codec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
        queuedWithoutOutput++
        drain(codec)
        if (queuedWithoutOutput >= OUTPUT_STALL_RESET_THRESHOLD &&
            System.nanoTime() - lastOutputNanos >= OUTPUT_STALL_WINDOW_NS
        ) {
            log("decoder output stalled ($queuedWithoutOutput queued without output), auto-resetting for keyframe recovery")
            queuedWithoutOutput = 0
            stallRecoveries++
            reset()
            onStallDetected()
        }
    }

    /** Drops the decoder so the next frame reconfigures. Used when focus returns. */
    @Synchronized
    fun reset() {
        release()
        startIfReady()
    }

    @Synchronized
    fun close() {
        closed = true
        surface = null
        release()
    }

    private fun release() {
        val current = codec ?: return
        codec = null
        inputBuffers = null
        queuedWithoutOutput = 0
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    /**
     * Releases every decoded frame the codec has ready, rendering each one.
     *
     * This is not optional even though the codec was configured with a surface: a surface-backed
     * decoder shows nothing until the frame is released with `render = true`, and leaving the loop
     * out produces the worst possible symptom — frames counting up on the input side while the
     * screen stays black.
     */
    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (e: IllegalStateException) {
                log("decoder went away: ${e.message}")
                release()
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = try { codec.outputFormat } catch (_: Exception) { null }
                    val w = format?.let { if (it.containsKey(MediaFormat.KEY_WIDTH)) it.getInteger(MediaFormat.KEY_WIDTH) else null }
                    val h = format?.let { if (it.containsKey(MediaFormat.KEY_HEIGHT)) it.getInteger(MediaFormat.KEY_HEIGHT) else null }
                    log("decoder output format changed to ${w ?: "?"}x${h ?: "?"}")
                }
                @Suppress("DEPRECATION")
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                index >= 0 -> {
                    codec.releaseOutputBuffer(index, true)
                    renderedFrames++
                    queuedWithoutOutput = 0
                    lastOutputNanos = System.nanoTime()
                    if (!reportedFirstFrame) {
                        reportedFirstFrame = true
                        log("first frame on screen")
                        onFirstFrame()
                    }
                }
                else -> return
            }
        }
    }

    private var reportedFirstFrame = false
    private var loggedFirstUnit = false
    private var inputBuffers: Array<ByteBuffer>? = null
    private var queuedWithoutOutput = 0
    private var lastOutputNanos = 0L

    private fun getInputBuffer(codec: MediaCodec, index: Int): ByteBuffer? =
        if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) {
            codec.getInputBuffer(index)
        } else {
            @Suppress("DEPRECATION")
            inputBuffers?.getOrNull(index) ?: codec.inputBuffers.getOrNull(index)?.also {
                inputBuffers = codec.inputBuffers
            }
        }

    private fun startIfReady() {
        val surface = surface?.takeIf { it.isValid } ?: return
        if (width <= 0 || height <= 0) return
        val sps = sps ?: return
        val pps = pps ?: return
        if (codec != null) return
        var candidate: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MIME, width.coerceAtLeast(16), height.coerceAtLeast(16))
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // The stream is already low-latency; asking the decoder to hold frames back would only
            // add latency the driver feels on every touch.
            if (realSdkInt() >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val created = MediaCodec.createDecoderByType(MIME).also { candidate = it }
            created.configure(format, surface, null, 0)
            created.start()
            if (realSdkInt() < Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION")
                inputBuffers = created.inputBuffers
            }
            codec = created
            queuedWithoutOutput = 0
            lastOutputNanos = System.nanoTime()
            log("decoder started for ${width}x$height")
        } catch (e: Exception) {
            runCatching { candidate?.release() }
            log("could not start the decoder: ${e.message}")
        }
    }

    companion object {
        const val MIME = "video/avc"
        const val INPUT_TIMEOUT_US = 10_000L
        /** Reset the hardware decoder after this many consecutive input-buffer drops. */
        const val CONSECUTIVE_DROP_RESET_THRESHOLD = 60
        /** Reset the hardware decoder if frames are accepted without producing any output. */
        const val OUTPUT_STALL_RESET_THRESHOLD = 90
        const val OUTPUT_STALL_WINDOW_NS = 3_000_000_000L
    }
}

/** One NAL unit found in an Annex-B stream. */
class AnnexBNal(val type: Int, val bytes: ByteArray)

/**
 * Splits Annex-B byte streams into NAL units.
 *
 * Both start codes (`00 00 01` and the four-byte `00 00 00 01`) occur in Android Auto streams, often
 * in the same buffer, so the scanner looks for the three-byte form and treats a preceding zero as
 * part of the same start code.
 */
object AnnexB {

    fun split(data: ByteArray, offset: Int, length: Int): List<AnnexBNal> {
        val out = ArrayList<AnnexBNal>(4)
        val end = offset + length
        var start = findStartCode(data, offset, end)
        while (start >= 0) {
            // The three-byte pattern is what the scan finds, so a four-byte start code leaves one
            // zero at the end of the previous unit. Annex-B allows trailing zeros there, but
            // trimming them keeps the bytes handed to the decoder identical to what the encoder
            // produced.
            val headerStart = start + START_CODE_SIZE
            val next = findStartCode(data, headerStart, end)
            var nalEnd = if (next < 0) end else next
            if (next >= 0) {
                while (nalEnd > headerStart && data[nalEnd - 1].toInt() == 0) nalEnd--
            }
            if (headerStart < nalEnd) {
                val type = data[headerStart].toInt() and 0x1f
                out += AnnexBNal(type, data.copyOfRange(headerStart, nalEnd))
            }
            start = next
        }
        return out
    }

    /** Whether [data] carries an SPS or PPS, which is what makes it usable as codec config. */
    fun hasParameterSets(data: ByteArray, offset: Int, length: Int): Boolean =
        split(data, offset, length).any { it.type == 7 || it.type == 8 }

    /** Four-byte Annex-B start code plus the unit, which is the form `csd-0` expects. */
    fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(4 + nal.size)
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    private fun findStartCode(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
            i++
        }
        return -1
    }

    private const val START_CODE_SIZE = 3
}
