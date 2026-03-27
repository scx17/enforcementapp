package com.hdcollection.enforcement.sip

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.gb28181.SipMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

/**
 * SIP 对讲管理器
 * 复用 GB28181 SIP 消息格式，通过独立 UDP socket 处理对讲信令
 * 音频：AudioRecord（麦克风）→ RTP UDP → 对端
 *        对端 RTP UDP → AudioTrack（扬声器）
 */
class SipManager(private val settings: AppSettings) {

    // ── 状态 ──────────────────────────────────────
    enum class State { IDLE, INCOMING, IN_CALL }
    var state = State.IDLE
        private set

    var onIncomingCall: ((callerUri: String) -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null
    var onCallConnected: (() -> Unit)? = null

    // ── SIP 信令 socket ───────────────────────────
    private var sipSocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var listenJob: Job? = null

    // 当前通话参数
    private var callId: String = ""
    private var remoteIp: String = ""
    private var remotePort: Int = 5060
    private var remoteRtpPort: Int = 0
    private var localRtpPort: Int = 20000
    private var cseq: Int = 1

    // ── 音频 ──────────────────────────────────────
    private val SAMPLE_RATE = 8000     // G.711 8kHz
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private val BUF_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private var rtpSocket: DatagramSocket? = null

    // ── 初始化：启动 SIP 监听 ─────────────────────
    fun start() {
        if (settings.sipServer.isEmpty()) {
            Timber.w("SipManager: sipServer not configured")
            return
        }
        try {
            sipSocket = DatagramSocket(5070)  // 对讲用独立端口，与 GB28181 5060 区分
            startListening()
            Timber.i("SipManager started on port 5070")
        } catch (e: Exception) {
            Timber.e(e, "SipManager start failed")
        }
    }

    private fun startListening() {
        listenJob = scope.launch {
            val buf = ByteArray(4096)
            while (true) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sipSocket?.receive(packet) ?: break
                    val msg = String(packet.data, 0, packet.length)
                    handleSipMessage(msg, packet.address.hostAddress ?: "", packet.port)
                } catch (e: Exception) {
                    if (sipSocket?.isClosed == true) break
                    Timber.e(e, "SipManager listen error")
                }
            }
        }
    }

    private fun handleSipMessage(msg: String, fromIp: String, fromPort: Int) {
        when {
            msg.startsWith("INVITE") -> handleInvite(msg, fromIp, fromPort)
            msg.startsWith("BYE")    -> handleBye()
            msg.contains("200 OK") && state == State.INCOMING -> { /* 已在 acceptCall 处理 */ }
            msg.startsWith("ACK")   -> {
                if (state == State.INCOMING) {
                    state = State.IN_CALL
                    startAudio()
                    onCallConnected?.invoke()
                }
            }
        }
    }

    private fun handleInvite(msg: String, fromIp: String, fromPort: Int) {
        if (state != State.IDLE) {
            sendBusy(fromIp, fromPort, msg)
            return
        }
        callId = SipMessage.extractHeader(msg, "Call-ID") ?: UUID.randomUUID().toString()
        remoteIp = fromIp
        remotePort = fromPort
        remoteRtpPort = extractRtpPort(msg)
        val fromUri = SipMessage.extractHeader(msg, "From") ?: fromIp
        state = State.INCOMING

        // 先回 100 Trying
        send180Ringing(msg, fromIp, fromPort)
        Timber.i("SipManager: incoming call from $fromUri")
        onIncomingCall?.invoke(fromUri)
    }

    private fun handleBye() {
        Timber.i("SipManager: call ended by remote")
        state = State.IDLE
        stopAudio()
        onCallEnded?.invoke()
    }

    /** 接听：发送 200 OK 含 SDP */
    fun acceptCall() {
        val socket = sipSocket ?: return
        try {
            val localIp = socket.localAddress?.hostAddress ?: "0.0.0.0"
            val sdp = buildSdp(localIp, localRtpPort)
            val ok = "SIP/2.0 200 OK\r\n" +
                    "Via: SIP/2.0/UDP $remoteIp:$remotePort\r\n" +
                    "From: <sip:${settings.deviceId}@${settings.sipServer}>\r\n" +
                    "To: <sip:${settings.deviceId}@${settings.sipServer}>\r\n" +
                    "Call-ID: $callId\r\n" +
                    "CSeq: 1 INVITE\r\n" +
                    "Contact: <sip:${settings.deviceId}@$localIp:5070>\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: ${sdp.length}\r\n\r\n$sdp"
            sendRaw(ok, remoteIp, remotePort)
            // ACK 到来后才正式进 IN_CALL（handleSipMessage 的 ACK 分支）
            Timber.i("SipManager: accepted call, sent 200 OK")
        } catch (e: Exception) {
            Timber.e(e, "SipManager acceptCall failed")
        }
    }

    /** 拒接 */
    fun declineCall() {
        val decline = "SIP/2.0 486 Busy Here\r\n" +
                "Call-ID: $callId\r\n" +
                "CSeq: 1 INVITE\r\n" +
                "Content-Length: 0\r\n\r\n"
        sendRaw(decline, remoteIp, remotePort)
        state = State.IDLE
        Timber.i("SipManager: declined call")
    }

    /** 发起呼叫（群组对讲） */
    fun makeCall(targetUri: String) {
        if (state != State.IDLE) return
        val socket = sipSocket ?: return
        try {
            val localIp = socket.localAddress?.hostAddress ?: "0.0.0.0"
            callId = UUID.randomUUID().toString().replace("-", "")
            remoteIp = settings.sipServer
            remotePort = settings.sipPort.toIntOrNull() ?: 5060
            val sdp = buildSdp(localIp, localRtpPort)
            val invite = "INVITE $targetUri SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP $localIp:5070;branch=z9hG4bK${callId.take(8)}\r\n" +
                    "From: <sip:${settings.deviceId}@${settings.sipServer}>;tag=${callId.take(8)}\r\n" +
                    "To: <$targetUri>\r\n" +
                    "Call-ID: $callId\r\n" +
                    "CSeq: ${cseq++} INVITE\r\n" +
                    "Contact: <sip:${settings.deviceId}@$localIp:5070>\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: ${sdp.length}\r\n\r\n$sdp"
            sendRaw(invite, remoteIp, remotePort)
            state = State.INCOMING  // 等待 200 OK
            Timber.i("SipManager: INVITE sent to $targetUri")
        } catch (e: Exception) {
            Timber.e(e, "SipManager makeCall failed")
        }
    }

    /** 挂断 */
    fun hangup() {
        if (state == State.IDLE) return
        val socket = sipSocket ?: return
        val localIp = socket.localAddress?.hostAddress ?: "0.0.0.0"
        val bye = "BYE sip:${settings.sipServer} SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP $localIp:5070\r\n" +
                "From: <sip:${settings.deviceId}@${settings.sipServer}>\r\n" +
                "To: <sip:${settings.sipServer}>\r\n" +
                "Call-ID: $callId\r\n" +
                "CSeq: ${cseq++} BYE\r\n" +
                "Content-Length: 0\r\n\r\n"
        sendRaw(bye, remoteIp, remotePort)
        state = State.IDLE
        stopAudio()
        Timber.i("SipManager: BYE sent")
    }

    fun isInCall() = state == State.IN_CALL

    // ── 音频收发 ──────────────────────────────────
    private fun startAudio() {
        try {
            rtpSocket = DatagramSocket(localRtpPort)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING, BUF_SIZE * 2
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(BUF_SIZE * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioRecord = record
            audioTrack = track
            record.startRecording()
            track.play()

            audioJob = scope.launch {
                // 发送（麦克风 → RTP）
                launch { sendAudioLoop(record) }
                // 接收（RTP → 扬声器）
                launch { receiveAudioLoop(track) }
            }
            Timber.i("SipManager: audio started, rtp port $localRtpPort ↔ $remoteIp:$remoteRtpPort")
        } catch (e: Exception) {
            Timber.e(e, "SipManager startAudio failed")
        }
    }

    private suspend fun sendAudioLoop(record: AudioRecord) {
        val buf = ByteArray(BUF_SIZE)
        var seq = 0
        var ts = 0
        val ssrc = (Math.random() * 0xFFFFFFFFL).toInt()
        while (state == State.IN_CALL) {
            val read = record.read(buf, 0, buf.size)
            if (read <= 0) continue
            val rtp = buildRtpPacket(buf, read, seq++, ts, ssrc, 0)  // PT=0 PCMU
            ts += read / 2
            try {
                val pkt = DatagramPacket(rtp, rtp.size,
                    InetAddress.getByName(remoteIp), remoteRtpPort)
                rtpSocket?.send(pkt)
            } catch (e: Exception) { /* ignore send errors */ }
        }
    }

    private suspend fun receiveAudioLoop(track: AudioTrack) {
        val buf = ByteArray(2048)
        while (state == State.IN_CALL) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                rtpSocket?.receive(pkt) ?: break
                if (pkt.length > 12) {
                    // 跳过 12 字节 RTP 头，剩余为 PCM 数据
                    track.write(pkt.data, 12, pkt.length - 12)
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun stopAudio() {
        audioJob?.cancel()
        audioJob = null
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        runCatching { audioTrack?.stop(); audioTrack?.release() }
        runCatching { rtpSocket?.close() }
        audioRecord = null
        audioTrack = null
        rtpSocket = null
        Timber.i("SipManager: audio stopped")
    }

    // ── 工具方法 ──────────────────────────────────
    private fun buildSdp(localIp: String, rtpPort: Int) =
        "v=0\r\no=- 0 0 IN IP4 $localIp\r\ns=Intercom\r\n" +
                "c=IN IP4 $localIp\r\nt=0 0\r\n" +
                "m=audio $rtpPort RTP/AVP 0\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n"

    private fun extractRtpPort(sdp: String): Int {
        val m = Regex("""m=audio (\d+)""").find(sdp)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun send180Ringing(invite: String, toIp: String, toPort: Int) {
        val via = SipMessage.extractHeader(invite, "Via") ?: return
        val ringing = "SIP/2.0 180 Ringing\r\n" +
                "Via: $via\r\n" +
                "From: ${SipMessage.extractHeader(invite, "From")}\r\n" +
                "To: ${SipMessage.extractHeader(invite, "To")}\r\n" +
                "Call-ID: $callId\r\n" +
                "CSeq: ${SipMessage.extractHeader(invite, "CSeq")}\r\n" +
                "Content-Length: 0\r\n\r\n"
        sendRaw(ringing, toIp, toPort)
    }

    private fun sendBusy(toIp: String, toPort: Int, invite: String) {
        val busy = "SIP/2.0 486 Busy Here\r\n" +
                "Call-ID: ${SipMessage.extractHeader(invite, "Call-ID")}\r\n" +
                "CSeq: ${SipMessage.extractHeader(invite, "CSeq")}\r\n" +
                "Content-Length: 0\r\n\r\n"
        sendRaw(busy, toIp, toPort)
    }

    private fun sendRaw(msg: String, ip: String, port: Int) {
        try {
            val data = msg.toByteArray()
            val pkt = DatagramPacket(data, data.size, InetAddress.getByName(ip), port)
            sipSocket?.send(pkt)
        } catch (e: Exception) {
            Timber.e(e, "SipManager sendRaw failed")
        }
    }

    private fun buildRtpPacket(
        pcm: ByteArray, len: Int, seq: Int, ts: Int, ssrc: Int, pt: Int
    ): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = pt.toByte()
        header[2] = (seq shr 8).toByte()
        header[3] = (seq and 0xFF).toByte()
        header[4] = (ts shr 24).toByte(); header[5] = (ts shr 16).toByte()
        header[6] = (ts shr 8).toByte();  header[7] = (ts and 0xFF).toByte()
        header[8] = (ssrc shr 24).toByte(); header[9] = (ssrc shr 16).toByte()
        header[10] = (ssrc shr 8).toByte(); header[11] = (ssrc and 0xFF).toByte()
        return header + pcm.copyOf(len)
    }

    fun stop() {
        if (state != State.IDLE) hangup()
        listenJob?.cancel()
        sipSocket?.close()
        Timber.i("SipManager stopped")
    }
}
