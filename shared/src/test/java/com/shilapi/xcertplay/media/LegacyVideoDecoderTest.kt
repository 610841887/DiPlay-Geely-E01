package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 starts at API 23; the format-policy cases explicitly pass E01's API 22.
@Config(sdk = [23], manifest = Config.NONE)
class LegacyVideoDecoderTest {
    @Test fun e01HardwareKeepsItsNativeInputPortSize() {
        val format = videoDecoderFormat("video/avc", 1920, 720, "OMX.MTK.VIDEO.DECODER.AVC", 22)
        assertFalse(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
        assertFalse(format.containsKey(MediaFormat.KEY_PRIORITY))
        assertEquals(1920, format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
    }

    @Test fun allLegacyCodecsAvoidForcedSizeIncludingUnknownAndHevc() {
        for (sdk in 19..22) {
            for (name in listOf(null, "OMX.qcom.video.decoder.avc", "OMX.google.h264.decoder")) {
                assertTrue(needsNativePortSizing(name, sdk))
                assertFalse(videoDecoderFormat("video/hevc", 1280, 720, name, sdk)
                    .containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
    }

    @Test fun newerHardwareKeepsTuningExceptTheKnownK3PortLimit() {
        val modern = videoDecoderFormat("video/avc", 1280, 720, "c2.qti.avc.decoder", 30)
        assertEquals(8 * 1024 * 1024, modern.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        assertTrue(modern.containsKey(MediaFormat.KEY_PRIORITY))
        assertFalse(needsNativePortSizing(null, 23))
        assertTrue(needsNativePortSizing("OMX.K3.video.decoder.avc", 23))
    }

    @Test fun destroyedSurfaceWaitsWithoutConfiguringOrRepeatedErrors() {
        val texture = SurfaceTexture(1)
        val surface = Surface(texture)
        surface.release()
        val reports = CopyOnWriteArrayList<String>()
        val processed = CountDownLatch(1)
        var surfaceApplied = true
        val sink = AndroidMediaSink(surface)
        try {
            sink.setVideoDiagnosticHandler(110, reports::add)
            sink.onVideoConfig(110, byteArrayOf())
            sink.onVideoConfig(110, byteArrayOf())
            sink.replaceOutputSurface(110, surface) {
                surfaceApplied = it
                processed.countDown()
            }
            assertTrue("decoder worker did not process the surface change", processed.await(5, TimeUnit.SECONDS))
            assertFalse(surfaceApplied)
            assertEquals(1, reports.count { it.contains("surface invalid") })
            assertFalse(reports.any { it.contains("configuration failed") || it.startsWith("decoder configuring") })
        } finally {
            sink.close()
            texture.release()
        }
    }
}
