package com.hdcollection.enforcement.gb28181

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * GB28181 RTP 发送器
 * H.264 → PS (Program Stream) → RTP
 */
class RtpSender(private val targetIp: String, private val targetPort: Int) {

    private var socket: DatagramSocket? = null
    private var sequenceNumber: Int = (Math.random() * 0xFFFF).toInt()
    private var ssrc: Int = (Math.random() * 0xFFFFFFFF).toLong().toInt()
    private val payloadType = 96

    fun setSsrc(value: Int) {
        ssrc = value
        Timber.i("RtpSender: SSRC set to $ssrc (0x${Integer.toHexString(ssrc)})")
    }

    fun start() {
        socket = DatagramSocket()
        Timber.i("RtpSender: started target=$targetIp:$targetPort ssrc=$ssrc")
    }

    fun sendVideoFrame(encodedData: ByteArray, timestampMs: Long, isKeyFrame: Boolean = false) {
        val sock = socket ?: return
        try {
            val timestamp = ((timestampMs % 0xFFFFFFFFL) * 90).toInt()
            val psData = packPs(encodedData, timestampMs, isKeyFrame)
            sendRtpFragments(sock, psData, timestamp)
        } catch (e: Exception) {
            Timber.e(e, "RtpSender: send error")
        }
    }

    private fun sendRtpFragments(sock: DatagramSocket, data: ByteArray, timestamp: Int) {
        val mtu = 1400
        var offset = 0
        val addr = InetAddress.getByName(targetIp)

        while (offset < data.size) {
            val chunkSize = minOf(mtu, data.size - offset)
            val marker = (offset + chunkSize >= data.size)

            val rtp = ByteArray(12 + chunkSize)
            // RTP header
            rtp[0] = 0x80.toByte()
            rtp[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
            rtp[2] = (sequenceNumber shr 8).toByte()
            rtp[3] = (sequenceNumber and 0xFF).toByte()
            rtp[4] = (timestamp ushr 24).toByte()
            rtp[5] = (timestamp ushr 16).toByte()
            rtp[6] = (timestamp ushr 8).toByte()
            rtp[7] = (timestamp and 0xFF).toByte()
            rtp[8] = (ssrc ushr 24).toByte()
            rtp[9] = (ssrc ushr 16).toByte()
            rtp[10] = (ssrc ushr 8).toByte()
            rtp[11] = (ssrc and 0xFF).toByte()

            System.arraycopy(data, offset, rtp, 12, chunkSize)
            sock.send(DatagramPacket(rtp, rtp.size, addr, targetPort))

            offset += chunkSize
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        }
    }

    /**
     * 将 H.264 数据封装为 MPEG-2 PS 包
     */
    private fun packPs(h264: ByteArray, timestampMs: Long, isKeyFrame: Boolean): ByteArray {
        val out = ByteArrayOutputStream(h264.size + 200)
        val scr = timestampMs * 90
        val pts = scr

        // 1. PS Pack Header (14 bytes)
        writePsPackHeader(out, scr)

        // 2. System Header + PSM (关键帧时)
        if (isKeyFrame) {
            writePsSystemHeader(out)
            writePsm(out)
        }

        // 3. PES (Video)
        writePes(out, 0xE0, h264, pts)

        return out.toByteArray()
    }

    /**
     * PS Pack Header - 14 bytes
     * ISO 13818-1 Section 2.5.3.3
     */
    private fun writePsPackHeader(out: ByteArrayOutputStream, scr: Long) {
        // Start code: 00 00 01 BA
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBA)

        val scrBase = (scr / 300) and 0x1FFFFFFFFL
        val scrExt = (scr % 300).toInt() and 0x1FF

        // Byte 4: '01' + SCR[32..30] + '1'
        val b4 = 0x44 or (((scrBase shr 27) and 0x38).toInt()) or (((scrBase shr 28) and 0x03).toInt())
        out.write(b4)

        // Byte 5: SCR[29..22]
        out.write(((scrBase shr 20) and 0xFF).toInt())

        // Byte 6: SCR[21..15] + '1'
        val b6 = (((scrBase shr 12) and 0xF8).toInt()) or 0x04 or (((scrBase shr 13) and 0x03).toInt())
        out.write(b6)

        // Byte 7: SCR[14..7]
        out.write(((scrBase shr 5) and 0xFF).toInt())

        // Byte 8: SCR[6..0] + '1' + SCR_ext[8..7]
        val b8 = (((scrBase shl 3) and 0xF8).toInt()) or 0x04 or ((scrExt shr 7) and 0x03)
        out.write(b8)

        // Byte 9: SCR_ext[6..0] + '1'
        out.write(((scrExt shl 1) and 0xFE) or 0x01)

        // Mux rate: 22 bits + 2 marker bits = 3 bytes
        // mux_rate = 50400 (单位 50 bytes/s, 即 2520000 bytes/s)
        val muxRate = 50400
        out.write((muxRate shr 14) and 0xFF)
        out.write((muxRate shr 6) and 0xFF)
        out.write(((muxRate shl 2) and 0xFC) or 0x03)

        // Reserved(5) + stuffing_length(3) = 0xF8 表示 reserved=11111, length=0
        out.write(0xF8)
    }

    /**
     * PS System Header
     * ISO 13818-1 Section 2.5.3.5
     */
    private fun writePsSystemHeader(out: ByteArrayOutputStream) {
        // Start code: 00 00 01 BB
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBB)

        // header_length = 6 + 3*2(video+audio) = 12
        out.write(0x00); out.write(0x0C)

        // rate_bound: marker(1) + rate_bound(22) + marker(1) = 3 bytes
        val rateBound = 50400
        out.write(0x80 or ((rateBound shr 15) and 0x7F))
        out.write((rateBound shr 7) and 0xFF)
        out.write(((rateBound shl 1) and 0xFE) or 0x01)

        // audio_bound(6) + fixed_flag(1) + CSPS_flag(1)
        out.write(0x04)
        // system_audio_lock_flag(1) + system_video_lock_flag(1) + marker(1) + video_bound(5)
        out.write(0xE1)
        // packet_rate_restriction_flag(1) + reserved(7)
        out.write(0x7F)

        // Stream 1: Video E0
        out.write(0xE0)
        // '11' + P-STD_buffer_bound_scale(1)=1 + P-STD_buffer_size_bound(13)=512
        out.write(0xE0)
        out.write(0x20)

        // Stream 2: Audio C0
        out.write(0xC0)
        // '11' + P-STD_buffer_bound_scale(1)=0 + P-STD_buffer_size_bound(13)=64
        out.write(0xC0)
        out.write(0x40)
    }

    /**
     * Program Stream Map (PSM)
     * ISO 13818-1 Section 2.5.4
     */
    private fun writePsm(out: ByteArrayOutputStream) {
        // Start code: 00 00 01 BC
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBC)

        // program_stream_map_length = 16
        out.write(0x00); out.write(0x10)

        // current_next_indicator(1)=1 + reserved(2) + program_stream_map_version(5)=1
        out.write(0xE1)

        // reserved(7) + marker(1)
        out.write(0xFF)

        // program_stream_info_length = 0
        out.write(0x00); out.write(0x00)

        // elementary_stream_map_length = 8 (2 streams * 4 bytes each)
        out.write(0x00); out.write(0x08)

        // Video: stream_type=0x1B(H.264) + elementary_stream_id=0xE0 + ES_info_length=0
        out.write(0x1B); out.write(0xE0); out.write(0x00); out.write(0x00)

        // Audio: stream_type=0x90(G.711) + elementary_stream_id=0xC0 + ES_info_length=0
        out.write(0x90); out.write(0xC0); out.write(0x00); out.write(0x00)

        // CRC_32 (4 bytes) - 简化为固定值
        out.write(0x45); out.write(0xBD); out.write(0xDC); out.write(0xF4)
    }

    /**
     * PES Packet
     * ISO 13818-1 Section 2.4.3.7
     */
    private fun writePes(out: ByteArrayOutputStream, streamId: Int, payload: ByteArray, pts: Long) {
        // 如果 payload 太大，分割为多个 PES 包（每个最大 65500 字节）
        val maxPesPayload = 65500
        var offset = 0

        while (offset < payload.size) {
            val chunkSize = minOf(maxPesPayload, payload.size - offset)
            val isFirst = (offset == 0)

            // Start code: 00 00 01 + stream_id
            out.write(0x00); out.write(0x00); out.write(0x01); out.write(streamId)

            val ptsHeaderLen = if (isFirst) 5 else 0
            val pesOptHeaderLen = 3 + ptsHeaderLen
            val pesDataLen = pesOptHeaderLen + chunkSize

            // PES_packet_length (0 for unbounded video, otherwise actual length)
            if (pesDataLen <= 65535) {
                out.write((pesDataLen shr 8) and 0xFF)
                out.write(pesDataLen and 0xFF)
            } else {
                out.write(0x00); out.write(0x00)
            }

            // '10' + PES_scrambling_control(2)=00 + PES_priority=0 + data_alignment=1 + copyright=0 + original_or_copy=0
            out.write(0x84)

            // PTS_DTS_flags + other flags
            if (isFirst) {
                out.write(0x80) // PTS only
                out.write(ptsHeaderLen) // PES_header_data_length = 5
                writePts(out, pts)
            } else {
                out.write(0x00) // No PTS
                out.write(0x00) // PES_header_data_length = 0
            }

            // Payload
            out.write(payload, offset, chunkSize)
            offset += chunkSize
        }
    }

    /**
     * Write PTS (5 bytes)
     * '0010' + PTS[32..30] + '1' + PTS[29..15] + '1' + PTS[14..0] + '1'
     */
    private fun writePts(out: ByteArrayOutputStream, pts: Long) {
        val v = pts and 0x1FFFFFFFFL
        out.write((0x21 or ((v shr 29) and 0x0E).toInt()))
        out.write(((v shr 22) and 0xFF).toInt())
        out.write((0x01 or ((v shr 14) and 0xFE).toInt()))
        out.write(((v shr 7) and 0xFF).toInt())
        out.write((0x01 or ((v shl 1) and 0xFE).toInt()))
    }

    fun getSsrc(): String = String.format("%010d", ssrc.toLong() and 0xFFFFFFFFL)

    fun stop() {
        socket?.close()
        socket = null
        Timber.i("RtpSender: stopped")
    }
}
