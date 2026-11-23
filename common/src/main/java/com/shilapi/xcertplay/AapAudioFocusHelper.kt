package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt

/**
 * Coordinates Android system audio focus for Android Auto (AAP) and CarLife sessions.
 *
 * Ensures head-unit native sources (reversing camera radar, OEM radio, phone calls) can smoothly
 * duck or pause projected audio, and restores playback once focus returns.
 *
 * Compatible with Android 4.2+ (API 17).
 */
class AapAudioFocusHelper(
    context: Context,
    private val onDuckChanged: (Boolean) -> Unit,
    private val onMuteChanged: (Boolean) -> Unit,
    private val onFocusLostPermanent: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    private var hasFocus = false

    private var focusRequestObj: Any? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                onDuckChanged(false)
                onMuteChanged(false)
                log("audio focus gained")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                onDuckChanged(true)
                log("audio focus transient loss (ducking)")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                onMuteChanged(true)
                log("audio focus transient loss (muting)")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                onMuteChanged(true)
                log("audio focus lost permanently")
                onFocusLostPermanent()
            }
        }
    }

    fun requestFocus(): Boolean {
        val am = audioManager ?: return false
        if (hasFocus) return true
        val granted = runCatching {
            if (realSdkInt() >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                focusRequestObj = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(false)
        hasFocus = granted
        log("audio focus request granted=$granted")
        return granted
    }

    fun abandonFocus() {
        val am = audioManager ?: return
        hasFocus = false
        runCatching {
            if (realSdkInt() >= Build.VERSION_CODES.O) {
                (focusRequestObj as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
                focusRequestObj = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(listener)
            }
        }
        onDuckChanged(false)
        onMuteChanged(false)
    }
}
