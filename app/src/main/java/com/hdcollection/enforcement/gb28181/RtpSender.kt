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

    // 复用 buffer：避免每个视频帧（特别是 IDR 70+ 包）频繁 alloc 触发 GC。
    // 老 CPU (Cortex-A53) 上 GC 暂停几十毫秒，是卡顿的真正性能瓶颈。
    private val rtpBuf = ByteArray(12 + 1400)             // 单个 RTP 包复用
    private val rtpPacket = DatagramPacket(rtpBuf, rtpBuf.size)  // DatagramPacket 复用 (setSocketAddress 时创建)
    // 自定义 BAOS 子类，暴露 internal buf 避免 toByteArray() 每帧 100KB+ copy
    private class ReusableBaos(size: Int) : ByteArrayOutputStream(size) {
        fun internalBuf(): ByteArray = buf
    }
    private val psBuf = ReusableBaos(128 * 1024)          // PS 封装 buffer 复用
    private val frameMergeBuf = ByteArray(256 * 1024)     // SPS/PPS + IDR 拼接复用 buffer

    fun start() {
        socket = DatagramSocket()
        targetAddr = InetAddress.getByName(targetIp)
        rtpPacket.address = targetAddr
        rtpPacket.port = targetPort
        packetCount = 0
        byteCount = 0
        audioQueue.clear()
        Timber.i("RtpSender: started target=$targetIp:$targetPort ssrc=$ssrc localPort=${socket?.localPort}")
    }

    /**
     * 发送视频帧。spsPpsPrefix 非空时表示 IDR 帧需要拼接 SPS/PPS — 直接在
     * frameMergeBuf 拼接（不创建新数组）。
     */
    fun sendVideoFrame(encodedData: ByteArray, timestampMs: Long, isKeyFrame: Boolean = false, spsPpsPrefix: ByteArray? = null) {
        val sock = socket ?: return
        try {
            val timestamp = ((timestampMs % 0xFFFFFFFFL) * 90).toInt()

            if (USE_PS_ENCAPSULATION) {
                packPsInto(psBuf, encodedData, timestampMs, isKeyFrame, spsPpsPrefix)
                // 直接读 BAOS 内部 buf，避免 toByteArray() 每帧 100KB+ copy
                sendRtpFragments(sock, psBuf.internalBuf(), psBuf.size(), timestamp)
            } else {
                if (spsPpsPrefix != null) {
                    // 拼接到复用 buffer 而不是 spsPps + data 创建新数组
                    val total = spsPpsPrefix.size + encodedData.size
                    val merged = if (total <= frameMergeBuf.size) frameMergeBuf else ByteArray(total)
                    System.arraycopy(spsPpsPrefix, 0, merged, 0, spsPpsPrefix.size)
                    System.arraycopy(encodedData, 0, merged, spsPpsPrefix.size, encodedData.size)
                    sendRtpFragments(sock, merged, total, timestamp)
                } else {
                    sendRtpFragments(sock, encodedData, encodedData.size, timestamp)
                }
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
                sendRtpFragments(sock, psData, psData.size, timestamp)
            }
        } catch (e: Exception) {
            Timber.e(e, "RtpSender: audio flush error")
        }
    }

    companion object {
        // 切换 PS 封装开关，调试时可设为 false 直接发裸 H264
        var USE_PS_ENCAPSULATION = true
    }

    /**
     * 发送 RTP 分片。所有 buffer 都复用：rtpBuf + rtpPacket。
     * 注意：先前尝试加 LockSupport.parkNanos 做 RTP pacing 在低端硬件上反而引起卡顿
     * （nanos 精度只有 ~5ms，70 包 × 5ms = 350ms 阻塞编码线程，比一帧间隔还长）。
     * 已撤回 — 老硬件不接受 micro-sleep。
     */
    private fun sendRtpFragments(sock: DatagramSocket, data: ByteArray, dataLen: Int, timestamp: Int) {
        val mtu = 1400
        var offset = 0
        val buf = rtpBuf
        val pkt = rtpPacket

        while (offset < dataLen) {
            val chunkSize = minOf(mtu, dataLen - offset)
            val marker = (offset + chunkSize >= dataLen)
            val rtpLen = 12 + chunkSize

            // RTP header（写入复用 buffer）
            buf[0] = 0x80.toByte()
            buf[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
            buf[2] = (sequenceNumber shr 8).toByte()
            buf[3] = (sequenceNumber and 0xFF).toByte()
            buf[4] = (timestamp ushr 24).toByte()
            buf[5] = (timestamp ushr 16).toByte()
            buf[6] = (timestamp ushr 8).toByte()
            buf[7] = (timestamp and 0xFF).toByte()
            buf[8] = (ssrc ushr 24).toByte()
            buf[9] = (ssrc ushr 16).toByte()
            buf[10] = (ssrc ushr 8).toByte()
            buf[11] = (ssrc and 0xFF).toByte()

            System.arraycopy(data, offset, buf, 12, chunkSize)

            // 复用同一个 DatagramPacket：只调 setLength（target 已在 start() 设置）
            pkt.length = rtpLen
            sock.send(pkt)

            packetCount++
            byteCount += rtpLen
            if (packetCount % 500 == 1L) {
                Timber.i("RtpSender: sent $packetCount pkts, ${byteCount/1024}KB → $targetIp:$targetPort")
            }

            offset += chunkSize
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        }
    }

    /**
     * 将 H.264 数据封装为 MPEG-2 PS 包，写入复用 BAOS（reset 后追加），
     * 不创建新 byte[]。spsPpsPrefix 非空时直接拼接到 PES payload 前。
     */
    private fun packPsInto(out: ByteArrayOutputStream, h264: ByteArray, timestampMs: Long, isKeyFrame: Boolean, spsPpsPrefix: ByteArray?) {
        out.reset()
        val scr = timestampMs * 90
        val pts = scr

        writePsPackHeader(out, scr)
        if (isKeyFrame) {
            writePsSystemHeader(out)
            writePsm(out)
        }
        // PES payload：可能是单段 h264，或 spsPps + h264 两段拼接
        if (spsPpsPrefix != null) {
            writePes2Seg(out, 0xE0, spsPpsPrefix, h264, pts)
        } else {
            writePes(out, 0xE0, h264, pts)
        }
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

    /**
     * PES 两段 payload（spsPps + h264）— 避免外层先拼成一个数组再写入
     */
    private fun writePes2Seg(out: ByteArrayOutputStream, streamId: Int, seg1: ByteArray, seg2: ByteArray, pts: Long) {
        // 简单实现：两段总和 ≤ maxPesPayload 时一个 PES，否则分多个 PES
        val totalLen = seg1.size + seg2.size
        val maxPesPayload = 65500
        if (totalLen <= maxPesPayload) {
            out.write(0x00); out.write(0x00); out.write(0x01); out.write(streamId)
            val ptsHeaderLen = 5
            val pesOptHeaderLen = 3 + ptsHeaderLen
            val pesDataLen = pesOptHeaderLen + totalLen
            if (pesDataLen <= 65535) {
                out.write((pesDataLen shr 8) and 0xFF); out.write(pesDataLen and 0xFF)
            } else {
                out.write(0x00); out.write(0x00)
            }
            out.write(0x80); out.write(0x80); out.write(ptsHeaderLen)
            writePts(out, pts)
            out.write(seg1, 0, seg1.size)
            out.write(seg2, 0, seg2.size)
        } else {
            // 极少触发（单帧 > 64KB），fallback 到单段写法（先合并）
            val merged = ByteArray(totalLen)
            System.arraycopy(seg1, 0, merged, 0, seg1.size)
            System.arraycopy(seg2, 0, merged, seg1.size, seg2.size)
            writePes(out, streamId, merged, pts)
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
