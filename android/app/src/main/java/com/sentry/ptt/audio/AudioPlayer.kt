package com.sentry.ptt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.sin

class AudioPlayer(private val context: Context) {

    private val sampleRate = 16000
    private var audioTrack: AudioTrack? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volumeMultiplier = 1.0f
    private var isSpeakerEnabled = true

    init {
        initTrack()
    }

    private fun initTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBuf * 2).coerceAtLeast(4096)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioTrack = AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        // Route to speakerphone
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.w("AudioPlayer", "Speakerphone routing warning: ${e.message}")
        }

        audioTrack?.play()
    }

    fun playAudio(pcmData: ByteArray) {
        if (!isSpeakerEnabled || pcmData.isEmpty()) return

        if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            initTrack()
        }

        try {
            // Apply volume multiplier if not 1.0
            val dataToWrite = if (volumeMultiplier != 1.0f) {
                applyVolume(pcmData, volumeMultiplier)
            } else {
                pcmData
            }

            audioTrack?.write(dataToWrite, 0, dataToWrite.size)
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error writing to AudioTrack: ${e.message}")
        }
    }

    private fun applyVolume(data: ByteArray, multiplier: Float): ByteArray {
        val adjusted = ByteArray(data.size)
        var i = 0
        while (i < data.size - 1) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
            val scaled = (sample * multiplier).toInt().coerceIn(-32768, 32767).toShort()
            adjusted[i] = (scaled.toInt() and 0xFF).toByte()
            adjusted[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return adjusted
    }

    fun playRogerBeep() {
        Thread {
            val durationMs = 120
            val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
            val pcm = ByteArray(numSamples * 2)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freq = if (i < numSamples / 2) 1209.0 else 1633.0
                val angle = 2.0 * Math.PI * freq * t
                val sample = (sin(angle) * 10000).toInt().toShort()

                pcm[i * 2] = (sample.toInt() and 0xFF).toByte()
                pcm[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            playAudio(pcm)
        }.start()
    }

    fun setVolume(percent: Int) {
        volumeMultiplier = (percent / 100.0f) * 1.2f
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        isSpeakerEnabled = enabled
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error releasing AudioTrack: ${e.message}")
        }
    }
}
