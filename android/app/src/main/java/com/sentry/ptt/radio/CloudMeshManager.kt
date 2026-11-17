package com.sentry.ptt.radio

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class CloudMeshManager(
    private val onPacketReceived: (ParsedPacket) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {

    private var mqttClient: MqttClient? = null
    private val clientId = "sentry_android_" + UUID.randomUUID().toString().take(8)
    private val brokerUrl = "tcp://broker.hivemq.com:1883"
    
    private var currentChannel: Int = 42
    private var isAllCall: Boolean = false
    private var isConnected = false

    fun connect() {
        Thread {
            try {
                onStatusChanged("CONNECTING (CLOUD)...")
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
                
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 20
                    isAutomaticReconnect = true
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        isConnected = true
                        onStatusChanged("CLOUD MESH ACTIVE")
                        subscribe()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        isConnected = false
                        onStatusChanged("CLOUD OFFLINE")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload ?: return
                        val parsed = RadioPacket.parse(payload) ?: return
                        onPacketReceived(parsed)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(options)
            } catch (e: Exception) {
                Log.e("CloudMesh", "Connect failed: ${e.message}")
                onStatusChanged("RETRYING CLOUD...")
            }
        }.start()
    }

    private fun subscribe() {
        if (!isConnected || mqttClient == null) return
        try {
            val allCallTopic = "sentry/ptt/v1/allcall"
            mqttClient?.subscribe(allCallTopic, 0)

            if (isAllCall) {
                mqttClient?.subscribe("sentry/ptt/v1/ch/+", 0)
            } else {
                val padded = String.format("%03d", currentChannel)
                mqttClient?.subscribe("sentry/ptt/v1/ch/$padded", 0)
            }
        } catch (e: Exception) {
            Log.e("CloudMesh", "Subscribe error: ${e.message}")
        }
    }

    fun setChannel(channel: Int) {
        val old = currentChannel
        currentChannel = channel
        if (isConnected && mqttClient != null) {
            try {
                val oldPadded = String.format("%03d", old)
                mqttClient?.unsubscribe("sentry/ptt/v1/ch/$oldPadded")
            } catch (_: Exception) {}
            subscribe()
        }
    }

    fun setMode(allCall: Boolean) {
        isAllCall = allCall
        if (isConnected && mqttClient != null) {
            subscribe()
        }
    }

    fun sendPacket(packet: ByteArray) {
        if (!isConnected || mqttClient == null) return
        Thread {
            try {
                val topic = if (isAllCall) {
                    "sentry/ptt/v1/allcall"
                } else {
                    val padded = String.format("%03d", currentChannel)
                    "sentry/ptt/v1/ch/$padded"
                }
                val msg = MqttMessage(packet).apply { qos = 0 }
                mqttClient?.publish(topic, msg)
            } catch (e: Exception) {
                Log.e("CloudMesh", "Publish error: ${e.message}")
            }
        }.start()
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
            isConnected = false
        } catch (_: Exception) {}
    }
}
