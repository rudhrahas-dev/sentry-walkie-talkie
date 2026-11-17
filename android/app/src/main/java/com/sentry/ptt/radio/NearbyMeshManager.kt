package com.sentry.ptt.radio

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class NearbyMeshManager(
    private val context: Context,
    private val callsign: String,
    private val onPacketReceived: (ParsedPacket) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val connectedEndpoints = mutableSetOf<String>()
    private var currentChannel = 42
    private var isMeshRunning = false

    private fun getServiceId(ch: Int): String {
        return "com.sentry.ptt.ch" + String.format("%03d", ch)
    }

    fun start(channel: Int) {
        currentChannel = channel
        isMeshRunning = true
        startAdvertising()
        startDiscovery()
        onStatusChanged("DIRECT RADIO ACTIVE")
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            callsign,
            getServiceId(currentChannel),
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnFailureListener { e ->
            Log.w("NearbyMesh", "Start advertising failed: ${e.message}")
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            getServiceId(currentChannel),
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnFailureListener { e ->
            Log.w("NearbyMesh", "Start discovery failed: ${e.message}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Auto connect to discovered peer on the same channel
            connectionsClient.requestConnection(
                callsign,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener { e ->
                Log.w("NearbyMesh", "Request connection failed: ${e.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            connectedEndpoints.remove(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept connection
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                onStatusChanged("RADIO PEERS: ${connectedEndpoints.size}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            onStatusChanged("RADIO PEERS: ${connectedEndpoints.size}")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val parsed = RadioPacket.parse(bytes) ?: return
                onPacketReceived(parsed)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun sendPacket(packet: ByteArray) {
        if (connectedEndpoints.isEmpty()) return
        val payload = Payload.fromBytes(packet)
        connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
    }

    fun setChannel(newChannel: Int) {
        if (currentChannel != newChannel && isMeshRunning) {
            stop()
            start(newChannel)
        }
    }

    fun stop() {
        isMeshRunning = false
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            connectedEndpoints.clear()
        } catch (_: Exception) {}
    }
}
