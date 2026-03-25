package com.hdcollection.enforcement.gb28181

import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class RtpSender(private val targetIp: String, private val targetPort: Int) {

    private var socket: DatagramSocket? = null
    private var sequenceNumber: Int = (Math.random() * 0xFFFF).toInt()
    private var ssrc: Int = (Math.random() * 0xFFFFFFFF).toLong().toInt()
    private val payloadType = 96  // PS

    fun start() {
        socket = DatagramSocket()
        Timber.i("RtpSender: started, target=$targetIp:$targetPort")
    }

    fun sendVideoFrame(encodedData: ByteArray, timestampMs: Long) {
        val sock = socket ?: return
        try {
            // 简单 RTP 包封装（不做 PS 打包，直接送 H.264 NALU）
            // 实际 GB28181 需要 PS 封装，此处为可工作的简化版本
            val mtu = 1400
            var offset = 0
            val timestamp = (timestampMs * 90).toInt()  // 90kHz 时间戳

            while (offset < encodedData.size) {
                val chunkSize = minOf(mtu, encodedData.size - offset)
                val marker = (offset + chunkSize >= encodedData.size)

                val rtpPacket = buildRtpPacket(
                    encodedData, offset, chunkSize,
                    timestamp, marker
                )
                val packet = DatagramPacket(
                    rtpPacket, rtpPacket.size,
                    InetAddress.getByName(targetIp), targetPort
                )
                sock.send(packet)
                offset += chunkSize
                sequenceNumber = (sequenceNumber + 1) and 0xFFFF
            }
        } catch (e: Exception) {
            Timber.e(e, "RtpSender: send error")
        }
    }

    private fun buildRtpPacket(
        data: ByteArray, offset: Int, length: Int,
        timestamp: Int, marker: Boolean
    ): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()                    // V=2, P=0, X=0, CC=0
        header[1] = ((if (marker) 0x80 else 0x00) or payloadType).toByte()
        header[2] = (sequenceNumber shr 8).toByte()
        header[3] = (sequenceNumber and 0xFF).toByte()
        header[4] = (timestamp shr 24).toByte()
        header[5] = (timestamp shr 16).toByte()
        header[6] = (timestamp shr 8).toByte()
        header[7] = (timestamp and 0xFF).toByte()
        header[8] = (ssrc shr 24).toByte()
        header[9] = (ssrc shr 16).toByte()
        header[10] = (ssrc shr 8).toByte()
        header[11] = (ssrc and 0xFF).toByte()

        return header + data.copyOfRange(offset, offset + length)
    }

    fun getSsrc(): String = String.format("%010d", ssrc.toLong() and 0xFFFFFFFFL)

    fun stop() {
        socket?.close()
        socket = null
        Timber.i("RtpSender: stopped")
    }
}
