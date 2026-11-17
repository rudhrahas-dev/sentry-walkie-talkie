package com.sentry.ptt

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.sentry.ptt.audio.AudioPlayer
import com.sentry.ptt.audio.AudioRecorder
import com.sentry.ptt.radio.CloudMeshManager
import com.sentry.ptt.radio.NearbyMeshManager
import com.sentry.ptt.radio.ParsedPacket
import com.sentry.ptt.radio.RadioPacket

class SentryPttService : Service() {

    private val binder = LocalBinder()

    var callsign: String = "SENTRY-ANDR"
    var currentChannel: Int = 42
    var isAllCall: Boolean = false
    var squadPin: String = ""
    var isOffGridMode: Boolean = false // false = Cloud (Web/iPhone compatible), true = Direct Nearby Radio

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var cloudMesh: CloudMeshManager? = null
    private var nearbyMesh: NearbyMeshManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    var isTransmitting = false
        private set
    private var sequenceNum: Short = 0

    var currentStatus: String = "CONNECTING..."
        private set

    // Callback listeners for UI
    var onPacketReceivedListener: ((ParsedPacket) -> Unit)? = null
    var onStatusChangedListener: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): SentryPttService = this@SentryPttService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        initAudio()
        initMeshManagers()
        startForegroundServiceNotification()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentryPTT:WakeLock").apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours
        }
    }

    private fun initAudio() {
        audioPlayer = AudioPlayer(this)
        audioRecorder = AudioRecorder { pcmChunk ->
            if (isTransmitting) {
                sequenceNum = (sequenceNum + 1).toShort()
                val packet = RadioPacket.build(
                    type = RadioPacket.TYPE_AUDIO_CHUNK,
                    channel = currentChannel,
                    pinHash = RadioPacket.computePinHash(squadPin),
                    callsign = callsign,
                    sequence = sequenceNum,
                    audioPcm16 = pcmChunk
                )
                broadcastPacket(packet)
            }
        }
    }

    private fun initMeshManagers() {
        cloudMesh = CloudMeshManager(
            onPacketReceived = { packet -> handleIncomingPacket(packet) },
            onStatusChanged = { status ->
                currentStatus = status
                onStatusChangedListener?.invoke(status)
            }
        ).apply { connect() }

        nearbyMesh = NearbyMeshManager(
            context = this,
            callsign = callsign,
            onPacketReceived = { packet -> handleIncomingPacket(packet) },
            onStatusChanged = { status ->
                currentStatus = status
                onStatusChangedListener?.invoke(status)
            }
        )
    }

    private fun handleIncomingPacket(packet: ParsedPacket) {
        // Discard self broadcast
        if (packet.callsign == callsign) return

        // PIN validation
        val myPinHash = RadioPacket.computePinHash(squadPin)
        if (myPinHash != packet.pinHash) return

        // Channel filter
        if (!isAllCall && packet.channel != currentChannel) return

        when (packet.type) {
            RadioPacket.TYPE_PTT_START -> {
                onPacketReceivedListener?.invoke(packet)
            }
            RadioPacket.TYPE_AUDIO_CHUNK -> {
                packet.audioData?.let { audioPlayer?.playAudio(it) }
                onPacketReceivedListener?.invoke(packet)
            }
            RadioPacket.TYPE_PTT_END -> {
                audioPlayer?.playRogerBeep()
                onPacketReceivedListener?.invoke(packet)
            }
        }
    }

    private fun broadcastPacket(packet: ByteArray) {
        if (isOffGridMode) {
            nearbyMesh?.sendPacket(packet)
        } else {
            cloudMesh?.sendPacket(packet)
        }
    }

    fun startTransmitting() {
        if (isTransmitting) return
        isTransmitting = true

        try {
            sequenceNum = (sequenceNum + 1).toShort()
            val startPacket = RadioPacket.build(
                type = RadioPacket.TYPE_PTT_START,
                channel = currentChannel,
                pinHash = RadioPacket.computePinHash(squadPin),
                callsign = callsign,
                sequence = sequenceNum
            )
            broadcastPacket(startPacket)
            audioRecorder?.start()
        } catch (t: Throwable) {
            android.util.Log.e("SentryPttService", "startTransmitting error: ${t.message}")
        }
    }

    fun stopTransmitting() {
        if (!isTransmitting) return
        isTransmitting = false

        try {
            audioRecorder?.stop()

            sequenceNum = (sequenceNum + 1).toShort()
            val endPacket = RadioPacket.build(
                type = RadioPacket.TYPE_PTT_END,
                channel = currentChannel,
                pinHash = RadioPacket.computePinHash(squadPin),
                callsign = callsign,
                sequence = sequenceNum
            )
            broadcastPacket(endPacket)
            audioPlayer?.playRogerBeep()
        } catch (t: Throwable) {
            android.util.Log.e("SentryPttService", "stopTransmitting error: ${t.message}")
        }
    }

    fun setChannel(channel: Int) {
        currentChannel = channel
        cloudMesh?.setChannel(channel)
        if (isOffGridMode) {
            nearbyMesh?.setChannel(channel)
        }
        updateNotification()
    }

    fun setMode(allCall: Boolean) {
        isAllCall = allCall
        cloudMesh?.setMode(allCall)
    }

    fun setRadioTransceiverMode(offGrid: Boolean) {
        isOffGridMode = offGrid
        if (offGrid) {
            cloudMesh?.disconnect()
            nearbyMesh?.start(currentChannel)
        } else {
            nearbyMesh?.stop()
            cloudMesh?.connect()
        }
        updateNotification()
    }

    fun setVolume(percent: Int) {
        audioPlayer?.setVolume(percent)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        audioPlayer?.setSpeakerEnabled(enabled)
    }

    private fun startForegroundServiceNotification() {
        val channelId = "sentry_ptt_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sentry PTT Background Comms",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification(channelId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1337,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(1337, notification)
        }
    }

    private fun updateNotification() {
        val channelId = "sentry_ptt_service_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1337, buildNotification(channelId))
    }

    private fun buildNotification(channelId: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val modeLabel = if (isOffGridMode) "OFF-GRID RADIO" else "CLOUD MESH (WEB/IPHONE)"
        val padded = String.format("%03d", currentChannel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sentry PTT • Active on CH-$padded")
            .setContentText("Transceiver: $modeLabel | Pocket Mode Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder?.stop()
        audioPlayer?.release()
        cloudMesh?.disconnect()
        nearbyMesh?.stop()
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
    }
}
