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

    private var packetCount = 0L
    private var byteCount = 0L

    fun start() {
        socket = DatagramSocket()
        packetCount = 0
        byteCount = 0
        Timber.i("RtpSender: started target=$targetIp:$targetPort ssrc=$ssrc localPort=${socket?.localPort}")
    }

    fun sendVideoFrame(encodedData: ByteArray, timestampMs: Long, isKeyFrame: Boolean = false) {
        val sock = socket ?: return
        try {
            val timestamp = ((timestampMs % 0xFFFFFFFFL) * 90).toInt()

            // GB28181 要求 PS 封装，但如果 PS 解析有问题可以切换
            if (USE_PS_ENCAPSULATION) {
                val psData = packPs(encodedData, timestampMs, isKeyFrame)
                if (isKeyFrame) {
                    // 打印关键帧 PS 包前 80 字节的 hex dump，用于调试 PS 格式
                    val hexDump = psData.take(80).joinToString(" ") { String.format("%02X", it) }
                    Timber.w("PS-DEBUG keyframe PS[${psData.size}B] H264[${encodedData.size}B]: $hexDump")
                }
                sendRtpFragments(sock, psData, timestamp)
            } else {
                // 直接发送 H264 over RTP（RFC 6184, PT=96）
                sendRtpFragments(sock, encodedData, timestamp)
            }
        } catch (e: Exception) {
            Timber.e(e, "RtpSender: send error")
        }
    }

    companion object {
        // 切换 PS 封装开关，调试时可设为 false 直接发裸 H264
        var USE_PS_ENCAPSULATION = true
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
            packetCount++
            byteCount += rtp.size
            if (packetCount % 200 == 1L) {
                Timber.i("RtpSender: sent $packetCount pkts, ${byteCount/1024}KB → $targetIp:$targetPort")
            }

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

        // 2. System Header + PSM (仅关键帧)
        // 注意：某些 ZLM 版本的 libmpeg 对 System Header/PSM 格式校验严格
        // 如果视频无法播放，可以尝试注释掉 System Header 和 PSM
        if (isKeyFrame) {
            writePsSystemHeader(out)
            writePsm(out)
        }

        // 3. PES (Video) - H264 数据必须包含 Annex-B startcode (00 00 00 01)
        writePes(out, 0xE0, h264, pts)

        return out.toByteArray()
    }

    /**
     * PS Pack Header - 14 bytes
     * ISO 13818-1 Section 2.5.3.3
     * 参考 ireader/media-server mpeg-ps-enc.c 的实现
     */
    private fun writePsPackHeader(out: ByteArrayOutputStream, scr90k: Long) {
        // Start code: 00 00 01 BA
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBA)

        // SCR = scr_base * 300 + scr_ext
        // 我们直接传入 90kHz 的值，scr_base = scr90k / 300, scr_ext = scr90k % 300
        val scrBase = (scr90k / 300) and 0x1FFFFFFFFL
        val scrExt = (scr90k % 300).toInt() and 0x1FF

        // MPEG-2 SCR 编码（6 bytes, 48 bits）:
        // '01' SCR_base[32..30] '1' SCR_base[29..15] '1' SCR_base[14..0] '1' SCR_ext[8..0] '1'
        //  2    3                1   15                1   15              1   9              1  = 48 bits = 6 bytes

        val s32_30 = ((scrBase shr 30) and 0x07).toInt()
        val s29_15 = ((scrBase shr 15) and 0x7FFF).toInt()
        val s14_0  = (scrBase and 0x7FFF).toInt()

        out.write(0x44 or (s32_30 shl 3) or ((s29_15 shr 13) and 0x03))            // '01' + SCR[32..30] + '1' + SCR[29..28]
        out.write((s29_15 shr 5) and 0xFF)                                           // SCR[27..20]
        out.write(((s29_15 and 0x1F) shl 3) or 0x04 or ((s14_0 shr 13) and 0x03))  // SCR[19..15] + '1' + SCR[14..13]
        out.write((s14_0 shr 5) and 0xFF)                                            // SCR[12..5]
        out.write(((s14_0 and 0x1F) shl 3) or 0x04 or ((scrExt shr 7) and 0x03))   // SCR[4..0] + '1' + ext[8..7]
        out.write(((scrExt and 0x7F) shl 1) or 0x01)                                // ext[6..0] + '1'

        // program_mux_rate (22 bits) + marker(1) + marker(1): 3 bytes
        val muxRate = 6106  // ~= 300KB/s, 常用值
        out.write((0x80 or ((muxRate shr 14) and 0x7F)))
        out.write((muxRate shr 6) and 0xFF)
        out.write(((muxRate and 0x3F) shl 2) or 0x03)

        // reserved(5, 全1) + pack_stuffing_length(3, =0)
        out.write(0xF8)
    }

    /**
     * PS System Header
     * ISO 13818-1 Section 2.5.3.5
     */
    private fun writePsSystemHeader(out: ByteArrayOutputStream) {
        // Start code: 00 00 01 BB
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBB)

        // header_length = 6 + 3(仅视频) = 9
        out.write(0x00); out.write(0x09)

        // rate_bound: marker(1) + rate_bound(22) + marker(1) = 3 bytes
        val rateBound = 50400
        out.write(0x80 or ((rateBound shr 15) and 0x7F))
        out.write((rateBound shr 7) and 0xFF)
        out.write(((rateBound shl 1) and 0xFE) or 0x01)

        // audio_bound(6)=0 + fixed_flag(1)=0 + CSPS_flag(1)=0
        out.write(0x00)
        // system_audio_lock_flag(1)=0 + system_video_lock_flag(1)=1 + marker(1)=1 + video_bound(5)=1
        out.write(0x61)
        // packet_rate_restriction_flag(1)=0 + reserved(7)=1111111
        out.write(0x7F)

        // 仅声明 Video stream (E0)，不声明 Audio
        out.write(0xE0)
        // '11' + P-STD_buffer_bound_scale(1)=1 + P-STD_buffer_size_bound(13)=512
        out.write(0xE0)
        out.write(0x20)
    }

    /**
     * Program Stream Map (PSM)
     * ISO 13818-1 Section 2.5.4
     */
    private fun writePsm(out: ByteArrayOutputStream) {
        // Start code: 00 00 01 BC
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBC)

        // program_stream_map_length = 14 (2+2+2+4+4 = current_next+reserved + ps_info_len + es_map_len + 1 video stream + CRC32)
        out.write(0x00); out.write(0x0E)

        // current_next_indicator(1)=1 + reserved(2)=11 + program_stream_map_version(5)=0
        out.write(0xE0)

        // reserved(7)=1111111 + marker(1)=1
        out.write(0xFF)

        // program_stream_info_length = 0
        out.write(0x00); out.write(0x00)

        // elementary_stream_map_length = 4 (仅 1 个视频流 * 4 bytes)
        out.write(0x00); out.write(0x04)

        // Video: stream_type=0x1B(H.264) + elementary_stream_id=0xE0 + ES_info_length=0
        out.write(0x1B); out.write(0xE0); out.write(0x00); out.write(0x00)

        // CRC_32 (4 bytes) - 设为 0（ZLM 不校验 CRC）
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x00)
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

            // '10' + PES_scrambling_control(2)=00 + PES_priority=0 + data_alignment=0 + copyright=0 + original_or_copy=0
            out.write(0x80)

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
