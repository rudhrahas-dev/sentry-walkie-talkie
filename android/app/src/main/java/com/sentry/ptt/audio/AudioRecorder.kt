package com.sentry.ptt.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioRecorder(private val onAudioChunk: (ByteArray) -> Unit) {

    private val targetSampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var actualSampleRate = 16000

    private data class RecordConfig(
        val audioRecord: AudioRecord,
        val sampleRate: Int,
        val bufferSize: Int
    )

    private fun findBestRecordConfig(): RecordConfig? {
        val sampleRatesToTry = intArrayOf(16000, 48000, 44100, 8000)
        val sourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER
        )

        for (rate in sampleRatesToTry) {
            val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            if (minBuf <= 0) continue
            val bufSize = (minBuf * 2).coerceAtLeast(2048)

            for (source in sourcesToTry) {
                try {
                    val record = AudioRecord(source, rate, channelConfig, audioFormat, bufSize)
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        Log.i("AudioRecorder", "Found working hardware audio config: rate=$rate, source=$source, bufSize=$bufSize")
                        return RecordConfig(record, rate, bufSize)
                    } else {
                        try { record.release() } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    Log.d("AudioRecorder", "Failed config rate=$rate, source=$source: ${t.message}")
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        try {
            val config = findBestRecordConfig()
            if (config == null) {
                Log.e("AudioRecorder", "No supported AudioRecord configuration found on this device!")
                return
            }

            val record = config.audioRecord
            actualSampleRate = config.sampleRate
            record.startRecording()
            audioRecord = record
            isRecording = true

            recordThread = Thread({
                // Adjust buffer size proportionally: 32ms of audio
                val rawSamplesPerChunk = (actualSampleRate * 0.032).toInt().coerceAtLeast(256)
                val buffer = ByteArray(rawSamplesPerChunk * 2)

                while (isRecording) {
                    val currentRecord = audioRecord ?: break
                    if (currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) break

                    val bytesRead = currentRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0 && isRecording) {
                        val pcm16k = resampleTo16k(buffer, bytesRead, actualSampleRate)
                        if (pcm16k.isNotEmpty()) {
                            try {
                                onAudioChunk(pcm16k)
                            } catch (t: Throwable) {
                                Log.w("AudioRecorder", "onAudioChunk error: ${t.message}")
                            }
                        }
                    } else if (bytesRead < 0) {
                        Log.w("AudioRecorder", "AudioRecord read returned error code $bytesRead")
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

    private fun resampleTo16k(input: ByteArray, inputLength: Int, sourceRate: Int): ByteArray {
        if (sourceRate == 16000) {
            val out = ByteArray(inputLength)
            System.arraycopy(input, 0, out, 0, inputLength)
            return out
        }

        val inSamples = inputLength / 2
        if (inSamples == 0) return ByteArray(0)

        if (sourceRate == 48000) {
            // Fast 3:1 integer downsampling
            val outSamples = inSamples / 3
            val out = ByteArray(outSamples * 2)
            var inByteIdx = 0
            var outByteIdx = 0
            while (outByteIdx < out.size && inByteIdx < inputLength - 1) {
                out[outByteIdx] = input[inByteIdx]
                out[outByteIdx + 1] = input[inByteIdx + 1]
                outByteIdx += 2
                inByteIdx += 6
            }
            return out
        }

        // Linear interpolation for arbitrary rates (e.g. 44100 -> 16000 or 8000 -> 16000)
        val ratio = sourceRate.toDouble() / targetSampleRate.toDouble()
        val outSamples = (inSamples / ratio).toInt()
        val out = ByteArray(outSamples * 2)
        for (i in 0 until outSamples) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, inSamples - 1)
            val byteOffset = srcIdx * 2
            out[i * 2] = input[byteOffset]
            out[i * 2 + 1] = input[byteOffset + 1]
        }
        return out
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
