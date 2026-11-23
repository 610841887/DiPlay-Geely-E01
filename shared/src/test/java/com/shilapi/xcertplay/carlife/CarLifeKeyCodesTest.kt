package com.shilapi.xcertplay.carlife

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLifeKeyCodesTest {

    @Test
    fun mapsNavigationAndSystemKeys() {
        assertEquals(CarLifeKeyCodes.KEY_BACK, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_BACK))
        assertEquals(CarLifeKeyCodes.KEY_HOME, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_HOME))
        assertEquals(CarLifeKeyCodes.KEY_OK, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(CarLifeKeyCodes.KEY_OK, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_ENTER))
        assertEquals(CarLifeKeyCodes.KEY_UP, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(CarLifeKeyCodes.KEY_DOWN, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(CarLifeKeyCodes.KEY_LEFT, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(CarLifeKeyCodes.KEY_RIGHT, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun mapsMediaAndSteeringWheelKeys() {
        assertEquals(CarLifeKeyCodes.KEY_MEDIA_PLAY_PAUSE, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CarLifeKeyCodes.KEY_MEDIA_PLAY_PAUSE, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CarLifeKeyCodes.KEY_MEDIA_PLAY_PAUSE, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CarLifeKeyCodes.KEY_MEDIA_NEXT, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarLifeKeyCodes.KEY_MEDIA_PREV, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(CarLifeKeyCodes.KEY_VOL_UP, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(CarLifeKeyCodes.KEY_VOL_DOWN, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(CarLifeKeyCodes.KEY_MUTE, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE))
    }

    @Test
    fun mapsTelephonyAndVoiceKeys() {
        assertEquals(CarLifeKeyCodes.KEY_CALL, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_CALL))
        assertEquals(CarLifeKeyCodes.KEY_HANGUP, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_ENDCALL))
        assertEquals(CarLifeKeyCodes.KEY_VOICE, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertEquals(CarLifeKeyCodes.KEY_VOICE, CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_SEARCH))
    }

    @Test
    fun unmappedKeysReturnNull() {
        assertNull(CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_A))
        assertNull(CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_1))
        assertNull(CarLifeKeyCodes.fromAndroidKeyEvent(KeyEvent.KEYCODE_UNKNOWN))
    }
}
