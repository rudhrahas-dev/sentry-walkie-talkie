package com.sentry.ptt.bridge

import android.webkit.JavascriptInterface
import com.sentry.ptt.SentryPttService

class WebAppInterface(private val getService: () -> SentryPttService?) {

    @JavascriptInterface
    fun startTransmitting() {
        getService()?.startTransmitting()
    }

    @JavascriptInterface
    fun stopTransmitting() {
        getService()?.stopTransmitting()
    }

    @JavascriptInterface
    fun setChannel(channel: Int) {
        getService()?.setChannel(channel)
    }

    @JavascriptInterface
    fun setMode(allCall: Boolean) {
        getService()?.setMode(allCall)
    }

    @JavascriptInterface
    fun setTransceiver(mode: String) {
        // "offgrid" = Google Nearby Direct Radio, "cloud" = Web/iPhone compatible MQTT
        val isOffGrid = mode.equals("offgrid", ignoreCase = true)
        getService()?.setRadioTransceiverMode(isOffGrid)
    }

    @JavascriptInterface
    fun setSquadPin(pin: String) {
        getService()?.squadPin = pin
    }

    @JavascriptInterface
    fun setCallsign(callsign: String) {
        getService()?.callsign = callsign
    }

    @JavascriptInterface
    fun setVolume(percent: Int) {
        getService()?.setVolume(percent)
    }

    @JavascriptInterface
    fun setSpeakerEnabled(enabled: Boolean) {
        getService()?.setSpeakerEnabled(enabled)
    }

    @JavascriptInterface
    fun getStatus(): String = getService()?.currentStatus ?: "CONNECTING..."

    @JavascriptInterface
    fun isNativeApp(): Boolean = true
}
