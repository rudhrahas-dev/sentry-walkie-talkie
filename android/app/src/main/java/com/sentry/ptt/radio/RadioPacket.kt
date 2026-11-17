package com.sentry.ptt.radio

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ParsedPacket(
    val type: Byte,
    val channel: Int,
    val pinHash: Int,
    val callsign: String,
    val sequence: Short,
    val audioData: ByteArray?
)

object RadioPacket {
    const val TYPE_PTT_START: Byte = 0x01
    const val TYPE_AUDIO_CHUNK: Byte = 0x02
    const val TYPE_PTT_END: Byte = 0x03
    const val HEADER_LEN = 18

    fun build(
        type: Byte,
        channel: Int,
        pinHash: Int,
        callsign: String,
        sequence: Short,
        audioPcm16: ByteArray? = null
    ): ByteArray {
        val audioLen = audioPcm16?.size ?: 0
        val packet = ByteBuffer.allocate(HEADER_LEN + audioLen)
        packet.order(ByteOrder.BIG_ENDIAN)

        // [0] Packet Type
        packet.put(type)

        // [1..2] Channel (Uint16)
        packet.putShort(channel.toShort())

        // [3..6] Pin Hash (Uint32)
        packet.putInt(pinHash)

        // [7..14] Callsign (8 bytes ASCII)
        val cleanCallsign = callsign.take(8).padEnd(8, ' ')
        for (i in 0 until 8) {
            packet.put(cleanCallsign[i].code.toByte())
        }

        // [15..16] Sequence (Uint16)
        packet.putShort(sequence)

        // [17] Padding byte for 2-byte audio alignment
        packet.put(0.toByte())

        // [18..] Audio PCM payload (if present)
        if (audioPcm16 != null && audioLen > 0) {
            packet.put(audioPcm16)
        }

        return packet.array()
    }

    fun parse(bytes: ByteArray): ParsedPacket? {
        if (bytes.size < 17) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val type = buffer.get()
        val channel = buffer.short.toInt() and 0xFFFF
        val pinHash = buffer.int

        val callsignBytes = ByteArray(8)
        buffer.get(callsignBytes)
        val callsign = String(callsignBytes).trim()

        val sequence = buffer.short

        // Check if 18-byte header or 17-byte legacy header
        val headerOffset = if (bytes.size > 18 && bytes[17].toInt() == 0) 18 else 17
        val audioLen = bytes.size - headerOffset

        var audioData: ByteArray? = null
        if (audioLen > 0 && type == TYPE_AUDIO_CHUNK) {
            audioData = ByteArray(audioLen)
            System.arraycopy(bytes, headerOffset, audioData, 0, audioLen)
        }

        return ParsedPacket(
            type = type,
            channel = channel,
            pinHash = pinHash,
            callsign = callsign,
            sequence = sequence,
            audioData = audioData
        )
    }

    fun computePinHash(pin: String): Int {
        if (pin.isEmpty()) return 0
        var hash = 0
        for (ch in pin) {
            hash = ((hash shl 5) - hash) + ch.code
        }
        return Math.abs(hash)
    }
}
