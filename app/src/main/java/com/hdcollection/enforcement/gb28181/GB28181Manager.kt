package com.hdcollection.enforcement.gb28181

import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class GB28181Manager(
    private val settings: AppSettings,
    private val callback: StreamCallback
) {
    private var udpSocket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var keepAliveJob: Job? = null
    private var callId = UUID.randomUUID().toString().replace("-", "")
    private var cseq = 1
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var localIp: String = "0.0.0.0"
    private val localPort = 5060

    fun register() {
        scope.launch {
            try {
                localIp = getLocalIp()
                // 强制使用 IPv4
                System.setProperty("java.net.preferIPv4Stack", "true")
                // 绑定到设备的 WiFi IPv4 地址
                val bindAddr = java.net.Inet4Address.getByName(localIp)
                udpSocket = DatagramSocket(localPort, bindAddr)
                Timber.i("GB28181: socket bound to $localIp:$localPort (${udpSocket!!.localAddress})")
                Timber.i("GB28181: registering ${settings.deviceId} to ${settings.sipServer}:${settings.sipPort}")

                startListening()
                sendRegister()
            } catch (e: Exception) {
                Timber.e(e, "GB28181: register failed")
                callback.onRegistrationFailed(e.message ?: "Unknown error")
            }
        }
    }

    private fun sendRegister(authNonce: String? = null, authRealm: String? = null) {
        val msg = if (authNonce != null && authRealm != null) {
            SipMessage.buildRegisterWithAuth(
                settings.deviceId, settings.sipServer, settings.sipPort,
                localIp, localPort, callId, cseq++,
                settings.sipUsername, settings.sipPassword,
                authRealm, authNonce
            )
        } else {
            SipMessage.buildRegister(
                settings.deviceId, settings.sipServer, settings.sipPort,
                localIp, localPort, callId, cseq++
            )
        }
        sendUdp(msg)
    }

    private fun startListening() {
        listenJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    handleSipMessage(message)
                } catch (e: Exception) {
                    if (isActive) Timber.e(e, "GB28181: receive error")
                }
            }
        }
    }

    private fun handleSipMessage(message: String) {
        Timber.d("GB28181: received: ${message.take(100)}")
        when {
            message.startsWith("SIP/2.0 401") -> {
                // 需要认证
                val nonce = SipMessage.extractNonce(message)
                val realm = SipMessage.extractRealm(message)
                if (nonce != null && realm != null) {
                    Timber.i("GB28181: 401 received, retrying with auth")
                    sendRegister(nonce, realm)
                }
            }
            message.startsWith("SIP/2.0 200") && message.contains("CSeq") && message.contains("REGISTER") -> {
                Timber.i("GB28181: registered successfully")
                callback.onRegistered(settings.deviceId)
                startKeepAlive()
            }
            message.startsWith("INVITE") -> {
                handleInvite(message)
            }
            message.startsWith("BYE") -> {
                val callIdHeader = SipMessage.extractHeader(message, "Call-ID") ?: ""
                Timber.i("GB28181: BYE received, stopping stream")
                currentCallId = null
                callback.onStreamStopRequested(callIdHeader)
                sendUdp(SipMessage.buildBye(callIdHeader))
            }
        }
    }

    private var currentCallId: String? = null

    private fun handleInvite(message: String) {
        val callIdHeader = SipMessage.extractHeader(message, "Call-ID") ?: return

        // 忽略重复的 INVITE（同一个 Call-ID 的重传）
        if (callIdHeader == currentCallId) {
            Timber.d("GB28181: 忽略重复 INVITE, CallID=$callIdHeader")
            return
        }

        val fromHeader = SipMessage.extractHeader(message, "From") ?: return
        val toHeader = SipMessage.extractHeader(message, "To") ?: return
        val cseqHeader = SipMessage.extractHeader(message, "CSeq") ?: return

        // 如果正在推流，先停掉旧的
        if (currentCallId != null) {
            Timber.i("GB28181: 停止旧推流, 旧CallID=$currentCallId")
            callback.onStreamStopRequested(currentCallId!!)
        }

        // 从 SDP 解析 RTP 目标地址和端口
        val rtpIp = extractSdpConnection(message) ?: settings.sipServer
        val rtpPort = extractSdpMediaPort(message) ?: 10000

        // 从 SDP y= 字段解析 SSRC
        val sdpSsrc = extractSdpSsrc(message)
        Timber.i("GB28181: INVITE SDP -> IP=$rtpIp, Port=$rtpPort, SSRC=$sdpSsrc")

        val ok = SipMessage.buildInviteOk(
            callIdHeader, fromHeader, toHeader, cseqHeader,
            localIp, localPort, sdpSsrc?.let { String.format("%010d", it.toLong()) } ?: "0000000000"
        )
        sendUdp(ok)

        currentCallId = callIdHeader
        Timber.i("GB28181: INVITE accepted, streaming to $rtpIp:$rtpPort ssrc=$sdpSsrc")
        callback.onStreamStartRequested(callIdHeader, rtpIp, rtpPort, sdpSsrc ?: 0)
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(60_000)  // 每60秒发一次心跳
                sendRegister()  // GB28181 用 REGISTER 作为心跳
            }
        }
    }

    private fun sendUdp(message: String) {
        try {
            val data = message.toByteArray()
            val packet = DatagramPacket(
                data, data.size,
                InetAddress.getByName(settings.sipServer),
                settings.sipPort.toIntOrNull() ?: 5060
            )
            udpSocket?.send(packet)
        } catch (e: Exception) {
            Timber.e(e, "GB28181: send error")
        }
    }

    private fun getLocalIp(): String {
        return try {
            val socket = java.net.Socket()
            socket.connect(
                java.net.InetSocketAddress(
                    settings.sipServer,
                    settings.sipPort.toIntOrNull() ?: 5060
                ),
                1000
            )
            val ip = socket.localAddress.hostAddress ?: "0.0.0.0"
            socket.close()
            ip
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    private fun extractSdpConnection(message: String): String? {
        return Regex("""c=IN IP4 (\d+\.\d+\.\d+\.\d+)""").find(message)?.groupValues?.get(1)
    }

    private fun extractSdpMediaPort(message: String): Int? {
        return Regex("""m=video (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSdpSsrc(message: String): Int? {
        // GB28181 SDP 中 y= 字段是 10 位 SSRC（十进制字符串）
        val ssrcStr = Regex("""y=(\d{10})""").find(message)?.groupValues?.get(1)
        if (ssrcStr != null) {
            return ssrcStr.toLongOrNull()?.toInt()
        }
        // 兼容 ssrc= 格式
        val ssrcStr2 = Regex("""ssrc=(\d+)""", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)
        return ssrcStr2?.toLongOrNull()?.toInt()
    }

    fun unregister() {
        scope.launch {
            sendRegister() // expires=0 的 REGISTER 即注销（简化处理）
            cleanup()
        }
    }

    private fun cleanup() {
        keepAliveJob?.cancel()
        listenJob?.cancel()
        udpSocket?.close()
        udpSocket = null
    }

    fun destroy() {
        cleanup()
        scope.cancel()
    }
}
