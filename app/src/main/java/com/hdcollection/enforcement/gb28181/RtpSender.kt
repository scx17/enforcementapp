package com.hdcollection.enforcement.gb28181

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GB28181 RTP 发送器
 * H.264 + AAC → PS (Program Stream) → RTP
 *
 * 线程模型：所有 sendRtpFragments 调用只在视频编码线程执行，
 * 音频通过 ConcurrentLinkedQueue 入队，视频线程在每帧后刷出。
 */
class RtpSender(private val targetIp: String, private val targetPort: Int) {

    private var socket: DatagramSocket? = null
    private var targetAddr: InetAddress? = null
    private var sequenceNumber: Int = (Math.random() * 0xFFFF).toInt()
    private var ssrc: Int = (Math.random() * 0xFFFFFFFF).toLong().toInt()
    private val payloadType = 96

    fun setSsrc(value: Int) {
        ssrc = value
        Timber.i("RtpSender: SSRC set to $ssrc (0x${Integer.toHexString(ssrc)})")
    }

    private var packetCount = 0L
    private var byteCount = 0L

    // RTP pacing：大帧（IDR 等）分散在帧间隔内发送，避免老 WiFi 上行被 burst 突发丢包。
    // App 通过 setFrameRate() 告诉 sender 当前帧率，pacing 才能算出每帧多少时间窗。
    @Volatile private var frameIntervalNs: Long = 100_000_000L  // 默认 10fps = 100ms

    fun setFrameRate(fps: Int) {
        if (fps > 0) frameIntervalNs = 1_000_000_000L / fps
    }

    // 音频队列：音频编码线程入队，视频线程出队发送（无锁）
    private val audioQueue = ConcurrentLinkedQueue<Pair<ByteArray, Long>>()

    fun start() {
        socket = DatagramSocket()
        targetAddr = InetAddress.getByName(targetIp)
        packetCount = 0
        byteCount = 0
        audioQueue.clear()
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

    /**
     * 音频编码线程调用：将 AAC 帧入队（非阻塞）
     */
    fun queueAudioFrame(aacData: ByteArray, timestampMs: Long) {
        audioQueue.offer(Pair(aacData, timestampMs))
    }

    /**
     * 视频编码线程调用：在每个视频帧发送后，刷出所有排队的音频帧。
     * 保证 RTP 包不会交叉，消除锁竞争。
     */
    fun flushAudioQueue() {
        val sock = socket ?: return
        try {
            while (true) {
                val pair = audioQueue.poll() ?: break
                val (aacData, timestampMs) = pair
                val timestamp = ((timestampMs % 0xFFFFFFFFL) * 90).toInt()
                val adtsData = addAdtsHeader(aacData)
                val psData = packAudioPs(adtsData, timestampMs)
                sendRtpFragments(sock, psData, timestamp)
            }
        } catch (e: Exception) {
            Timber.e(e, "RtpSender: audio flush error")
        }
    }

    companion object {
        // 切换 PS 封装开关，调试时可设为 false 直接发裸 H264
        var USE_PS_ENCAPSULATION = true
    }

    private fun sendRtpFragments(sock: DatagramSocket, data: ByteArray, timestamp: Int) {
        val mtu = 1400
        var offset = 0
        val addr = targetAddr ?: return

        // 计算包数 + pacing 间隔。包数少（≤6，普通 P 帧）直接 burst（pacing 反而增加延迟）；
        // 包数多（IDR 等大帧）按 帧间隔 60% / 包数 分散发送，留 40% 给后续帧编码。
        val totalPackets = (data.size + mtu - 1) / mtu
        val useInterPacketDelay = totalPackets > 6
        val interPacketNs = if (useInterPacketDelay) {
            (frameIntervalNs * 6 / 10) / totalPackets
        } else 0L

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
                Timber.i("RtpSender: sent $packetCount pkts, ${byteCount/1024}KB → $targetIp:$targetPort, paced=$useInterPacketDelay")
            }

            offset += chunkSize
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF

            // pacing：包间 micro-sleep 让 WiFi 上行有空隙吞包，不被 burst 撑爆
            if (interPacketNs > 0 && offset < data.size) {
                java.util.concurrent.locks.LockSupport.parkNanos(interPacketNs)
            }
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
     */
    private fun writePsPackHeader(out: ByteArrayOutputStream, scr90k: Long) {
        // Start code: 00 00 01 BA
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBA)

        val scrBase = (scr90k / 300) and 0x1FFFFFFFFL
        val scrExt = (scr90k % 300).toInt() and 0x1FF

        val s32_30 = ((scrBase shr 30) and 0x07).toInt()
        val s29_15 = ((scrBase shr 15) and 0x7FFF).toInt()
        val s14_0  = (scrBase and 0x7FFF).toInt()

        out.write(0x44 or (s32_30 shl 3) or ((s29_15 shr 13) and 0x03))
        out.write((s29_15 shr 5) and 0xFF)
        out.write(((s29_15 and 0x1F) shl 3) or 0x04 or ((s14_0 shr 13) and 0x03))
        out.write((s14_0 shr 5) and 0xFF)
        out.write(((s14_0 and 0x1F) shl 3) or 0x04 or ((scrExt shr 7) and 0x03))
        out.write(((scrExt and 0x7F) shl 1) or 0x01)

        val muxRate = 6106
        out.write((0x80 or ((muxRate shr 14) and 0x7F)))
        out.write((muxRate shr 6) and 0xFF)
        out.write(((muxRate and 0x3F) shl 2) or 0x03)

        out.write(0xF8)
    }

    /**
     * PS System Header
     * ISO 13818-1 Section 2.5.3.5
     */
    private fun writePsSystemHeader(out: ByteArrayOutputStream) {
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBB)

        // header_length = 6 + 3(视频) + 3(音频) = 12
        out.write(0x00); out.write(0x0C)

        val rateBound = 50400
        out.write(0x80 or ((rateBound shr 15) and 0x7F))
        out.write((rateBound shr 7) and 0xFF)
        out.write(((rateBound shl 1) and 0xFE) or 0x01)

        // audio_bound(6)=1 + fixed_flag(1)=0 + CSPS_flag(1)=0
        out.write(0x04)
        out.write(0x61)
        out.write(0x7F)

        // Video stream (E0)
        out.write(0xE0); out.write(0xE0); out.write(0x20)
        // Audio stream (C0)
        out.write(0xC0); out.write(0xC0); out.write(0x20)
    }

    /**
     * Program Stream Map (PSM)
     * ISO 13818-1 Section 2.5.4
     */
    private fun writePsm(out: ByteArrayOutputStream) {
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xBC)

        // length = 2+2+2+8+4 = 18
        out.write(0x00); out.write(0x12)
        out.write(0xE0)
        out.write(0xFF)
        out.write(0x00); out.write(0x00)

        // es_map_length = 8 (视频 + 音频)
        out.write(0x00); out.write(0x08)
        // Video: H.264
        out.write(0x1B); out.write(0xE0); out.write(0x00); out.write(0x00)
        // Audio: AAC (MPEG-4 AAC/ADTS, stream_type=0x0F)
        out.write(0x0F); out.write(0xC0); out.write(0x00); out.write(0x00)

        // CRC_32
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x00)
    }

    /**
     * PES Packet
     */
    private fun writePes(out: ByteArrayOutputStream, streamId: Int, payload: ByteArray, pts: Long) {
        val maxPesPayload = 65500
        var offset = 0

        while (offset < payload.size) {
            val chunkSize = minOf(maxPesPayload, payload.size - offset)
            val isFirst = (offset == 0)

            out.write(0x00); out.write(0x00); out.write(0x01); out.write(streamId)

            val ptsHeaderLen = if (isFirst) 5 else 0
            val pesOptHeaderLen = 3 + ptsHeaderLen
            val pesDataLen = pesOptHeaderLen + chunkSize

            if (pesDataLen <= 65535) {
                out.write((pesDataLen shr 8) and 0xFF)
                out.write(pesDataLen and 0xFF)
            } else {
                out.write(0x00); out.write(0x00)
            }

            out.write(0x80)

            if (isFirst) {
                out.write(0x80)
                out.write(ptsHeaderLen)
                writePts(out, pts)
            } else {
                out.write(0x00)
                out.write(0x00)
            }

            out.write(payload, offset, chunkSize)
            offset += chunkSize
        }
    }

    private fun writePts(out: ByteArrayOutputStream, pts: Long) {
        val v = pts and 0x1FFFFFFFFL
        out.write((0x21 or ((v shr 29) and 0x0E).toInt()))
        out.write(((v shr 22) and 0xFF).toInt())
        out.write((0x01 or ((v shr 14) and 0xFE).toInt()))
        out.write(((v shr 7) and 0xFF).toInt())
        out.write((0x01 or ((v shl 1) and 0xFE).toInt()))
    }

    /**
     * 将 AAC ADTS 音频封装为 PS 包
     */
    private fun packAudioPs(adtsData: ByteArray, timestampMs: Long): ByteArray {
        val out = ByteArrayOutputStream(adtsData.size + 50)
        val pts = timestampMs * 90
        writePsPackHeader(out, pts)
        writePes(out, 0xC0, adtsData, pts)
        return out.toByteArray()
    }

    /**
     * 为 AAC 帧添加 ADTS 头（7 bytes, 无 CRC）
     * 参数: 16kHz, Mono, AAC-LC
     */
    private fun addAdtsHeader(aacData: ByteArray): ByteArray {
        val frameLength = 7 + aacData.size
        val profile = 1   // AAC-LC
        val freqIdx = 8   // 16kHz
        val chanCfg = 1   // Mono

        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        header[2] = ((profile shl 6) or (freqIdx shl 2) or (chanCfg shr 2)).toByte()
        header[3] = (((chanCfg and 3) shl 6) or (frameLength shr 11)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()

        return header + aacData
    }

    fun getSsrc(): String = String.format("%010d", ssrc.toLong() and 0xFFFFFFFFL)

    fun stop() {
        audioQueue.clear()
        socket?.close()
        socket = null
        targetAddr = null
        Timber.i("RtpSender: stopped")
    }
}
