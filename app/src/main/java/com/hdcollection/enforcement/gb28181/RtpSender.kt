package com.hdcollection.enforcement.gb28181

import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * GB28181 RTP 发送器
 * 将 H.264 编码数据封装为 PS (Program Stream) 格式，再打包为 RTP 发送
 * 符合 GB/T 28181-2016 附录 B 的要求
 */
class RtpSender(private val targetIp: String, private val targetPort: Int) {

    private var socket: DatagramSocket? = null
    private var sequenceNumber: Int = (Math.random() * 0xFFFF).toInt()
    private var ssrc: Int = (Math.random() * 0xFFFFFFFF).toLong().toInt()
    private val payloadType = 96  // PS over RTP, 90kHz

    fun start() {
        socket = DatagramSocket()
        Timber.i("RtpSender: started, target=$targetIp:$targetPort, ssrc=$ssrc")
    }

    /**
     * 发送一帧 H.264 视频数据
     * @param encodedData MediaCodec 编码输出的 H.264 数据（可能包含 SPS/PPS/IDR/P 帧）
     * @param timestampMs 毫秒时间戳
     * @param isKeyFrame 是否为关键帧
     */
    fun sendVideoFrame(encodedData: ByteArray, timestampMs: Long, isKeyFrame: Boolean = false) {
        val sock = socket ?: return
        try {
            val timestamp = (timestampMs * 90).toInt()  // 转为 90kHz 时间戳

            // 将 H.264 数据封装为 PS 包
            val psPacket = buildPsPacket(encodedData, timestampMs, isKeyFrame)

            // 将 PS 包拆分为 RTP 包发送
            val mtu = 1400
            var offset = 0

            while (offset < psPacket.size) {
                val chunkSize = minOf(mtu, psPacket.size - offset)
                val marker = (offset + chunkSize >= psPacket.size)

                val rtpPacket = buildRtpPacket(
                    psPacket, offset, chunkSize,
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

    /**
     * 构建 MPEG-PS 包
     * 结构：PS Header + PS System Header(关键帧时) + PSM(关键帧时) + PES(H.264)
     */
    private fun buildPsPacket(h264Data: ByteArray, timestampMs: Long, isKeyFrame: Boolean): ByteArray {
        val scr = timestampMs * 90  // SCR 也用 90kHz
        val parts = mutableListOf<ByteArray>()

        // 1. PS Pack Header (14 bytes)
        parts.add(buildPsPackHeader(scr))

        // 2. PS System Header (关键帧时发送)
        if (isKeyFrame) {
            parts.add(buildPsSystemHeader())
        }

        // 3. PSM - Program Stream Map (关键帧时发送)
        if (isKeyFrame) {
            parts.add(buildPsm())
        }

        // 4. PES 包 - 封装 H.264 数据
        parts.add(buildPesPacket(h264Data, timestampMs))

        // 合并
        var totalSize = 0
        parts.forEach { totalSize += it.size }
        val result = ByteArray(totalSize)
        var pos = 0
        parts.forEach {
            System.arraycopy(it, 0, result, pos, it.size)
            pos += it.size
        }
        return result
    }

    /**
     * PS Pack Header (14 bytes)
     * 00 00 01 BA + SCR + mux_rate + stuffing
     */
    private fun buildPsPackHeader(scr: Long): ByteArray {
        val header = ByteArray(14)
        // Pack start code: 00 00 01 BA
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xBA.toByte()

        // SCR (System Clock Reference) - MPEG-2 格式
        // '01' + SCR[32..30] + marker + SCR[29..15] + marker + SCR[14..0] + marker + SCR_ext + marker
        val scrBase = scr and 0x1FFFFFFFFL
        val scrExt = 0

        header[4] = (0x44 or                                          // '01' + SCR[32..30]
                ((scrBase shr 27) and 0x38).toInt() or
                ((scrBase shr 28) and 0x03).toInt()).toByte()
        header[5] = ((scrBase shr 20) and 0xFF).toInt().toByte()
        header[6] = (0x04 or                                          // marker
                ((scrBase shr 12) and 0xF8).toInt() or
                ((scrBase shr 13) and 0x03).toInt()).toByte()
        header[7] = ((scrBase shr 5) and 0xFF).toInt().toByte()
        header[8] = (0x04 or                                          // marker
                ((scrBase shl 3) and 0xF8).toInt() or
                ((scrExt shr 7) and 0x03)).toByte()

        // Mux rate (固定 50400 * 50 = 25200 bytes/s)
        val muxRate = 50400
        header[9] = ((muxRate shr 14) and 0xFF).toByte()
        header[10] = ((muxRate shr 6) and 0xFF).toByte()
        header[11] = (((muxRate shl 2) and 0xFC) or 0x03).toByte()

        // Pack stuffing length = 0
        header[12] = 0xFE.toByte()  // reserved + stuffing_length=0 (0b11111110)
        header[13] = 0x00           // padding

        return header.copyOf(13) // 实际 13 bytes 即可（MPEG-2 PS header without stuffing byte）
    }

    /**
     * PS System Header
     * 00 00 01 BB + header_length + rate_bound + audio/video stream info
     */
    private fun buildPsSystemHeader(): ByteArray {
        val header = ByteArray(18)
        // System Header start code: 00 00 01 BB
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xBB.toByte()

        // Header length (不含前6字节)
        val headerLen = 12
        header[4] = ((headerLen shr 8) and 0xFF).toByte()
        header[5] = (headerLen and 0xFF).toByte()

        // Rate bound (50400)
        val rateBound = 50400
        header[6] = (0x80 or ((rateBound shr 15) and 0x7F)).toByte()
        header[7] = ((rateBound shr 7) and 0xFF).toByte()
        header[8] = (((rateBound shl 1) and 0xFE) or 0x01).toByte()

        // Audio bound=0, fixed=0, CSPS=0
        header[9] = 0x04.toByte()  // audio_bound=0, fixed_flag=0, CSPS_flag=0
        // System_audio_lock=1, System_video_lock=1, video_bound=1
        header[10] = 0xE1.toByte()
        // packet_rate_restriction=0
        header[11] = 0x7F.toByte()

        // Video stream (0xE0) - H.264
        header[12] = 0xE0.toByte() // stream_id
        header[13] = 0xC0.toByte() // '11' + P-STD_buffer_bound_scale=0
        header[14] = 0x00.toByte() // P-STD_buffer_size_bound=0

        // Audio stream (0xC0) - placeholder
        header[15] = 0xC0.toByte()
        header[16] = 0xC0.toByte()
        header[17] = 0x00.toByte()

        return header
    }

    /**
     * Program Stream Map (PSM)
     * 00 00 01 BC + length + info
     */
    private fun buildPsm(): ByteArray {
        val psm = ByteArray(24)
        // PSM start code: 00 00 01 BC
        psm[0] = 0x00
        psm[1] = 0x00
        psm[2] = 0x01
        psm[3] = 0xBC.toByte()

        // Length (不含前6字节) = 18
        val psmLen = 18
        psm[4] = ((psmLen shr 8) and 0xFF).toByte()
        psm[5] = (psmLen and 0xFF).toByte()

        // current_next_indicator=1, reserved, psm_version=0
        psm[6] = 0xE0.toByte()
        psm[7] = 0xFF.toByte()

        // PS info length = 0
        psm[8] = 0x00
        psm[9] = 0x00

        // Elementary stream map length = 8 (一个视频流 + 一个音频流的描述)
        psm[10] = 0x00
        psm[11] = 0x08

        // Video stream info: stream_type=0x1B (H.264), stream_id=0xE0
        psm[12] = 0x1B   // H.264
        psm[13] = 0xE0.toByte()  // Video stream id
        psm[14] = 0x00   // ES info length
        psm[15] = 0x00

        // Audio stream info: stream_type=0x90 (G.711), stream_id=0xC0
        psm[16] = 0x90.toByte()  // G.711
        psm[17] = 0xC0.toByte()  // Audio stream id
        psm[18] = 0x00
        psm[19] = 0x00

        // CRC32 (简化处理，设为0)
        psm[20] = 0x00
        psm[21] = 0x00
        psm[22] = 0x00
        psm[23] = 0x00

        return psm
    }

    /**
     * PES 包 - 封装 H.264 数据
     * 00 00 01 E0 + length + flags + PTS + payload
     */
    private fun buildPesPacket(h264Data: ByteArray, timestampMs: Long): ByteArray {
        val pts = timestampMs * 90  // 90kHz
        val pesHeaderDataLen = 5     // PTS 占 5 字节
        val pesHeaderLen = 9 + pesHeaderDataLen  // PES header 固定 9 字节 + optional header

        // PES 包最大 65535 字节，如果超出则设为 0（无限制）
        val pesPayloadLen = h264Data.size
        val pesPacketLen = 3 + pesHeaderDataLen + pesPayloadLen  // 从 flags 开始算

        val header = ByteArray(pesHeaderLen)
        // PES start code: 00 00 01 E0 (video)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xE0.toByte()  // Video stream

        // PES packet length (0 = unbounded for video)
        if (pesPacketLen <= 65535) {
            header[4] = ((pesPacketLen shr 8) and 0xFF).toByte()
            header[5] = (pesPacketLen and 0xFF).toByte()
        } else {
            header[4] = 0x00
            header[5] = 0x00
        }

        // Flags: '10' + scrambling=00 + priority=0 + alignment=1 + copyright=0 + original=0
        header[6] = 0x80.toByte()
        // PTS_DTS_flags='10' (PTS only) + other flags = 0
        header[7] = 0x80.toByte()
        // PES header data length
        header[8] = pesHeaderDataLen.toByte()

        // PTS (5 bytes): '0010' + PTS[32..30] + '1' + PTS[29..15] + '1' + PTS[14..0] + '1'
        val ptsVal = pts and 0x1FFFFFFFFL
        header[9] = (0x21 or ((ptsVal shr 29) and 0x0E).toInt()).toByte()
        header[10] = ((ptsVal shr 22) and 0xFF).toInt().toByte()
        header[11] = (0x01 or ((ptsVal shr 14) and 0xFE).toInt()).toByte()
        header[12] = ((ptsVal shr 7) and 0xFF).toInt().toByte()
        header[13] = (0x01 or ((ptsVal shl 1) and 0xFE).toInt()).toByte()

        // 合并 header + payload
        val result = ByteArray(pesHeaderLen + pesPayloadLen)
        System.arraycopy(header, 0, result, 0, pesHeaderLen)
        System.arraycopy(h264Data, 0, result, pesHeaderLen, pesPayloadLen)
        return result
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

        val result = ByteArray(12 + length)
        System.arraycopy(header, 0, result, 0, 12)
        System.arraycopy(data, offset, result, 12, length)
        return result
    }

    fun getSsrc(): String = String.format("%010d", ssrc.toLong() and 0xFFFFFFFFL)

    fun stop() {
        socket?.close()
        socket = null
        Timber.i("RtpSender: stopped")
    }
}
