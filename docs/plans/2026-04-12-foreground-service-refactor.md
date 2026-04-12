# MediaCaptureService 重构实施计划（息屏推流）

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 把 Camera2 采集 / 编码 / 推流 / GB28181 SIP 监听全部迁到 ForegroundService，让设备息屏后平台仍能点播。

**Architecture:**
- 新增 `MediaCaptureService`（前台服务，`foregroundServiceType="camera|microphone"`），它持有 cameraDevice、MediaCodec、RtpSender、GB28181Manager、PARTIAL_WAKE_LOCK，生命周期独立于 Activity。
- 把 `Camera2Preview` 改成 SurfaceView **可选**：previewSurface 不再是必选 target，session 至少保留一个 always-on target（ImageReader）即可，preview 通过 attach/detach 动态加入 session。
- MainActivity 通过 `bindService` 拿 Binder 调拍照/录像/切摄像头/挂载预览，回调由 service 反向通知 Activity（注册状态、推流状态等）。

**Tech Stack:** Android Foreground Service + Camera2 + MediaCodec + 已有的 GB28181Manager + Binder（同进程，本地绑定）。

**关键约束：**
- 全程不能丢失拍照、录像、切前后摄像头、SIP 来电（PTT 对讲）这些已有功能。
- 服务与 Activity 通信用本地 Binder（同进程）+ 弱引用回调，不能产生内存泄漏。
- 每个 Phase 完成后必须 adb install 到 `DSJ-2501100484` 设备验证基础流程未回归。
- 调试日志看 `/storage/emulated/0/Android/data/com.hdcollection.enforcement/files/logs/app_yyyyMMdd.log`，**不是** logcat。

**关键文件：**
- `app/src/main/java/com/hdcollection/enforcement/camera/Camera2Preview.kt`（588 行）
- `app/src/main/java/com/hdcollection/enforcement/gb28181/GB28181Manager.kt`
- `app/src/main/java/com/hdcollection/enforcement/ui/main/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`

---

## Phase A — Camera2Preview 解耦 SurfaceView（不动 Activity）

目标：让 Camera2Preview **不再依赖 SurfaceView 生命周期** 来 open/close camera。改成显式 `start()` / `stop()` + `attachPreview()` / `detachPreview()`。这一步先在 Activity 内完成，确保现有功能不回归，再开始 Phase B。

### Task A1: 添加 ImageReader 作为 always-on session target

**文件：** `app/src/main/java/com/hdcollection/enforcement/camera/Camera2Preview.kt`

**问题：** 当前 `rebuildCaptureSession()` 第 175-176 行无条件 `surfaces.add(previewSurface)`，没有 preview 时 session 会因为 0 个 target 失败。需要 ImageReader 作为兜底。

**Step 1：** 把 `ensureImageReader()` 改成在 `openCamera() → onOpened` 里**总是**调用（目前只在拍照前用）。让 ImageReader 在 camera 打开后立即就位。

```kotlin
override fun onOpened(camera: CameraDevice) {
    cameraDevice = camera
    ensureImageReader()   // 新增：保证 always-on target 存在
    startPreviewSession()
    ...
}
```

**Step 2：** `rebuildCaptureSession()` 改成 previewSurface 可选：

```kotlin
private fun rebuildCaptureSession() {
    val camera = cameraDevice ?: return
    ensureImageReader()

    val useEncoder = isEncoding && encoderSurface != null
    val useRecorder = isRecording && mediaRecorder != null
    val recSurface = if (useRecorder) mediaRecorder?.surface else null
    val preview = previewSurfaceOrNull()  // 新增 helper，下面 Step 3 实现

    val surfaces = mutableListOf<Surface>()
    preview?.let { surfaces.add(it) }
    if (useEncoder) surfaces.add(encoderSurface!!)
    if (recSurface != null) surfaces.add(recSurface)
    imageReader?.surface?.let { surfaces.add(it) }

    val template = if (useEncoder || useRecorder)
        CameraDevice.TEMPLATE_RECORD
    else
        CameraDevice.TEMPLATE_PREVIEW

    try {
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cameraDevice == null) return
                captureSession = session
                try {
                    val request = camera.createCaptureRequest(template).apply {
                        preview?.let { addTarget(it) }
                        if (useEncoder) addTarget(encoderSurface!!)
                        if (recSurface != null) addTarget(recSurface)
                    }
                    session.setRepeatingRequest(request.build(), null, bgHandler)
                    Timber.i("Session 重建: preview=${preview != null}, encoder=$useEncoder, recorder=$useRecorder")
                } catch (e: IllegalStateException) {
                    Timber.w(e, "rebuildCaptureSession setRepeatingRequest 失败")
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Timber.e("Session 重建失败")
            }
        }, bgHandler)
    } catch (e: Exception) {
        Timber.e(e, "Session 重建异常")
    }
}
```

**Step 3：** 提交。

```bash
cd /home/scx17/WorkSpace/wang/EnforcementApp
git add app/src/main/java/com/hdcollection/enforcement/camera/Camera2Preview.kt
git commit -m "refactor(camera): allow rebuildCaptureSession without preview surface"
```

---

### Task A2: previewSurface 改成动态 attach / detach

**文件：** `app/src/main/java/com/hdcollection/enforcement/camera/Camera2Preview.kt`

**Step 1：** 把 `private val previewSurface: Surface get() = surfaceView.holder.surface` 改成可空字段 + helper：

```kotlin
private var attachedSurfaceView: SurfaceView? = null
private var attachedSurfaceHolderCallback: android.view.SurfaceHolder.Callback? = null

private fun previewSurfaceOrNull(): Surface? {
    val sv = attachedSurfaceView ?: return null
    val s = sv.holder.surface
    return if (s != null && s.isValid) s else null
}
```

**Step 2：** 添加 `attachPreview` / `detachPreview` 方法，并去掉 init 块里的 holder.addCallback：

```kotlin
fun attachPreview(surfaceView: SurfaceView) {
    if (attachedSurfaceView === surfaceView) return
    detachPreview()
    attachedSurfaceView = surfaceView
    val cb = object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            Timber.i("Preview surfaceCreated → rebuildCaptureSession")
            bgHandler?.post { rebuildCaptureSession() }
        }
        override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {}
        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
            Timber.i("Preview surfaceDestroyed → rebuildCaptureSession (无 preview)")
            // 不再 closeCamera，只重建 session 去掉 preview target
            bgHandler?.post { rebuildCaptureSession() }
        }
    }
    surfaceView.holder.addCallback(cb)
    attachedSurfaceHolderCallback = cb
    // 如果 surface 已经就绪（典型场景：service 先开 camera，activity 后挂载预览），立即 rebuild
    if (surfaceView.holder.surface?.isValid == true) {
        bgHandler?.post { rebuildCaptureSession() }
    }
}

fun detachPreview() {
    val sv = attachedSurfaceView ?: return
    attachedSurfaceHolderCallback?.let { sv.holder.removeCallback(it) }
    attachedSurfaceHolderCallback = null
    attachedSurfaceView = null
    bgHandler?.post { rebuildCaptureSession() }
}
```

**Step 3：** 把构造函数从 `(activity: Activity, surfaceView: SurfaceView)` 改成 `(context: Context)`（service 也能用）。删掉 `init` 块里的 `surfaceView.holder.addCallback(...)`。所有用到 `activity.application` 的地方改用 `context.applicationContext`。

```kotlin
class Camera2Preview(private val context: Context) {
    // ...
    private fun resolveVideoParams(): Triple<Int, Int, Int> {
        val app = context.applicationContext as? com.hdcollection.enforcement.EnforcementApp
        ...
    }
    private fun resolveVideoBitrateBps(): Int {
        val app = context.applicationContext as? com.hdcollection.enforcement.EnforcementApp
        ...
    }
```

`openCamera()` 第 99 行 `activity.getSystemService(Activity.CAMERA_SERVICE)` 改成 `context.getSystemService(Context.CAMERA_SERVICE)`。

`startLocalRecording()` 第 484 行 `MediaRecorder(activity)` 改成 `MediaRecorder(context)`。

**Step 4：** 添加显式 `start()` / `stop()`（替代原来由 surfaceCreated/Destroyed 触发）：

```kotlin
fun start() {
    if (cameraDevice != null) return
    Timber.i("Camera2Preview.start() — openCamera")
    openCamera()
}

fun stop() {
    Timber.i("Camera2Preview.stop() — closeCamera")
    closeCamera()
}
```

**Step 5：** MainActivity 配合改动：
- `Camera2Preview(this, surfaceView)` → `Camera2Preview(this)`
- onCreate 中创建 camera 后立即调 `camera.start()` 和 `camera.attachPreview(surfaceView)`
- onDestroy 中先调 `camera.detachPreview()` 再 `camera.stop()`

**Step 6：** 编译 + 部署 + 启动 App，验证：
- 预览显示正常
- 拍照正常（按物理按键）
- 录像正常（按物理按键）
- 平台点播能推流并显示

```bash
cd /home/scx17/WorkSpace/wang/EnforcementApp
./gradlew assembleDebug
adb -s DSJ-2501100484 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DSJ-2501100484 shell am force-stop com.hdcollection.enforcement
adb -s DSJ-2501100484 shell monkey -p com.hdcollection.enforcement -c android.intent.category.LAUNCHER 1
# 然后人工验证 + 看日志：
adb -s DSJ-2501100484 shell "tail -100 /storage/emulated/0/Android/data/com.hdcollection.enforcement/files/logs/app_$(date +%Y%m%d).log" | grep -iE "Camera|Preview|Session|Encoding|GB28181"
```

**Step 7：** 提交。

```bash
git add -A
git commit -m "refactor(camera): explicit start/stop and attachPreview, decouple from SurfaceView lifecycle"
```

---

## Phase B — 创建 MediaCaptureService 骨架

目标：建一个空跑通的前台服务，能 startForeground、显示通知、提供 LocalBinder。Camera/GB28181 暂时还在 Activity 里。

### Task B1: 创建 MediaCaptureService 类骨架

**文件：** `app/src/main/java/com/hdcollection/enforcement/service/MediaCaptureService.kt`（新建）

**Step 1：** 创建文件：

```kotlin
package com.hdcollection.enforcement.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.ui.main.MainActivity
import timber.log.Timber

/**
 * 媒体采集前台服务：持有 Camera + 编码 + RTP + GB28181 SIP 监听 + WakeLock。
 * 生命周期独立于 Activity，息屏后仍能继续接收平台点播并推流。
 *
 * 当前 Phase B：仅启动前台通知，Camera/GB28181 暂时还在 Activity。
 */
class MediaCaptureService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MediaCaptureService = this@MediaCaptureService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        Timber.i("MediaCaptureService onCreate")
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("MediaCaptureService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Timber.i("MediaCaptureService onDestroy")
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "执法仪后台采集", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保证息屏后仍可被平台点播"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("执法仪运行中")
            .setContentText("视频采集与上报服务")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Timber.i("MediaCaptureService startForeground 完成")
    }

    companion object {
        const val CHANNEL_ID = "media_capture_service"
        const val NOTIFICATION_ID = 1001
    }
}
```

**Step 2：** AndroidManifest 注册 service：

`app/src/main/AndroidManifest.xml`，在 `</application>` 之前：

```xml
<service
    android:name=".service.MediaCaptureService"
    android:exported="false"
    android:foregroundServiceType="camera|microphone" />
```

**Step 3：** MainActivity onCreate 启动并 bind 服务（最早的 setContentView 之后）：

```kotlin
import com.hdcollection.enforcement.service.MediaCaptureService
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

private var mediaService: MediaCaptureService? = null
private val mediaServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        mediaService = (binder as? MediaCaptureService.LocalBinder)?.getService()
        Timber.i("MediaCaptureService connected: $mediaService")
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        mediaService = null
        Timber.w("MediaCaptureService disconnected")
    }
}

// onCreate 内：
val mediaIntent = Intent(this, MediaCaptureService::class.java)
ContextCompat.startForegroundService(this, mediaIntent)
bindService(mediaIntent, mediaServiceConnection, Context.BIND_AUTO_CREATE)

// onDestroy 内：
try { unbindService(mediaServiceConnection) } catch (_: Exception) {}
// 注意：不调 stopService，让服务保持运行直到用户主动退出 App
```

**Step 4：** 编译、部署、启动。验证：
- 通知栏出现 "执法仪运行中"
- 日志中 `MediaCaptureService onCreate` / `startForeground 完成` / `connected` 都有
- 现有功能（预览/拍照/录像/点播）全部不回归

**Step 5：** 提交。

```bash
git add -A
git commit -m "feat(service): add MediaCaptureService skeleton with foreground notification"
```

---

## Phase C — Camera 迁入 Service

目标：MediaCaptureService 持有 Camera2Preview 实例，从 Activity 移走。Activity 通过 Binder 拿引用挂载预览/触发拍照/录像。

### Task C1: Service 持有 Camera2Preview 并启动

**文件：**
- `app/src/main/java/com/hdcollection/enforcement/service/MediaCaptureService.kt`
- `app/src/main/java/com/hdcollection/enforcement/ui/main/MainActivity.kt`

**Step 1：** Service 内增加 camera 字段并暴露：

```kotlin
import com.hdcollection.enforcement.camera.Camera2Preview
import android.view.SurfaceView
import java.io.File

class MediaCaptureService : Service() {
    // ...
    private var camera: Camera2Preview? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("MediaCaptureService onCreate")
        startForegroundWithNotification()
        camera = Camera2Preview(this).also { it.start() }
        Timber.i("Camera2Preview 已在 service 内启动")
    }

    override fun onDestroy() {
        Timber.i("MediaCaptureService onDestroy")
        camera?.detachPreview()
        camera?.stop()
        camera = null
        super.onDestroy()
    }

    // —— 给 Activity 用的代理方法 ——
    fun attachPreview(surfaceView: SurfaceView) { camera?.attachPreview(surfaceView) }
    fun detachPreview() { camera?.detachPreview() }
    fun capturePhoto(out: File, cb: (File) -> Unit) { camera?.capturePhoto(out, cb) }
    fun startLocalRecording(out: File) { camera?.startLocalRecording(out) }
    fun stopLocalRecording() { camera?.stopLocalRecording() }
    fun switchCamera() { camera?.switchCamera() }
    fun isFrontCamera(): Boolean = camera?.isFrontCamera() ?: false
    fun startEncoding(rtpIp: String, rtpPort: Int, ssrc: Int) { camera?.startEncoding(rtpIp, rtpPort, ssrc) }
    fun stopEncoding() { camera?.stopEncoding() }
}
```

**Step 2：** MainActivity 删掉 `private lateinit var camera: Camera2Preview` 字段和它的初始化（122-137 行那段 try/catch）。`val surfaceView = findViewById<SurfaceView>(R.id.surfacePreview)` 留着，但是用法变成：onServiceConnected 里调 `mediaService?.attachPreview(surfaceView)`。

```kotlin
private val mediaServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val svc = (binder as? MediaCaptureService.LocalBinder)?.getService()
        mediaService = svc
        Timber.i("MediaCaptureService connected")
        // 挂载预览
        svc?.attachPreview(findViewById(R.id.surfacePreview))
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        mediaService = null
    }
}
```

onDestroy 里在 unbindService 之前调 `mediaService?.detachPreview()`。

**Step 3：** 把 MainActivity 内所有 `camera.xxx()` 调用改成 `mediaService?.xxx()`：
- `camera.startEncoding(...)` → `mediaService?.startEncoding(...)`
- `camera.stopEncoding()` → `mediaService?.stopEncoding()`
- `camera.capturePhoto(...)` → `mediaService?.capturePhoto(...)`
- `camera.startLocalRecording(...)` → `mediaService?.startLocalRecording(...)`
- `camera.stopLocalRecording()` → `mediaService?.stopLocalRecording()`
- `camera.switchCamera()` → `mediaService?.switchCamera()`
- `camera.isFrontCamera()` → `mediaService?.isFrontCamera() ?: false`

用 grep 找全：

```bash
cd /home/scx17/WorkSpace/wang/EnforcementApp
grep -n "camera\." app/src/main/java/com/hdcollection/enforcement/ui/main/MainActivity.kt
```

**Step 4：** 编译。**预期会有错误**：MainActivity 在 onCreate 同步调用 capturePhoto/startEncoding 等方法时 mediaService 可能还是 null（bindService 是异步的）。但 onCreate 不应该有这种同步调用（推流是 INVITE 触发，拍照是按键触发，都在 onCreate 之后）。如果有同步调用就报错说明那段代码不对。

**Step 5：** 部署测试：
- 预览显示
- 拍照
- 录像
- 切前后摄像头
- 平台点播

**Step 6：** 提交。

```bash
git add -A
git commit -m "refactor: move Camera2Preview ownership into MediaCaptureService"
```

---

## Phase D — GB28181Manager 迁入 Service

目标：GB28181Manager 由 Service 持有并实现 StreamCallback，息屏后继续接收 INVITE。Activity 通过监听器接收注册状态/推流状态变化更新 UI。

### Task D1: 在 Service 内创建 GB28181Manager 并实现 StreamCallback

**文件：** `app/src/main/java/com/hdcollection/enforcement/service/MediaCaptureService.kt`

**Step 1：** Service 添加字段和 StreamCallback 实现：

```kotlin
import com.hdcollection.enforcement.gb28181.GB28181Manager
import com.hdcollection.enforcement.gb28181.StreamCallback
import com.hdcollection.enforcement.data.AppSettings

class MediaCaptureService : Service(), StreamCallback {

    interface Listener {
        fun onRegistered(deviceId: String) {}
        fun onRegistrationFailed(reason: String) {}
        fun onStreamStarted(channelId: String) {}
        fun onStreamStopped(channelId: String) {}
        fun onIntercomReceived(callerInfo: String) {}
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private var gb28181Manager: GB28181Manager? = null
    private lateinit var settings: AppSettings

    override fun onCreate() {
        super.onCreate()
        Timber.i("MediaCaptureService onCreate")
        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        startForegroundWithNotification()
        camera = Camera2Preview(this).also { it.start() }
        gb28181Manager = GB28181Manager(settings, this).also { it.register() }
        Timber.i("GB28181Manager 已在 service 内启动")
    }

    override fun onDestroy() {
        Timber.i("MediaCaptureService onDestroy")
        gb28181Manager?.unregister()
        gb28181Manager?.destroy()
        gb28181Manager = null
        camera?.detachPreview()
        camera?.stop()
        camera = null
        super.onDestroy()
    }

    // —— StreamCallback 实现 ——
    override fun onRegistered(deviceId: String) {
        Timber.i("[Service] GB28181 registered: $deviceId")
        listeners.forEach { it.onRegistered(deviceId) }
    }

    override fun onRegistrationFailed(reason: String) {
        Timber.w("[Service] GB28181 registration failed: $reason")
        listeners.forEach { it.onRegistrationFailed(reason) }
    }

    override fun onStreamStartRequested(channelId: String, rtpIp: String, rtpPort: Int, ssrc: Int) {
        Timber.i("[Service] Stream start: $channelId -> $rtpIp:$rtpPort ssrc=$ssrc")
        camera?.startEncoding(rtpIp, rtpPort, ssrc)
        listeners.forEach { it.onStreamStarted(channelId) }
    }

    override fun onStreamStopRequested(channelId: String) {
        Timber.i("[Service] Stream stop: $channelId")
        camera?.stopEncoding()
        listeners.forEach { it.onStreamStopped(channelId) }
    }

    override fun onIntercomReceived(callerInfo: String) {
        listeners.forEach { it.onIntercomReceived(callerInfo) }
    }

    // 给 Activity 用的查询/控制方法
    fun gb28181TriggerReconnect(reason: String) { gb28181Manager?.triggerReconnect(reason) }
    fun gb28181NotifyNetworkLost(reason: String) { gb28181Manager?.notifyNetworkLost(reason) }
}
```

**Step 2：** MainActivity 删掉 `private lateinit var gb28181Manager: GB28181Manager`，删掉初始化和 `startGB28181()`，删掉 `StreamCallback` 接口实现的方法体（onRegistered / onRegistrationFailed / onStreamStartRequested / onStreamStopRequested / onIntercomReceived）和 class 头的 `, StreamCallback`。

NetworkCallback 部分（registerGbNetworkCallback / unregisterGbNetworkCallback）：删掉，迁到 service。

**Step 3：** Service 内迁入 NetworkCallback：

```kotlin
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

private var gbNetworkCallback: ConnectivityManager.NetworkCallback? = null

private fun registerGbNetworkCallback() {
    if (gbNetworkCallback != null) return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val req = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Timber.i("[Service] GB NetworkCallback: onAvailable")
            gb28181Manager?.triggerReconnect("network onAvailable")
        }
        override fun onLost(network: Network) {
            Timber.i("[Service] GB NetworkCallback: onLost")
            gb28181Manager?.notifyNetworkLost("network onLost")
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                gb28181Manager?.triggerReconnect("network validated")
            }
        }
    }
    cm.registerNetworkCallback(req, cb)
    gbNetworkCallback = cb
    Timber.i("[Service] GB NetworkCallback 已注册")
}

private fun unregisterGbNetworkCallback() {
    val cb = gbNetworkCallback ?: return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    runCatching { cm.unregisterNetworkCallback(cb) }
    gbNetworkCallback = null
}
```

在 onCreate 的 `gb28181Manager?.register()` 之后调 `registerGbNetworkCallback()`，在 onDestroy 的 `gb28181Manager?.destroy()` 之前调 `unregisterGbNetworkCallback()`。

**Step 4：** MainActivity 实现 Listener 接口，在 onServiceConnected 中调 `addListener(this)`：

```kotlin
class MainActivity : AppCompatActivity(), MediaCaptureService.Listener {
    // ...

    override fun onRegistered(deviceId: String) {
        Timber.i("UI: GB28181 registered: $deviceId")
        runOnUiThread {
            updateStreamStatus("注册在线", "#4CAF50")
            checkPendingWorkTasks()
        }
    }

    override fun onRegistrationFailed(reason: String) {
        runOnUiThread { updateStreamStatus("断网", "#F44336") }
    }

    override fun onStreamStarted(channelId: String) {
        runOnUiThread { updateStreamStatus("推流中", "#2196F3") }
    }

    override fun onStreamStopped(channelId: String) {
        runOnUiThread { updateStreamStatus("注册在线", "#4CAF50") }
    }
}

private val mediaServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val svc = (binder as? MediaCaptureService.LocalBinder)?.getService()
        mediaService = svc
        svc?.addListener(this@MainActivity)
        svc?.attachPreview(findViewById(R.id.surfacePreview))
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        mediaService = null
    }
}

// onDestroy:
mediaService?.removeListener(this)
mediaService?.detachPreview()
try { unbindService(mediaServiceConnection) } catch (_: Exception) {}
```

**Step 5：** 编译 + 部署 + 测试：
- 启动后通知栏看到服务运行
- UI 显示"注册在线"
- 平台点播能成功
- 切到桌面/锁屏后回到 App 状态正常
- 上一轮的 WiFi 断重连恢复测试仍然能正常工作（用 `adb shell svc wifi disable / enable`）

**Step 6：** 提交。

```bash
git add -A
git commit -m "refactor(gb28181): move GB28181Manager and NetworkCallback into MediaCaptureService"
```

---

## Phase E — WakeLock 迁入 Service + 验证息屏推流

### Task E1: WakeLock 迁到 Service

**文件：** `app/src/main/java/com/hdcollection/enforcement/service/MediaCaptureService.kt`、`MainActivity.kt`

**Step 1：** Service 内 acquire WAKE_LOCK：

```kotlin
import android.os.PowerManager

private var wakeLock: PowerManager.WakeLock? = null

override fun onCreate() {
    super.onCreate()
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "EnforcementApp::MediaCaptureService"
    ).apply { acquire() }
    Timber.i("Service WakeLock acquired")
    // ... 后续 startForeground / camera / gb28181
}

override fun onDestroy() {
    // ... 后续清理
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    Timber.i("Service WakeLock released")
    super.onDestroy()
}
```

**Step 2：** MainActivity 删掉 `private var wakeLock: PowerManager.WakeLock? = null` 以及 onCreate 中的 acquire、onDestroy 中的 release。Activity 不再需要 wakelock，因为 service 接管了。

**Step 3：** AndroidManifest 中 MainActivity 标签的 `android:keepScreenOn="true"` 保留——这只影响 Activity 在前台时屏幕不息，不影响息屏后服务运行。

**Step 4：** 编译部署。

**Step 5：** 提交。

```bash
git add -A
git commit -m "refactor: move PARTIAL_WAKE_LOCK from MainActivity to MediaCaptureService"
```

---

### Task E2: 息屏推流端到端验证

**Step 1：** 启动 App，确认平台显示设备在线。

**Step 2：** 用 adb 模拟息屏：

```bash
adb -s DSJ-2501100484 shell input keyevent KEYCODE_POWER
```

确认屏幕熄灭。

**Step 3：** 立即在平台点播该设备。

**Step 4：** 检查日志（注意此时设备息屏，logcat 可能受限，主要看本地日志文件）：

```bash
adb -s DSJ-2501100484 shell "tail -200 /storage/emulated/0/Android/data/com.hdcollection.enforcement/files/logs/app_$(date +%Y%m%d).log" | grep -iE "Stream|Camera|Encoding|GB28181 INVITE|received from"
```

预期看到：
- `[Service] Stream start: ... -> rtpIp:rtpPort`
- `Session 重建: preview=false, encoder=true, recorder=false`
- `Camera encoding started`
- 没有 `closeCamera` / `surfaceDestroyed` 触发

**Step 5：** 平台端确认能看到画面（即使没有 preview 也能编码出画面，因为 session 没有 preview target，但有 encoder target）。

**Step 6：** 唤醒屏幕：

```bash
adb -s DSJ-2501100484 shell input keyevent KEYCODE_POWER
adb -s DSJ-2501100484 shell input keyevent KEYCODE_WAKEUP
```

确认 preview 重新挂载，预览画面恢复正常，推流不中断。

**Step 7：** 平台停止点播，确认编码停止。

**Step 8：** 如果上面任何一步失败，**不要** "再试一下"，回到 Phase 1 用 systematic-debugging 找根因。常见可能问题：
- Doze 模式限制 socket I/O —— 需要把服务加到电池优化白名单
- SurfaceView destroy 时仍然意外 closeCamera —— 检查 Phase A 改动是否完整
- Camera2 session 在 0 个 surface 时报错 —— 确认 ImageReader always-on
- 通知缺失 → 服务被系统杀 —— 确认 startForeground 调用成功

**Step 9：** 验收通过后提交（可能没有代码改动，只是确认验收）：

```bash
git commit --allow-empty -m "test: verify screen-off streaming works end-to-end"
```

---

## Phase F — 收尾

### Task F1: 清理 MainActivity 中残留的 `onPause 拉回前台` hack

**文件：** `app/src/main/java/com/hdcollection/enforcement/ui/main/MainActivity.kt:181-195`

现在有了前台服务，Activity 的 `300ms 后 startActivity` 拉回前台机制可能不再需要。如果实测确认服务能保持运行而 Activity 不需要常驻前台，删掉这段逻辑或者只保留"被其它内部 Activity 切走"的判断。

**Step 1：** 评估 + 清理。

**Step 2：** 测试 Activity 切走/切回是否正常。

**Step 3：** 提交。

---

### Task F2: 更新 troubleshooting 文档

**文件：** `docs/deployment/troubleshooting.md`

记录本次重构动机（息屏不能推流）、根因（无前台服务 + SurfaceView destroyed 关 camera）、解决方案（迁入 ForegroundService + 解耦 SurfaceView）。

---

## 风险与回滚

**风险点：**
1. Camera2 在 0 个 surface 上 createCaptureSession 行为不一定可靠 —— 用 ImageReader always-on 兜底，必要时改成"始终保留 encoder surface 即使不推流"。
2. SurfaceView 的 attach/detach 在 Activity 重建时可能漏：旧 SurfaceView 已 destroyed 但 detachPreview 还没调，新 SurfaceView 又 attach。Service 内 attachedSurfaceView 字段需要保证只关联当前的。
3. Android 14+ 对 FOREGROUND_SERVICE_TYPE_CAMERA 的限制：必须从 Activity（前台）启动 service 才能拿 camera 权限。本计划在 onCreate 启动是 OK 的。
4. 厂商定制 ROM 的电池优化可能仍然杀服务，需要测试后看是否要引导用户加白名单。

**回滚：** 每个 Phase 提交后都是可用状态。如果 Phase D 验证失败，可以 `git revert` 回到 Phase C。
