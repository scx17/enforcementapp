package com.hdcollection.enforcement.gb28181

import android.net.Network
import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class GB28181Manager(
    private val settings: AppSettings,
    private val callback: StreamCallback
) {
    // 当前可用网络，由 MediaCaptureService 在 NetworkCallback 中更新
    @Volatile var boundNetwork: Network? = null
    private var udpSocket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var keepAliveJob: Job? = null
    private var callId = UUID.randomUUID().toString().replace("-", "")
    private var cseq = 1
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var localIp: String = "0.0.0.0"
    private val localPort = 5060

    // keepAlive 连续失败次数 — 用于判断是否需要触发 onRegistrationFailed 并重建 socket
    private var keepaliveFailCount = 0
    // 重试触发阈值：连续失败 N 次（约 N 分钟，对应 60 秒间隔）后认为掉线
    private val keepaliveFailThreshold = 3
    // 标志位：避免 onRegistrationFailed 重入触发多次 register
    private var rebuilding = false
    // 唤醒通道：rebuild 在等待 5s 时可被网络回调立即唤醒（CONFLATED 避免堆积）
    private val rebuildWakeChan = Channel<Unit>(Channel.CONFLATED)
    // 串行化 register / rebuild 的关键段，避免初始 register 与 NetworkCallback 触发的 rebuild 竞争
    private val opMutex = Mutex()

    fun register() {
        scope.launch {
            opMutex.withLock {
                try {
                    val existing = udpSocket
                    if (existing != null && !existing.isClosed) {
                        Timber.d("GB28181 register: 已有 open socket (${existing.localSocketAddress}), 跳过本次")
                        return@withLock
                    }
                    localIp = getLocalIp()
                    // 强制使用 IPv4
                    System.setProperty("java.net.preferIPv4Stack", "true")
                    // 绑定到设备的 WiFi IPv4 地址
                    val bindAddr = java.net.Inet4Address.getByName(localIp)
                    udpSocket = DatagramSocket(localPort, bindAddr)
                    boundNetwork?.let { net ->
                        try { net.bindSocket(udpSocket!!); Timber.i("GB28181: socket 已绑定到网络 $net") }
                        catch (e: Throwable) { Timber.w("GB28181: bindSocket 失败: ${e.message}") }
                    }
                    Timber.i("GB28181: socket bound to $localIp:$localPort (${udpSocket!!.localAddress})")
                    Timber.i("GB28181: registering ${settings.deviceId} to ${settings.sipServer}:${settings.sipPort}")

                    startListening()
                    val sent = sendRegister()
                    if (!sent) {
                        // sendUdp 内部已 catch 异常返回 false（如 ENETUNREACH / 网络未就绪）
                        // 此时 register 流程还没收到 200 OK，必须主动通知 UI 标为未注册
                        Timber.e("GB28181: 初次 REGISTER 投递失败 (网络不可达?), 通知上层并安排重试")
                        // 已持有锁，不能再调 triggerRebuild（它要进 launch + withLock 会死锁）
                        // 标记 rebuilding 让 triggerReconnect 知道有 rebuild 在进行
                        rebuilding = true
                        // force=true：sendto 已失败说明网络真的不可达，跳过 double-check
                        scope.launch { rebuildAfterUnlock("initial register sendto failed (network unreachable?)", force = true) }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "GB28181: register failed")
                    callback.onRegistrationFailed(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * 发送 REGISTER。返回是否成功投递到网络层（不代表对端响应）。
     * 上层（keepAlive）依赖此返回值统计连续失败次数。
     */
    private fun sendRegister(authNonce: String? = null, authRealm: String? = null): Boolean {
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
        return sendUdp(msg)
    }

    private fun startListening() {
        listenJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    handleSipMessage(message, packet.address, packet.port)
                } catch (e: Exception) {
                    if (isActive) Timber.e(e, "GB28181: receive error")
                }
            }
        }
    }

    private fun handleSipMessage(message: String, srcAddr: InetAddress? = null, srcPort: Int = 0) {
        Timber.d("GB28181: received from ${srcAddr?.hostAddress}:$srcPort: ${message.take(100)}")
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
                keepaliveFailCount = 0
                rebuilding = false
                callback.onRegistered(settings.deviceId)
                startKeepAlive()
            }
            message.startsWith("INVITE") -> {
                handleInvite(message)
            }
            message.startsWith("MESSAGE") -> {
                handleMessage(message, srcAddr, srcPort)
            }
            message.startsWith("BYE") -> {
                val callIdHeader = SipMessage.extractHeader(message, "Call-ID") ?: ""
                // 回复 200 OK（无论是否匹配当前会话都要回复）
                val byeOk = SipMessage.buildBye(callIdHeader)
                val addr = srcAddr ?: InetAddress.getByName(settings.sipServer)
                val port = if (srcPort > 0) srcPort else (settings.sipPort.toIntOrNull() ?: 5060)
                try {
                    val data = byeOk.toByteArray()
                    repeat(3) { i ->
                        udpSocket?.send(DatagramPacket(data, data.size, addr, port))
                        if (i < 2) Thread.sleep(50)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "GB28181: BYE 200 OK ���送失败")
                }
                // 只有 Call-ID 匹配当前会话才停流（防止旧 BYE 延迟到达误杀新会话）
                if (callIdHeader == currentCallId) {
                    Timber.i("GB28181: BYE 匹配当前会话, 停止推流 CallID=$callIdHeader")
                    currentCallId = null
                    callback.onStreamStopRequested(callIdHeader)
                } else {
                    Timber.w("GB28181: BYE 不匹配当前会话, 忽略. BYE=$callIdHeader, current=$currentCallId")
                }
            }
        }
    }

    private var currentCallId: String? = null

    private fun handleMessage(message: String, srcAddr: InetAddress? = null, srcPort: Int = 0) {
        Timber.i("GB28181: MESSAGE from ${srcAddr?.hostAddress}:$srcPort, 内容:\n${message.take(500)}")
        val callIdHeader = SipMessage.extractHeader(message, "Call-ID") ?: return
        val fromHeader = SipMessage.extractHeader(message, "From") ?: return
        val toHeader = SipMessage.extractHeader(message, "To") ?: return
        val cseqHeader = SipMessage.extractHeader(message, "CSeq") ?: return
        val viaHeader = SipMessage.extractHeader(message, "Via") ?: return

        // 修正 Via 头：添加 received 和 rport 参数（RFC 3581）
        val fixedVia = if (srcAddr != null && srcPort > 0) {
            val via = viaHeader.replace(";rport", ";rport=$srcPort;received=${srcAddr.hostAddress}")
            via
        } else viaHeader

        // 回复 200 OK — 必须发回消息的实际来源地址
        val ok = SipMessage.buildMessageOk(callIdHeader, fromHeader, toHeader, cseqHeader, fixedVia)
        val replyAddr = srcAddr ?: InetAddress.getByName(settings.sipServer)
        val replyPort = if (srcPort > 0) srcPort else (settings.sipPort.toIntOrNull() ?: 5060)
        Timber.i("GB28181: MESSAGE 200 OK -> ${replyAddr.hostAddress}:$replyPort")
        try {
            val data = ok.toByteArray()
            // 发送3次，UDP 可能丢包
            repeat(3) { i ->
                udpSocket?.send(DatagramPacket(data, data.size, replyAddr, replyPort))
                if (i < 2) Thread.sleep(50)
            }
            Timber.i("GB28181: MESSAGE 200 OK 已发送3次")
        } catch (e: Exception) {
            Timber.e(e, "GB28181: 发送 MESSAGE 200 OK 失败")
        }

        // 解析 XML body 中的 CmdType
        val cmdType = Regex("""<CmdType>(.*?)</CmdType>""").find(message)?.groupValues?.get(1)
        Timber.i("GB28181: MESSAGE received, CmdType=$cmdType")

        when (cmdType) {
            "Catalog" -> {
                val sn = Regex("<SN>(.*?)</SN>").find(message)?.groupValues?.get(1) ?: "0"
                Timber.i("GB28181: 收到 Catalog 查询 SN=$sn，发送通道列表响应")
                try {
                    val catalogMsg = SipMessage.buildCatalogResponse(
                        deviceId = settings.deviceId,
                        sipServer = settings.sipServer,
                        sipPort = settings.sipPort,
                        localIp = localIp,
                        localPort = localPort,
                        callId = callId,
                        cseq = cseq++,
                        fromHeader = fromHeader,
                        toHeader = toHeader,
                        sn = sn
                    )
                    val data = catalogMsg.toByteArray()
                    udpSocket?.send(DatagramPacket(data, data.size, replyAddr, replyPort))
                    Timber.i("GB28181: Catalog 响应已发送 -> ${replyAddr.hostAddress}:$replyPort")
                } catch (e: Exception) {
                    Timber.e(e, "GB28181: Catalog 响应发送失败")
                }
            }
            "Broadcast" -> {
                Timber.i("GB28181: 收到广播/对讲指令，已回复200 OK，等待 INVITE")
                // WVP-PRO 收到 200 OK 后会发送 INVITE 建立音频通道
                // INVITE 由现有的 handleInvite 处理
            }
            else -> {
                Timber.d("GB28181: 未处理的 MESSAGE CmdType=$cmdType")
            }
        }
    }

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
        val viaHeader = SipMessage.extractHeader(message, "Via") ?: return

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

        // SDP 中填写设备推流的源地址（用 0.0.0.0 表示由服务端自动识别）
        val ok = SipMessage.buildInviteOk(
            callIdHeader, fromHeader, toHeader, cseqHeader, viaHeader,
            "0.0.0.0", rtpPort, sdpSsrc?.let { String.format("%010d", it.toLong()) } ?: "0000000000"
        )
        Timber.i("GB28181: 发送 200 OK, Via=$viaHeader")
        sendUdp(ok)

        currentCallId = callIdHeader
        Timber.i("GB28181: INVITE accepted, streaming to $rtpIp:$rtpPort ssrc=$sdpSsrc")
        callback.onStreamStartRequested(callIdHeader, rtpIp, rtpPort, sdpSsrc ?: 0)
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepaliveFailCount = 0
        keepAliveJob = scope.launch {
            Timber.i("GB28181: KeepAlive 心跳任务已启动, 间隔60s")
            while (isActive) {
                delay(60_000)  // 每60秒发一次心跳
                Timber.d("GB28181: 发送 KeepAlive 心跳 -> ${settings.sipServer}:${settings.sipPort}")
                val ok = sendRegister()  // GB28181 用 REGISTER 作为心跳
                if (ok) {
                    if (keepaliveFailCount > 0) {
                        Timber.i("GB28181: KeepAlive 恢复, 失败计数清零")
                    }
                    keepaliveFailCount = 0
                } else {
                    keepaliveFailCount++
                    Timber.w("GB28181: KeepAlive 投递失败 ($keepaliveFailCount/$keepaliveFailThreshold)")
                    if (keepaliveFailCount >= keepaliveFailThreshold) {
                        Timber.e("GB28181: KeepAlive 连续 $keepaliveFailThreshold 次投递失败, 触发离线回调并重建 socket")
                        triggerRebuild("keepalive sendto failed $keepaliveFailThreshold times (network unreachable?)")
                        break  // 退出当前 keepAlive 循环；register() 成功后会启动新的
                    }
                }
            }
        }
    }

    /**
     * 网络异常 / 主动 reconnect 时调用：
     *  1. 通知上层 onRegistrationFailed，让 UI 立刻反映"未注册"
     *  2. 关闭旧 socket 并重新发起 register()，以适配 WiFi 切换 / IP 变更场景
     * 调用时**不能持有 opMutex**。
     */
    private fun triggerRebuild(reason: String, force: Boolean = false) {
        if (rebuilding) return
        rebuilding = true
        scope.launch { rebuildAfterUnlock(reason, force) }
    }

    /**
     * rebuild 实际执行体，自带 opMutex 加锁。
     * @param force 跳过"socket 健康"的 double-check。用于明确知道网络已断的场景（如 onLost）。
     */
    private suspend fun rebuildAfterUnlock(reason: String, force: Boolean) {
        opMutex.withLock {
            try {
                // double-check：拿到锁后重新评估是否真需要 rebuild
                // （可能在 launch 排队期间，并发的 register 已经把 socket 建好）
                if (!force) {
                    val sock = udpSocket
                    if (sock != null && !sock.isClosed && keepaliveFailCount == 0) {
                        Timber.i("GB28181 rebuild: 拿到锁后发现 socket 已健康, 跳过. reason=$reason")
                        rebuilding = false
                        return
                    }
                }
                try {
                    callback.onRegistrationFailed(reason)
                } catch (e: Exception) {
                    Timber.e(e, "GB28181: onRegistrationFailed 回调异常")
                }
                listenJob?.cancel()
                runCatching { udpSocket?.close() }
                udpSocket = null
                Timber.i("GB28181: 旧 socket 已关闭, 最多等待 5s 后重建（可被网络回调唤醒）")
                while (rebuildWakeChan.tryReceive().isSuccess) {}
                val woken = withTimeoutOrNull(5_000) { rebuildWakeChan.receive() }
                if (woken != null) {
                    Timber.i("GB28181: rebuild 等待被网络回调唤醒, 立即重建")
                }
            } catch (e: Exception) {
                Timber.e(e, "GB28181: rebuild 清理异常")
            }
        }
        // 退出锁后再 register（register 内部会重新拿锁）
        rebuilding = false
        register()
    }

    /**
     * 网络状态恢复时由外部（MainActivity 的 NetworkCallback）触发：
     *  - 已注册且 keepAlive 健在：跳过
     *  - 在途 rebuild（正在等 5s）：唤醒它立即重试
     *  - 其它（罕见）：主动启动一次 rebuild
     * 此方法可被频繁调用（如 onAvailable + onCapabilitiesChanged 双触发），内部已做去抖。
     */
    fun triggerReconnect(reason: String) {
        if (rebuilding) {
            val sent = rebuildWakeChan.trySend(Unit).isSuccess
            Timber.i("GB28181 triggerReconnect: 唤醒在途 rebuild (sent=$sent). reason=$reason")
            return
        }
        val sock = udpSocket
        // socket 健在且 keepAlive 没在累计失败 → 认为健康，跳过
        // （包括首次注册中的瞬态：socket 已建好但还没收到 200 OK，避免与初始 register 打架）
        if (sock != null && !sock.isClosed && keepaliveFailCount == 0) {
            Timber.d("GB28181 triggerReconnect: socket 健康, 跳过. reason=$reason")
            return
        }
        Timber.i("GB28181 triggerReconnect: 触发重建 (failCount=$keepaliveFailCount, sockClosed=${sock?.isClosed}). reason=$reason")
        triggerRebuild("triggerReconnect: $reason")
    }

    /**
     * 网络丢失（如 WiFi 关闭）由外部 NetworkCallback.onLost 调用：
     * 进入 rebuild 等待状态——关闭旧 socket（接口已失效）并等待 onAvailable 唤醒。
     * 不立即 register（没网时也没意义），由后续 triggerReconnect 唤醒。
     */
    fun notifyNetworkLost(reason: String) {
        Timber.i("GB28181 notifyNetworkLost: $reason")
        // force=true 跳过 double-check：onLost 明确知道网络已断，
        // 此时 socket 表面 isClosed=false 但实际已无效，必须强制重建
        triggerRebuild("notifyNetworkLost: $reason", force = true)
    }

    /**
     * 发送 UDP 包，返回是否成功投递。
     * ENETUNREACH / 接口失效 / socket 已关闭等会返回 false，调用方据此判断网络可达性。
     */
    private fun sendUdp(message: String): Boolean {
        return try {
            val data = message.toByteArray()
            val packet = DatagramPacket(
                data, data.size,
                InetAddress.getByName(settings.sipServer),
                settings.sipPort.toIntOrNull() ?: 5060
            )
            val sock = udpSocket
            if (sock == null || sock.isClosed) {
                Timber.w("GB28181: send skipped, socket null or closed")
                return false
            }
            sock.send(packet)
            true
        } catch (e: Exception) {
            Timber.e(e, "GB28181: send error")
            false
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
            if (ip == "0.0.0.0") {
                Timber.w("GB28181: getLocalIp() 返回 0.0.0.0, 可能无网络连接")
            }
            ip
        } catch (e: Exception) {
            Timber.w(e, "GB28181: getLocalIp() 异常, 回退到 0.0.0.0")
            "0.0.0.0"
        }
    }

    private fun extractSdpConnection(message: String): String? {
        val ip = Regex("""c=IN IP4 (\d+\.\d+\.\d+\.\d+)""").find(message)?.groupValues?.get(1)
        Timber.d("GB28181: SDP 解析 connection IP=$ip")
        return ip
    }

    private fun extractSdpMediaPort(message: String): Int? {
        val port = Regex("""m=video (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        Timber.d("GB28181: SDP 解析 media port=$port")
        return port
    }

    private fun extractSdpSsrc(message: String): Int? {
        // GB28181 SDP 中 y= 字段是 10 位 SSRC（十进制字符串）
        val ssrcStr = Regex("""y=(\d{10})""").find(message)?.groupValues?.get(1)
        if (ssrcStr != null) {
            val ssrc = ssrcStr.toLongOrNull()?.toInt()
            Timber.d("GB28181: SDP 解析 SSRC(y=)=$ssrc")
            return ssrc
        }
        // 兼容 ssrc= 格式
        val ssrcStr2 = Regex("""ssrc=(\d+)""", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)
        val ssrc2 = ssrcStr2?.toLongOrNull()?.toInt()
        Timber.d("GB28181: SDP 解析 SSRC(ssrc=)=$ssrc2")
        return ssrc2
    }

    fun unregister() {
        Timber.i("GB28181: 开始注销流程")
        scope.launch {
            sendRegister() // expires=0 的 REGISTER 即注销（简化处理）
            Timber.i("GB28181: 注销 REGISTER 已发送")
            cleanup()
            Timber.i("GB28181: 注销完成, 资源已释放")
        }
    }

    private fun cleanup() {
        keepAliveJob?.cancel()
        listenJob?.cancel()
        Timber.d("GB28181: 关闭 UDP Socket (localPort=$localPort)")
        udpSocket?.close()
        udpSocket = null
    }

    fun destroy() {
        cleanup()
        scope.cancel()
    }
}
