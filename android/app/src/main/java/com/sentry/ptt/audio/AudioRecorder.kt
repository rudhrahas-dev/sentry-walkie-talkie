package com.sentry.ptt.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioRecorder(private val onAudioChunk: (ByteArray) -> Unit) {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordThread: Thread? = null

    init {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        bufferSize = (minBuf * 2).coerceAtLeast(2048)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "AudioRecord not initialized")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordThread = Thread {
                val buffer = ByteArray(1024) // 512 samples = 32ms per chunk
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val chunk = ByteArray(read)
                        System.arraycopy(buffer, 0, chunk, 0, read)
                        onAudioChunk(chunk)
                    }
                }
            }.apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error starting recording: ${e.message}")
        }
    }

    fun stop() {
        isRecording = false
        try {
            recordThread?.join(300)
            recordThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping recording: ${e.message}")
        }
    }
}
