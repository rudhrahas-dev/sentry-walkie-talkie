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

    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    init {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        bufferSize = (minBuf * 2).coerceAtLeast(2048)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        try {
            // AudioSource.MIC is the most universal and reliable across Android devices
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "AudioRecord not initialized")
                try { record.release() } catch (_: Throwable) {}
                return
            }

            record.startRecording()
            audioRecord = record
            isRecording = true

            recordThread = Thread({
                val buffer = ByteArray(1024) // 512 samples = 32ms
                while (isRecording) {
                    val currentRecord = audioRecord ?: break
                    if (currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) break

                    val read = currentRecord.read(buffer, 0, buffer.size)
                    if (read > 0 && isRecording) {
                        val chunk = ByteArray(read)
                        System.arraycopy(buffer, 0, chunk, 0, read)
                        try {
                            onAudioChunk(chunk)
                        } catch (t: Throwable) {
                            Log.w("AudioRecorder", "onAudioChunk error: ${t.message}")
                        }
                    } else if (read < 0) {
                        Log.w("AudioRecorder", "AudioRecord read returned error code $read")
                        break
                    }
                }
            }, "SentryPTT-RecordThread").apply {
                priority = Thread.NORM_PRIORITY + 2
                start()
            }
        } catch (t: Throwable) {
            Log.e("AudioRecorder", "Error starting recording: ${t.message}")
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false

        try {
            val recordToRelease = audioRecord
            audioRecord = null
            recordThread?.interrupt()
            recordThread = null

            recordToRelease?.let {
                try {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                } catch (_: Throwable) {}
                try {
                    it.release()
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.e("AudioRecorder", "Error stopping recording: ${t.message}")
        }
    }
}
