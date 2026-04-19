package com.hdcollection.enforcement.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class PlatformNotification(
    val message: String,
    val receivedAt: Long = System.currentTimeMillis()
)

class PlatformNotificationService(
    private val context: Context,
    private val settings: AppSettings
) {
    private var connection: HubConnection? = null
    private var audioTrack: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    val notifications: CopyOnWriteArrayList<PlatformNotification> = CopyOnWriteArrayList()

    var onNotificationReceived: ((PlatformNotification) -> Unit)? = null
    var onPullFileRequested: ((String) -> Unit)? = null
    var onWorkTaskReceived: ((String, String) -> Unit)? = null  // (title, priority)
    var onConfigPushReceived: ((org.json.JSONObject) -> Unit)? = null
    var onCustomCodeChanged: ((String) -> Unit)? = null

    // 无限重连状态机
    private val reconnecting = AtomicBoolean(false)
    @Volatile private var reconnectThread: Thread? = null
    @Volatile private var networkCallbackRegistered = false

    fun connect() {
        if (settings.platformApiUrl.isEmpty()) {
            Timber.w("SignalR: platformApiUrl not configured, skipping connect")
            return
        }

        // 初始化 TTS 引擎
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.CHINESE)
                    ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                    Timber.i("TTS 初始化成功, 中文支持=${ttsReady}")
                } else {
                    Timber.w("TTS 初始化失败: status=$status")
                }
            }
        }

        Timber.i("SignalR: 开始连接 ${settings.platformApiUrl}/hubs/monitor")
        try {
            val conn = HubConnectionBuilder
                .create("${settings.platformApiUrl}/hubs/monitor")
                .build()

            // 断连后启动无限重连守护线程（首次 2s，指数退避封顶 60s）
            conn.onClosed { error ->
                Timber.w("SignalR: 连接断开, error=${error?.message}, 启动重连守护线程")
                scheduleReconnect()
            }

            conn.on("PlatformNotification", { message: String ->
                val notification = PlatformNotification(message)
                notifications.add(0, notification)
                Timber.i("Platform notification: $message")
                showSystemNotification(message)
                onNotificationReceived?.invoke(notification)
            }, String::class.java)

            // 监听服务器拉取文件命令
            conn.on("PullFile", { data: String ->
                try {
                    val json = org.json.JSONObject(data)
                    val fileName = json.getString("fileName")
                    Timber.i("PullFile 命令: fileName=$fileName")
                    onPullFileRequested?.invoke(fileName)
                } catch (e: Exception) {
                    Timber.e(e, "PullFile 命令解析失败")
                }
            }, String::class.java)

            // 监听对讲音频（平台→设备）
            conn.on("TalkAudio", { audioBase64: String ->
                try {
                    val pcmBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                    playPcmAudio(pcmBytes)
                } catch (e: Exception) {
                    Timber.e(e, "TalkAudio 播放失败")
                }
            }, String::class.java)

            // 监听新工单分配 — App 内弹窗 + 循环声音直到已阅
            conn.on("NewWorkTask", { data: String ->
                try {
                    Timber.i("收到新工单: $data")
                    val json = org.json.JSONObject(data)
                    // 兼容大小写（C# 默认 PascalCase）
                    val title = json.optString("Title", json.optString("title", "新工单"))
                    val priority = json.optString("Priority", json.optString("priority", "normal"))
                    val notification = PlatformNotification("工单: $title")
                    notifications.add(0, notification)

                    // 通知 MainActivity 弹出 App 内对话框
                    onWorkTaskReceived?.invoke(title, priority)

                    onNotificationReceived?.invoke(notification)
                } catch (e: Exception) {
                    Timber.e(e, "工单通知处理失败")
                }
            }, String::class.java)

            // 监听远程配置推送（平台→设备）
            conn.on("ConfigPush", { data: String ->
                try {
                    val json = org.json.JSONObject(data)
                    val deviceIdField = json.optString("deviceId", json.optString("DeviceId", ""))
                    val configs = json.optJSONObject("configs") ?: json.optJSONObject("Configs")
                    val keyList = configs?.keys()?.asSequence()?.toList() ?: emptyList()
                    Timber.i("ConfigPush 收到: deviceId=$deviceIdField, keys=$keyList")
                    onConfigPushReceived?.invoke(json)
                } catch (e: Exception) {
                    Timber.e(e, "ConfigPush 解析失败")
                }
            }, String::class.java)

            // 监听围栏告警 — TTS 语音播报
            conn.on("FenceAlarm", { data: String ->
                try {
                    Timber.i("收到围栏告警: $data")
                    val json = org.json.JSONObject(data)
                    val message = json.optString("message", json.optString("action", "围栏告警"))
                    val fenceName = json.optString("fenceName", "")

                    // 系统通知
                    val notification = PlatformNotification("围栏告警: $message")
                    notifications.add(0, notification)
                    showFenceAlarmNotification(message)
                    onNotificationReceived?.invoke(notification)

                    // TTS 语音播报
                    speakFenceAlarm(message)
                } catch (e: Exception) {
                    Timber.e(e, "围栏告警处理失败")
                }
            }, String::class.java)

            // 监听管理员强制改名（平台→设备）
            conn.on("CustomCodeChanged", { data: String ->
                try {
                    Timber.i("收到编号变更: $data")
                    val json = org.json.JSONObject(data)
                    val newCode = json.optString("customCodeDisplay", json.optString("customCode", ""))
                    val updatedAt = json.optLong("updatedAt", 0L)
                    if (newCode.isNotBlank()) {
                        settings.customCode = newCode
                        if (updatedAt > 0) settings.customCodeUpdatedAt = updatedAt
                        Timber.i("设备编号已同步: newCode=$newCode")
                        val notification = PlatformNotification("设备编号已更新为: $newCode")
                        notifications.add(0, notification)
                        onNotificationReceived?.invoke(notification)
                        onCustomCodeChanged?.invoke(newCode)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "CustomCodeChanged 处理失败")
                }
            }, String::class.java)

            connection = conn

            Timber.i("SignalR: 正在连接...")
            try {
                conn.start().blockingAwait()
                conn.invoke("JoinDeviceNotificationGroup", settings.deviceId)
                Timber.i("SignalR: 连接成功，已加入设备组 ${settings.deviceId}")
            } catch (e: Exception) {
                Timber.e(e, "SignalR: 首次连接失败，启动重连守护线程")
                scheduleReconnect()
            }

            registerNetworkCallback()
        } catch (e: Exception) {
            Timber.e(e, "SignalR: 初始化失败 url=${settings.platformApiUrl}/hubs/monitor")
        }
    }

    /**
     * 启动后台无限重连守护线程。
     * 退避策略：2s, 5s, 10s, 30s, 60s (后续重复 60s)。
     * triggerReconnect() 可以中断当前 sleep 立即重试。
     */
    private fun scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            Timber.d("SignalR: 已有重连线程在运行，跳过")
            return
        }
        val t = Thread {
            val delays = longArrayOf(2000, 5000, 10000, 30000, 60000)
            var attempt = 0
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val conn = connection
                    if (conn == null) {
                        Timber.w("SignalR: 重连线程发现 connection=null，退出")
                        return@Thread
                    }
                    if (conn.connectionState == HubConnectionState.CONNECTED) {
                        Timber.i("SignalR: 已连接，重连线程退出 (attempt=$attempt)")
                        return@Thread
                    }
                    val delay = delays.getOrElse(attempt) { 60000L }
                    attempt++
                    try {
                        Thread.sleep(delay)
                    } catch (ie: InterruptedException) {
                        // 被 triggerReconnect 中断 → 立即重试
                        Thread.currentThread().interrupt()
                    }
                    try {
                        Timber.i("SignalR: 第 $attempt 次重连尝试")
                        conn.start().blockingAwait()
                        conn.invoke("JoinDeviceNotificationGroup", settings.deviceId)
                        Timber.i("SignalR: 重连成功（第 $attempt 次尝试）")
                        return@Thread
                    } catch (e: Exception) {
                        Timber.w("SignalR: 第 $attempt 次重连失败: ${e.message}")
                    }
                }
            } finally {
                reconnecting.set(false)
            }
        }.apply {
            name = "SignalR-Reconnect"
            isDaemon = true
        }
        reconnectThread = t
        t.start()
    }

    /**
     * 外部触发立即重连：
     * - 已连接 → 跳过
     * - 重连线程正在 sleep → 中断它立即重试
     * - 无重连线程 → 启动一个
     */
    fun triggerReconnect() {
        val conn = connection
        if (conn == null) {
            Timber.d("SignalR triggerReconnect: connection=null，跳过")
            return
        }
        if (conn.connectionState == HubConnectionState.CONNECTED) {
            Timber.d("SignalR triggerReconnect: 已连接，跳过")
            return
        }
        if (reconnecting.get()) {
            // 有线程在重连，中断它跳过 sleep 立即重试
            reconnectThread?.interrupt()
            Timber.i("SignalR triggerReconnect: 已唤醒在途重连线程")
        } else {
            scheduleReconnect()
        }
    }

    /** 注册系统网络状态回调，网络恢复时立即触发 SignalR 重连 */
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Timber.i("SignalR: 网络变可用 ($network)，触发立即重连")
                    triggerReconnect()
                }
            })
            networkCallbackRegistered = true
            Timber.i("SignalR: 网络状态回调已注册")
        } catch (e: Exception) {
            Timber.w(e, "SignalR: 注册网络回调失败")
        }
    }

    fun disconnect() {
        try {
            connection?.stop()
            connection = null
            Timber.i("SignalR disconnected")
        } catch (e: Exception) {
            Timber.e(e, "SignalR disconnect error")
        }
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
        } catch (e: Exception) {
            Timber.e(e, "TTS shutdown error")
        }
    }

    private fun showSystemNotification(message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "平台通知", NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("平台通知")
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    /** 围栏告警通知（高优先级，带提示音） */
    private fun showFenceAlarmNotification(message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "fence_alarm_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "围栏告警", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "电子围栏告警通知" }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("围栏告警")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    /** TTS 语音播报围栏告警 */
    private fun speakFenceAlarm(message: String) {
        if (!ttsReady || tts == null) {
            Timber.w("TTS 未就绪，跳过语音播报: $message")
            // 降级为提示音
            playAlertTone("high")
            return
        }
        try {
            val text = "注意，$message"
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "fence_alarm_${System.currentTimeMillis()}")
            Timber.i("TTS 语音播报: $text")
        } catch (e: Exception) {
            Timber.e(e, "TTS 语音播报失败")
            playAlertTone("high")
        }
    }

    /** 常驻工单通知（不可滑动删除，直到用户处理） */
    private fun showWorkTaskNotification(title: String, priority: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "work_task_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (priority == "urgent") NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, "作业工单", importance).apply {
                description = "作业工单通知"
            }
            manager.createNotificationChannel(channel)
        }

        val priorityLabel = when (priority) {
            "urgent" -> "[紧急]"
            "high" -> "[高]"
            else -> ""
        }

        // 点击通知打开工单页面
        val intent = android.content.Intent(context, com.hdcollection.enforcement.ui.worktask.WorkTaskListActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

        val np = if (priority == "urgent") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("新工单 $priorityLabel")
            .setContentText(title)
            .setOngoing(true)  // 常驻，不可滑动删除
            .setAutoCancel(false)
            .setPriority(np)
            .setContentIntent(pendingIntent)
            .addAction(0, "查看", pendingIntent)
            .build()

        manager.notify(WORK_TASK_NOTIFY_ID, notification)
    }

    /** 按优先级播放不同提示音 */
    private fun playAlertTone(priority: String) {
        try {
            val toneType = when (priority) {
                "urgent" -> android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK  // 紧急：急促连续音
                "high" -> android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD     // 高：警示音
                else -> android.media.ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE     // 普通：轻提示
            }
            val duration = when (priority) {
                "urgent" -> 3000  // 紧急响 3 秒
                "high" -> 1500   // 高响 1.5 秒
                else -> 800      // 普通响 0.8 秒
            }
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
            tg.startTone(toneType, duration)
            // 延迟释放
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, (duration + 500).toLong())
            Timber.i("工单提示音: priority=$priority, tone=$toneType, duration=$duration")
        } catch (e: Exception) {
            Timber.e(e, "播放提示音失败")
        }
    }

    /** 清除工单常驻通知（用户处理工单后调用） */
    fun clearWorkTaskNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(WORK_TASK_NOTIFY_ID)
    }

    /** 播放 PCM 16bit 8kHz 单声道音频 */
    private fun playPcmAudio(pcmBytes: ByteArray) {
        if (audioTrack == null) {
            val sampleRate = 8000
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(pcmBytes.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            Timber.i("TalkAudio: AudioTrack 已创建, sampleRate=$sampleRate, bufferSize=$bufferSize")
        }
        audioTrack?.write(pcmBytes, 0, pcmBytes.size)
    }

    fun stopTalkAudio() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        Timber.i("TalkAudio: AudioTrack 已释放")
    }

    companion object {
        private const val CHANNEL_ID = "platform_notifications"
        private const val WORK_TASK_NOTIFY_ID = 10001
    }
}
