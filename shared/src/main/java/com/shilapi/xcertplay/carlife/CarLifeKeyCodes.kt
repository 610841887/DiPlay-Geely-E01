package com.shilapi.xcertplay.carlife

import android.view.KeyEvent

/**
 * Baidu CarLife official physical key / hard key codes.
 *
 * Mapped to vehicle steering wheel controls and physical dash buttons.
 */
object CarLifeKeyCodes {
    const val KEY_HOME = 0x0001
    const val KEY_BACK = 0x0002
    const val KEY_OK = 0x0003
    const val KEY_UP = 0x0004
    const val KEY_DOWN = 0x0005
    const val KEY_LEFT = 0x0006
    const val KEY_RIGHT = 0x0007
    const val KEY_MEDIA_PLAY_PAUSE = 0x0020
    const val KEY_MEDIA_NEXT = 0x0021
    const val KEY_MEDIA_PREV = 0x0022
    const val KEY_VOL_UP = 0x0023
    const val KEY_VOL_DOWN = 0x0024
    const val KEY_MUTE = 0x0025
    const val KEY_CALL = 0x0030
    const val KEY_HANGUP = 0x0031
    const val KEY_VOICE = 0x0040

    /**
     * Maps an Android [KeyEvent] keycode to a CarLife hard key code.
     * Returns null if the key is not mapped.
     */
    fun fromAndroidKeyEvent(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> KEY_BACK
        KeyEvent.KEYCODE_HOME -> KEY_HOME
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> KEY_OK
        KeyEvent.KEYCODE_DPAD_UP -> KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> KEY_MEDIA_PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> KEY_MEDIA_NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> KEY_MEDIA_PREV
        KeyEvent.KEYCODE_VOLUME_UP -> KEY_VOL_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> KEY_VOL_DOWN
        KeyEvent.KEYCODE_VOLUME_MUTE -> KEY_MUTE
        KeyEvent.KEYCODE_CALL -> KEY_CALL
        KeyEvent.KEYCODE_ENDCALL -> KEY_HANGUP
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> KEY_VOICE
        else -> null
    }
}
