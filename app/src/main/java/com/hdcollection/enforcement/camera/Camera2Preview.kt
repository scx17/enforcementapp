package com.hdcollection.enforcement.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import com.hdcollection.enforcement.gb28181.RtpSender
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class Camera2Preview(private val context: Context) {

    /** 解析远程配置里的分辨率字符串（形如 "1920x1080"），失败回退到 1920x1080 */
    private fun resolveVideoParams(): Triple<Int, Int, Int> {
        // 返回 (width, height, fps)；码率单独读
        val app = context.applicationContext as? com.hdcollection.enforcement.EnforcementApp
        val cfg = app?.remoteConfigManager?.config?.value
        val resStr = cfg?.videoResolution ?: "1920x1080"
        val fps = cfg?.videoFps ?: 25
        return try {
            val (w, h) = resStr.split("x", "X").let { it[0].toInt() to it[1].toInt() }
            Triple(w, h, fps)
        } catch (_: Exception) {
            Triple(1920, 1080, fps)
        }
    }

    private fun resolveVideoBitrateBps(): Int {
        val app = context.applicationContext as? com.hdcollection.enforcement.EnforcementApp
        val kbps = app?.remoteConfigManager?.config?.value?.videoBitrateKbps ?: 2000
        return kbps * 1000
    }

    /**
     * 校验编码器目标尺寸是否在当前摄像头支持的输出尺寸列表里。
     * 不支持时按优先级回退：1280x720 → 1920x1080 → 摄像头最大尺寸 → 1280x720（兜底）。
     *
     * 老机型（BT280T/SDJW_F2S 等）的 Camera2 HAL 不接受 848x480 之类的非标准尺寸，
     * 不预先校验直接 createCaptureSession 会失败 ("Unsupported set of inputs/outputs")，
     * 编码器接不上数据，App 显示推流中但平台拉不到流。
     */
    private fun ensureSupportedEncoderSize(w: Int, h: Int): Pair<Int, Int> {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK
            val targetId = ids.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: ids.first()
            val map = cm.getCameraCharacteristics(targetId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaCodec::class.java) ?: emptyArray()
            if (sizes.any { it.width == w && it.height == h }) {
                Pair(w, h)
            } else {
                val fb = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
                    ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                    ?: sizes.maxByOrNull { it.width.toLong() * it.height }
                Timber.w("摄像头不支持编码尺寸 ${w}x${h}，回退到 ${fb?.width}x${fb?.height}（支持列表共 ${sizes.size} 项）")
                fb?.let { Pair(it.width, it.height) } ?: Pair(1280, 720)
            }
        } catch (e: Exception) {
            Timber.w(e, "查询摄像头支持尺寸失败，使用 1280x720 兜底")
            Pair(1280, 720)
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null // 推流编码器 Surface 缓存，用于 session 并存时复用
    private var rtpSender: RtpSender? = null
    private var encoderThread: Thread? = null
    private var isEncoding = false
    private var currentRtpIp: String? = null
    private var currentRtpPort: Int = 0
    private var currentSsrc: Int = 0
    private var pendingResumeEncoding = false

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentRecordingFile: File? = null
    private var useFrontCamera = false

    private var audioRecorder: MediaRecorder? = null
    var isAudioRecording: Boolean = false
        private set
    private var audioStartTime: Long = 0L

    private var audioMediaCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioInputThread: Thread? = null
    private var audioEncoderThread: Thread? = null

    private var imageReader: ImageReader? = null
    private var photoCallback: ((File) -> Unit)? = null
    private var pendingPhotoFile: File? = null

    @Volatile
    private var attachedSurfaceView: SurfaceView? = null
    private var attachedSurfaceHolderCallback: android.view.SurfaceHolder.Callback? = null

    private fun previewSurfaceOrNull(): Surface? {
        val sv = attachedSurfaceView ?: return null
        val s: Surface? = sv.holder.surface
        return if (s != null && s.isValid) s else null
    }

    fun start() {
        if (cameraDevice != null) {
            Timber.d("Camera2Preview.start(): camera already open, skip")
            return
        }
        Timber.i("Camera2Preview.start() → openCamera")
        openCamera()
    }

    fun stop() {
        Timber.i("Camera2Preview.stop() → closeCamera")
        closeCamera()
    }

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
                Timber.i("Preview surfaceDestroyed → rebuildCaptureSession (no preview)")
                bgHandler?.post { rebuildCaptureSession() }
            }
        }
        surfaceView.holder.addCallback(cb)
        attachedSurfaceHolderCallback = cb
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

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        startBackgroundThread()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (useFrontCamera)
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        else
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: manager.cameraIdList.firstOrNull() ?: return
        Timber.i("Opening camera: id=$cameraId, front=$useFrontCamera")
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    ensureImageReader()
                    Timber.d("Camera opened")
                    // 如果预览 Surface 已 attach（如 QR 扫码抢占相机后重开场景），主动重建 session，
                    // 否则 surfaceCreated 已触发过一次、这里不会再回调，主界面会黑屏
                    if (attachedSurfaceView != null && previewSurfaceOrNull() != null) {
                        Timber.i("Camera opened with existing preview surface, 主动重建 session")
                        bgHandler?.post { rebuildCaptureSession() }
                    }
                    // 切换摄像头后恢复推流
                    if (pendingResumeEncoding && currentRtpIp != null) {
                        pendingResumeEncoding = false
                        bgHandler?.postDelayed({
                            Timber.i("切换摄像头后恢复推流: $currentRtpIp:$currentRtpPort")
                            startEncoding(currentRtpIp!!, currentRtpPort, currentSsrc)
                        }, 300)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    Timber.w("Camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Timber.e("Camera error: $error")
                }
            }, bgHandler)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open camera")
        }
    }

    private var currentPhotoSize: Size = Size(1920, 1080)

    private fun resolvePhotoSize(): Size {
        val app = context.applicationContext as? com.hdcollection.enforcement.EnforcementApp
        val resStr = app?.remoteConfigManager?.config?.value?.photoResolution ?: "1920x1080"
        return try {
            val (w, h) = resStr.split("x", "X").let { it[0].toInt() to it[1].toInt() }
            Size(w, h)
        } catch (_: Exception) {
            Size(1920, 1080)
        }
    }

    private fun ensureImageReader() {
        val target = resolvePhotoSize()
        // 配置变化：分辨率不一致时销毁旧 reader 让本方法重建
        if (imageReader != null && (target.width != currentPhotoSize.width || target.height != currentPhotoSize.height)) {
            Timber.i("ImageReader 尺寸变化 ${currentPhotoSize.width}x${currentPhotoSize.height} → ${target.width}x${target.height}, 重建")
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = null
        }
        if (imageReader != null) return
        currentPhotoSize = target
        Timber.i("ImageReader 初始化: ${target.width}x${target.height}, JPEG, maxImages=2")
        imageReader = ImageReader.newInstance(target.width, target.height, ImageFormat.JPEG, 2).also {
            it.setOnImageAvailableListener({ reader ->
                val image: Image? = reader.acquireLatestImage()
                image?.let { img ->
                    val buffer: ByteBuffer = img.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    img.close()
                    pendingPhotoFile?.let { file ->
                        FileOutputStream(file).use { out -> out.write(bytes) }
                        photoCallback?.invoke(file)
                        pendingPhotoFile = null
                        photoCallback = null
                    }
                }
            }, bgHandler)
        }
    }

    /**
     * 根据当前推流/录像状态重建 CaptureSession。
     * 同一个 session 里同时包含 preview、推流编码器、录像 MediaRecorder 三个 Surface，
     * 让录像和推流真正并行，切换时画面流不中断。
     */
    private fun rebuildCaptureSession() {
        val camera = cameraDevice ?: return
        ensureImageReader()

        val useEncoder = isEncoding && encoderSurface != null
        val useRecorder = isRecording && mediaRecorder != null
        val recSurface = if (useRecorder) mediaRecorder?.surface else null
        val preview = previewSurfaceOrNull()

        // 没有任何活跃输出 → 无需 capture session（息屏 + 未推流 + 未录像时的正常状态）
        if (preview == null && !useEncoder && recSurface == null) {
            captureSession?.close()
            captureSession = null
            Timber.i("rebuildCaptureSession: 无活跃 target, 关闭 session (息屏待命)")
            return
        }

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
                    if (cameraDevice == null) {
                        Timber.w("rebuildCaptureSession: camera closed during onConfigured, skip")
                        return
                    }
                    captureSession = session
                    try {
                        val request = camera.createCaptureRequest(template).apply {
                            preview?.let { addTarget(it) }
                            if (useEncoder) addTarget(encoderSurface!!)
                            if (recSurface != null) addTarget(recSurface)
                        }
                        session.setRepeatingRequest(request.build(), null, bgHandler)
                        Timber.i("Session 重建成功: preview=${preview != null}, encoder=$useEncoder, recorder=$useRecorder, template=${if (template == CameraDevice.TEMPLATE_RECORD) "RECORD" else "PREVIEW"}")
                    } catch (e: IllegalStateException) {
                        Timber.w(e, "rebuildCaptureSession: camera closed during setRepeatingRequest")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Timber.e("Session 重建失败: preview=${preview != null}, encoder=$useEncoder, recorder=$useRecorder")
                }
            }, bgHandler)
        } catch (e: Exception) {
            Timber.e(e, "Session 重建异常")
        }
    }

    fun startEncoding(rtpIp: String, rtpPort: Int, ssrc: Int = 0) {
        if (isEncoding) return
        currentRtpIp = rtpIp
        currentRtpPort = rtpPort
        currentSsrc = ssrc
        // camera 被系统回收（例如 MTK 平台息屏后 HAL 触发 onDisconnected）时自动重开
        val camera = cameraDevice ?: run {
            Timber.w("Camera not open, 触发重开后延迟推流: $rtpIp:$rtpPort ssrc=$ssrc")
            pendingResumeEncoding = true
            openCamera()
            return
        }

        try {
            val (rawW, rawH, encFps) = resolveVideoParams()
            val (encW, encH) = ensureSupportedEncoderSize(rawW, rawH)
            val encBitrateBps = resolveVideoBitrateBps()
            // 创建 MediaCodec H.264 编码器（低延迟配置）
            // GOP = 1 秒：丢帧后 IDR 间隔短，移动场景马赛克恢复快（开销 ~10% 码率）
            // Profile 仍用 Baseline@3.1：尝试过 Main 但 DSJ_Z6 等部分厂商编码器
            // 在 Main 模式下输出 NAL 与平台/flv.js 解码器不匹配（出现竖向色块）
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, encBitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, encFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_COMPLEXITY, 0)
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                    setInteger("vendor.rtc-ext-enc-low-latency.enable", 1)
                } catch (_: Exception) {}
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
            Timber.i("编码参数: 分辨率=${encW}x${encH}, 码率=${encBitrateBps/1000}kbps, 帧率=${encFps}fps, GOP=1s, Profile=Baseline@3.1, CBR模式")
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
            encoderSurface = encSurface

            // 启动 RTP 发送（使用 SDP 指定的 SSRC + 设置帧率让 pacing 正确分配包间延迟）
            val sender = RtpSender(rtpIp, rtpPort)
            if (ssrc != 0) sender.setSsrc(ssrc)
            sender.setFrameRate(encFps)
            sender.start()
            rtpSender = sender

            // 重建 capture session，包含 encoder；如果正在录像，也把 recorder.surface 一起带上并行
            val recSurfaceAtStart = if (isRecording) mediaRecorder?.surface else null
            val preview = previewSurfaceOrNull()
            val surfaces = mutableListOf<Surface>()
            preview?.let { surfaces.add(it) }
            surfaces.add(encSurface)
            recSurfaceAtStart?.let { surfaces.add(it) }
            imageReader?.surface?.let { surfaces.add(it) }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        preview?.let { addTarget(it) }
                        addTarget(encSurface)
                        recSurfaceAtStart?.let { addTarget(it) }
                    }
                    session.setRepeatingRequest(request.build(), null, bgHandler)
                    isEncoding = true
                    startEncoderOutputLoop()
                    startAudioEncoding()
                    Timber.i("Camera encoding started (video+audio) -> $rtpIp:$rtpPort, withRecorder=${recSurfaceAtStart != null}")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Timber.e("Encoding session configure failed")
                }
            }, bgHandler)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start encoding")
        }
    }

    private fun startEncoderOutputLoop() {
        var spsPps: ByteArray? = null  // 缓存 SPS/PPS，拼接到每个 IDR 帧前
        var frameCount = 0

        Timber.i("编码输出线程启动")
        encoderThread = Thread {
            // 提升编码输出线程优先级：低端 CPU（msm8953 等）默认线程优先级被系统服务抢占，
            // 偶发几百毫秒不出帧导致客户端缓冲耗尽卡顿。URGENT_AUDIO 是 -19，仅次于 RT。
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Exception) {
                Timber.w(e, "提升编码线程优先级失败")
            }
            val bufferInfo = MediaCodec.BufferInfo()
            while (isEncoding) {
                try {
                    val codec = mediaCodec ?: break
                    val index = codec.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms 超时
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Timber.i("Encoder: OUTPUT_FORMAT_CHANGED: ${codec.outputFormat}")
                        continue
                    }
                    if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        val data = ByteArray(bufferInfo.size)
                        buffer.get(data)
                        codec.releaseOutputBuffer(index, false)

                        // CODEC_CONFIG 帧包含 SPS/PPS，缓存它
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            spsPps = data.copyOf()
                            Timber.i("Encoder: SPS/PPS cached, ${data.size} bytes")
                            continue  // 不直接发送，后续拼到 IDR 前
                        }

                        val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        frameCount++

                        // IDR 帧由 sender 直接拼接 SPS/PPS 前缀（不再 spsPps + data 创建新数组）
                        val prefix = if (isKeyFrame) spsPps else null
                        if (isKeyFrame) {
                            Timber.i("Encoder: IDR #$frameCount (${data.size}B), SPS/PPS=${spsPps?.size ?: 0}B, pts=${bufferInfo.presentationTimeUs}")
                        } else if (frameCount <= 5 || frameCount % 50 == 0) {
                            Timber.i("Encoder: P-frame #$frameCount (${data.size}B), pts=${bufferInfo.presentationTimeUs}")
                        }

                        rtpSender?.sendVideoFrame(data, bufferInfo.presentationTimeUs / 1000, isKeyFrame, prefix)
                        rtpSender?.flushAudioQueue()  // 视频帧后刷出音频，避免锁竞争
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // 正常超时，继续
                    }
                } catch (e: Exception) {
                    if (isEncoding) Timber.e(e, "Encoder output error (frameCount=$frameCount)")
                }
            }
            Timber.i("编码输出线程结束 (totalFrames=$frameCount)")
        }.also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioEncoding() {
        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val pcmFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, pcmFormat)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate, channelConfig, pcmFormat,
                minBufSize * 2
            )
            audioRecord = recorder

            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufSize * 2)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioMediaCodec = codec
            recorder.startRecording()

            Timber.i("音频编码启动: AAC-LC, ${sampleRate}Hz, Mono, 64kbps (队列模式)")

            // 输入线程: 从麦克风读取 PCM 送入编码器（低优先级）
            audioInputThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val buffer = ByteArray(minBufSize)
                while (isEncoding) {
                    try {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val inputIndex = codec.dequeueInputBuffer(5_000)
                            if (inputIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inputIndex)!!
                                inputBuffer.clear()
                                inputBuffer.put(buffer, 0, read)
                                codec.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                            }
                        }
                    } catch (e: Exception) {
                        if (isEncoding) Timber.e(e, "音频输入错误")
                    }
                }
            }.also { it.start() }

            // 输出线程: 读取编码后的 AAC 帧入队到 RtpSender（不直接发送，由视频线程统一刷出）
            audioEncoderThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val bufferInfo = MediaCodec.BufferInfo()
                while (isEncoding) {
                    try {
                        val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                        if (index >= 0) {
                            val outBuffer = codec.getOutputBuffer(index)!!
                            val data = ByteArray(bufferInfo.size)
                            outBuffer.get(data)
                            codec.releaseOutputBuffer(index, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                Timber.i("AudioEncoder: config ${data.size} bytes (skipped)")
                                continue
                            }

                            // 入队而非直接发送，由视频线程 flushAudioQueue() 统一发送
                            rtpSender?.queueAudioFrame(data, bufferInfo.presentationTimeUs / 1000)
                        }
                    } catch (e: Exception) {
                        if (isEncoding) Timber.e(e, "音频编码输出错误")
                    }
                }
            }.also { it.start() }
        } catch (e: Exception) {
            Timber.e(e, "音频编码启动失败")
        }
    }

    private fun stopAudioEncoding() {
        audioInputThread?.join(2000)
        audioInputThread = null
        audioEncoderThread?.join(2000)
        audioEncoderThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        try { audioMediaCodec?.apply { stop(); release() } } catch (_: Exception) {}
        audioMediaCodec = null
        Timber.i("音频编码已停止")
    }

    /**
     * 远程配置变更后热应用视频编码参数。
     * 仅当正在推流时才会重启编码器；分辨率/码率/帧率任一不一致就重启。
     * 拍照分辨率变化通过 [ensureImageReader] 在下次拍照前自动重建，无需走这里。
     */
    fun applyVideoConfigIfChanged() {
        if (!isEncoding) return
        val (rawW, rawH, newFps) = resolveVideoParams()
        // 用 fallback 后的实际尺寸做相等判断，避免推 848x480 之类不支持尺寸时
        // 永远不等于当前 1280x720 而无限触发重启
        val (newW, newH) = ensureSupportedEncoderSize(rawW, rawH)
        val newBitrateBps = resolveVideoBitrateBps()
        val mc = mediaCodec
        if (mc != null) {
            try {
                val cur = mc.outputFormat
                val curW = cur.getInteger(MediaFormat.KEY_WIDTH)
                val curH = cur.getInteger(MediaFormat.KEY_HEIGHT)
                val curFps = if (cur.containsKey(MediaFormat.KEY_FRAME_RATE)) cur.getInteger(MediaFormat.KEY_FRAME_RATE) else newFps
                val curBr = if (cur.containsKey(MediaFormat.KEY_BIT_RATE)) cur.getInteger(MediaFormat.KEY_BIT_RATE) else newBitrateBps
                if (curW == newW && curH == newH && curFps == newFps && curBr == newBitrateBps) {
                    return
                }
                Timber.i("视频参数变化, 热重启编码器: ${curW}x${curH}@${curFps}fps/${curBr/1000}kbps → ${newW}x${newH}@${newFps}fps/${newBitrateBps/1000}kbps")
            } catch (_: Exception) {
                Timber.i("视频参数变化, 热重启编码器（旧参数读取失败）: 目标 ${newW}x${newH}@${newFps}fps/${newBitrateBps/1000}kbps")
            }
        }
        val ip = currentRtpIp
        val port = currentRtpPort
        val ssrc = currentSsrc
        if (ip.isNullOrEmpty() || port == 0) {
            Timber.w("热重启编码器跳过：RTP 目标缺失")
            return
        }
        stopEncoding()
        startEncoding(ip, port, ssrc)
    }

    fun stopEncoding() {
        if (!isEncoding) return
        isEncoding = false
        Timber.i("编码输出线程停止中...")
        encoderThread?.join(2000)
        encoderThread = null
        Timber.i("视频编码输出线程已停止")

        stopAudioEncoding()

        mediaCodec?.apply { stop(); release() }
        mediaCodec = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
        rtpSender?.stop()
        rtpSender = null

        // 重建会话：若仍在录像则保留录像 surface，否则退到预览
        rebuildCaptureSession()
        Timber.i("Camera encoding stopped (video+audio), isRecording=$isRecording")
    }

    fun capturePhoto(outputFile: File, callback: (File) -> Unit) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

        pendingPhotoFile = outputFile
        photoCallback = callback

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
            }
            session.capture(request.build(), null, bgHandler)
            Timber.d("Photo capture triggered: ${outputFile.name}")
        } catch (e: Exception) {
            Timber.e(e, "Photo capture failed")
        }
    }

    /** 兼容新旧 Android 的 MediaRecorder 构造：API 31+ 用 Context 构造，老版本用无参构造。 */
    private fun newMediaRecorder(): MediaRecorder =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    fun startLocalRecording(outputFile: File) {
        if (isRecording) return
        val camera = cameraDevice ?: return

        val (recW, recH, recFps) = resolveVideoParams()
        val recBitrateBps = resolveVideoBitrateBps()
        val app = context.applicationContext as? com.hdcollection.enforcement.EnforcementApp
        val audioOn = app?.remoteConfigManager?.config?.value?.audioEnabled ?: true

        val recorder = newMediaRecorder().apply {
            if (audioOn) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoEncodingBitRate(recBitrateBps)
            setVideoFrameRate(recFps)
            setVideoSize(recW, recH)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audioOn) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
        Timber.i("本地录像参数: 分辨率=${recW}x${recH}, 码率=${recBitrateBps/1000}kbps, 帧率=${recFps}fps, audio=$audioOn")
        mediaRecorder = recorder
        currentRecordingFile = outputFile

        // 如果正在推流，新 session 必须同时包含 encoder surface，否则推流会断
        val encSurfaceAtStart = if (isEncoding) encoderSurface else null
        val preview = previewSurfaceOrNull()
        val surfaces = mutableListOf<Surface>()
        preview?.let { surfaces.add(it) }
        surfaces.add(recorder.surface)
        encSurfaceAtStart?.let { surfaces.add(it) }
        imageReader?.surface?.let { surfaces.add(it) }

        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        preview?.let { addTarget(it) }
                        addTarget(recorder.surface)
                        encSurfaceAtStart?.let { addTarget(it) }
                    }
                    session.setRepeatingRequest(request.build(), null, bgHandler)
                    recorder.start()
                    isRecording = true
                    Timber.i("Local recording started: ${outputFile.name}, withEncoder=${encSurfaceAtStart != null}")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Timber.e("Recording session configure failed")
                }
            }, bgHandler)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            recorder.release()
            mediaRecorder = null
        }
    }

    fun stopLocalRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping recorder")
        }
        mediaRecorder = null
        // 重建会话：若还在推流则保留 encoder surface 不断流，否则退到预览
        rebuildCaptureSession()
        Timber.i("Local recording stopped: ${currentRecordingFile?.name}, isEncoding=$isEncoding")
        currentRecordingFile = null
    }

    fun startAudioRecording(file: File) {
        if (isAudioRecording) {
            Timber.w("startAudioRecording ignored: already recording, file=${file.name}")
            return
        }
        try {
            val recorder = newMediaRecorder()
            // VOICE_RECOGNITION 比 MIC 优先级更高，在部分设备（如老 2 的 smarteye.mcu 占麦）可绕开 audio policy 冲突
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            audioRecorder = recorder
            isAudioRecording = true
            audioStartTime = System.currentTimeMillis()
            Timber.i("Audio recording started: file=${file.name}, path=${file.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Audio recording start failed: file=${file.name}")
            audioRecorder?.runCatching { release() }
            audioRecorder = null
            isAudioRecording = false
            throw e
        }
    }

    fun stopAudioRecording(): Long {
        if (!isAudioRecording) {
            Timber.w("stopAudioRecording ignored: not currently recording")
            return 0L
        }
        val duration = System.currentTimeMillis() - audioStartTime
        try {
            audioRecorder?.stop()
        } catch (e: Exception) {
            Timber.e(e, "Audio recording stop() failed, will still release recorder")
        }
        audioRecorder?.runCatching { release() }
        audioRecorder = null
        isAudioRecording = false
        Timber.i("Audio recording stopped: duration_ms=$duration")
        return duration
    }

    /** 切换前置/后置摄像头 */
    fun switchCamera() {
        pendingResumeEncoding = isEncoding
        useFrontCamera = !useFrontCamera
        Timber.i("切换摄像头: front=$useFrontCamera, resumeEncoding=$pendingResumeEncoding")
        closeCamera()
        openCamera()
    }

    fun isFrontCamera() = useFrontCamera

    private fun closeCamera() {
        Timber.i("摄像头关闭流程开始")
        stopEncoding()
        if (isRecording) stopLocalRecording()
        captureSession?.close()
        captureSession = null
        Timber.d("CaptureSession 已关闭")
        cameraDevice?.close()
        cameraDevice = null
        Timber.d("CameraDevice 已关闭")
        imageReader?.close()
        imageReader = null
        Timber.d("ImageReader 已关闭")
        stopBackgroundThread()
        Timber.i("摄像头关闭流程完成")
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("CameraBackground").also {
            it.start()
            bgHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        bgThread?.join()
        bgThread = null
        bgHandler = null
    }
}
