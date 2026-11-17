package com.sentry.ptt.radio

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors

class CloudMeshManager(
    private val onPacketReceived: (ParsedPacket) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {

    private var mqttClient: MqttClient? = null
    private val clientId = "sentry_android_" + UUID.randomUUID().toString().take(8)
    private val sendExecutor = Executors.newSingleThreadExecutor()
    
    private var currentChannel: Int = 42
    private var isAllCall: Boolean = false
    @Volatile var isConnected: Boolean = false
        private set
    @Volatile private var isRunning = false

    private fun resolveBrokerUrl(): String {
        return try {
            val addrs = InetAddress.getAllByName("broker.hivemq.com")
            val ipv4 = addrs.firstOrNull { it is Inet4Address }?.hostAddress
            if (ipv4 != null) "tcp://$ipv4:1883" else "tcp://broker.hivemq.com:1883"
        } catch (e: Exception) {
            "tcp://broker.hivemq.com:1883"
        }
    }

    fun connect() {
        if (isConnected || isRunning) return
        isRunning = true

        Thread {
            var attempt = 0
            while (isRunning && !isConnected) {
                try {
                    onStatusChanged("CONNECTING...")
                    val brokerUrl = resolveBrokerUrl()
                    Log.i("CloudMesh", "Connecting to MQTT at $brokerUrl (attempt ${attempt + 1})...")

                    val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
                    val options = MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 8
                        keepAliveInterval = 20
                        isAutomaticReconnect = true
                    }

                    client.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                            isConnected = true
                            onStatusChanged("MESH ACTIVE")
                            subscribe()
                        }

                        override fun connectionLost(cause: Throwable?) {
                            isConnected = false
                            onStatusChanged("RECONNECTING...")
                        }

                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            val payload = message?.payload ?: return
                            val parsed = RadioPacket.parse(payload) ?: return
                            onPacketReceived(parsed)
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })

                    client.connect(options)
                    mqttClient = client
                    isConnected = true
                    onStatusChanged("MESH ACTIVE")
                    subscribe()
                    break
                } catch (e: Exception) {
                    attempt++
                    Log.w("CloudMesh", "Connect attempt $attempt failed: ${e.message}")
                    onStatusChanged("RETRYING MESH...")
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.start()
    }

    private fun subscribe() {
        val client = mqttClient ?: return
        if (!isConnected) return
        try {
            val allCallTopic = "sentry/ptt/v1/allcall"
            client.subscribe(allCallTopic, 0)

            if (isAllCall) {
                client.subscribe("sentry/ptt/v1/ch/+", 0)
            } else {
                val padded = String.format("%03d", currentChannel)
                client.subscribe("sentry/ptt/v1/ch/$padded", 0)
            }
        } catch (e: Exception) {
            Log.e("CloudMesh", "Subscribe error: ${e.message}")
        }
    }

    fun setChannel(channel: Int) {
        val old = currentChannel
        currentChannel = channel
        val client = mqttClient
        if (isConnected && client != null) {
            try {
                val oldPadded = String.format("%03d", old)
                client.unsubscribe("sentry/ptt/v1/ch/$oldPadded")
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
        try {
            sendExecutor.execute {
                try {
                    val client = mqttClient ?: return@execute
                    if (!isConnected) return@execute
                    val topic = if (isAllCall) {
                        "sentry/ptt/v1/allcall"
                    } else {
                        val padded = String.format("%03d", currentChannel)
                        "sentry/ptt/v1/ch/$padded"
                    }
                    val msg = MqttMessage(packet).apply { qos = 0 }
                    client.publish(topic, msg)
                } catch (t: Throwable) {
                    Log.w("CloudMesh", "Publish warning: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w("CloudMesh", "SendExecutor warning: ${t.message}")
        }
    }

    fun disconnect() {
        isRunning = false
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
            isConnected = false
        } catch (_: Throwable) {}
    }
}
