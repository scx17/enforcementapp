package com.hdcollection.enforcement.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
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

class Camera2Preview(private val activity: Activity, private val surfaceView: SurfaceView) {

    /** 解析远程配置里的分辨率字符串（形如 "1920x1080"），失败回退到 1920x1080 */
    private fun resolveVideoParams(): Triple<Int, Int, Int> {
        // 返回 (width, height, fps)；码率单独读
        val app = activity.application as? com.hdcollection.enforcement.EnforcementApp
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
        val app = activity.application as? com.hdcollection.enforcement.EnforcementApp
        val kbps = app?.remoteConfigManager?.config?.value?.videoBitrateKbps ?: 2000
        return kbps * 1000
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

    private var audioMediaCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioInputThread: Thread? = null
    private var audioEncoderThread: Thread? = null

    private var imageReader: ImageReader? = null
    private var photoCallback: ((File) -> Unit)? = null
    private var pendingPhotoFile: File? = null

    private val previewSurface: Surface
        get() = surfaceView.holder.surface

    private fun previewSurfaceOrNull(): Surface? {
        val s = surfaceView.holder.surface
        return if (s != null && s.isValid) s else null
    }

    init {
        surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                openCamera()
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                closeCamera()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        startBackgroundThread()
        val manager = activity.getSystemService(Activity.CAMERA_SERVICE) as CameraManager
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
                    startPreviewSession()
                    Timber.d("Camera opened")
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

    private fun ensureImageReader() {
        if (imageReader != null) return
        Timber.i("ImageReader 初始化: 1920x1080, JPEG, maxImages=2")
        imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2).also {
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

    /** 兼容旧调用点：回到只含 preview 的基础会话。 */
    private fun startPreviewSession() {
        rebuildCaptureSession()
    }

    fun startEncoding(rtpIp: String, rtpPort: Int, ssrc: Int = 0) {
        if (isEncoding) return
        currentRtpIp = rtpIp
        currentRtpPort = rtpPort
        currentSsrc = ssrc
        val camera = cameraDevice ?: run {
            Timber.w("Camera not open, cannot start encoding")
            return
        }

        try {
            val (encW, encH, encFps) = resolveVideoParams()
            val encBitrateBps = resolveVideoBitrateBps()
            // 创建 MediaCodec H.264 编码器（低延迟配置）
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, encBitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, encFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // 低延迟优化
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_COMPLEXITY, 0) // 最低复杂度，编码更快
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 0) // API 30+ 低延迟
                    setInteger("vendor.rtc-ext-enc-low-latency.enable", 1) // MTK 低延迟
                } catch (_: Exception) {}
                // H264 Baseline Profile 编码最快
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
            Timber.i("编码参数: 分辨率=${encW}x${encH}, 码率=${encBitrateBps/1000}kbps, 帧率=${encFps}fps, Profile=Baseline, CBR模式")
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
            encoderSurface = encSurface

            // 启动 RTP 发送（使用 SDP 指定的 SSRC）
            val sender = RtpSender(rtpIp, rtpPort)
            if (ssrc != 0) sender.setSsrc(ssrc)
            sender.start()
            rtpSender = sender

            // 重建 capture session，包含 encoder；如果正在录像，也把 recorder.surface 一起带上并行
            val recSurfaceAtStart = if (isRecording) mediaRecorder?.surface else null
            val surfaces = mutableListOf(previewSurface, encSurface)
            recSurfaceAtStart?.let { surfaces.add(it) }
            imageReader?.surface?.let { surfaces.add(it) }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface)
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

        Timber.i("编码输出线程启动")
        encoderThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isEncoding) {
                try {
                    val codec = mediaCodec ?: break
                    val index = codec.dequeueOutputBuffer(bufferInfo, 1_000) // 1ms 超时，减少编码延迟
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

                        // IDR 帧前拼接 SPS/PPS
                        val frameData = if (isKeyFrame && spsPps != null) {
                            Timber.d("Encoder: prepending SPS/PPS (${spsPps!!.size}B) to IDR (${data.size}B)")
                            spsPps!! + data
                        } else {
                            data
                        }

                        rtpSender?.sendVideoFrame(frameData, bufferInfo.presentationTimeUs / 1000, isKeyFrame)
                        rtpSender?.flushAudioQueue()  // 视频帧后刷出音频，避免锁竞争
                        Timber.v("RTP frame sent: ${frameData.size} bytes, keyframe=$isKeyFrame")
                    }
                } catch (e: Exception) {
                    if (isEncoding) Timber.e(e, "Encoder output error")
                }
            }
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

    fun startLocalRecording(outputFile: File) {
        if (isRecording) return
        val camera = cameraDevice ?: return

        val (recW, recH, recFps) = resolveVideoParams()
        val recBitrateBps = resolveVideoBitrateBps()
        val app = activity.application as? com.hdcollection.enforcement.EnforcementApp
        val audioOn = app?.remoteConfigManager?.config?.value?.audioEnabled ?: true

        val recorder = MediaRecorder(activity).apply {
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
        val surfaces = mutableListOf(previewSurface, recorder.surface)
        encSurfaceAtStart?.let { surfaces.add(it) }
        imageReader?.surface?.let { surfaces.add(it) }

        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface)
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
