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

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var mediaCodec: MediaCodec? = null
    private var rtpSender: RtpSender? = null
    private var encoderThread: Thread? = null
    private var isEncoding = false

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
                    startPreviewSession()
                    Timber.d("Camera opened")
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

    private fun startPreviewSession() {
        val camera = cameraDevice ?: return
        val surfaces = mutableListOf(previewSurface)
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
            surfaces.add(it.surface)
        }

        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                    }
                    session.setRepeatingRequest(request.build(), null, bgHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Timber.e("Camera session configure failed")
                }
            }, bgHandler)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create capture session")
        }
    }

    fun startEncoding(rtpIp: String, rtpPort: Int, ssrc: Int = 0) {
        if (isEncoding) return
        val camera = cameraDevice ?: run {
            Timber.w("Camera not open, cannot start encoding")
            return
        }

        try {
            // 创建 MediaCodec H.264 编码器（低延迟配置）
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 25)
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
            Timber.i("编码参数: 分辨率=1920x1080, 码率=2000kbps, 帧率=25fps, Profile=Baseline, CBR模式")
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec

            // 启动 RTP 发送（使用 SDP 指定的 SSRC）
            val sender = RtpSender(rtpIp, rtpPort)
            if (ssrc != 0) sender.setSsrc(ssrc)
            sender.start()
            rtpSender = sender

            // 重建 capture session，加入编码器 Surface
            val surfaces = mutableListOf(previewSurface, encoderSurface)
            imageReader?.surface?.let { surfaces.add(it) }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface)
                        addTarget(encoderSurface)
                    }
                    session.setRepeatingRequest(request.build(), null, bgHandler)
                    isEncoding = true
                    startEncoderOutputLoop()
                    startAudioEncoding()
                    Timber.i("Camera encoding started (video+audio) -> $rtpIp:$rtpPort")
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
        rtpSender?.stop()
        rtpSender = null

        // 恢复预览 session（不含编码器 Surface）
        startPreviewSession()
        Timber.i("Camera encoding stopped (video+audio)")
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

        val recorder = MediaRecorder(activity).apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoEncodingBitRate(4_000_000)
            setVideoFrameRate(30)
            setVideoSize(1920, 1080)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
        mediaRecorder = recorder
        currentRecordingFile = outputFile

        val surfaces = mutableListOf(previewSurface, recorder.surface)
        imageReader?.surface?.let { surfaces.add(it) }

        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface)
                        addTarget(recorder.surface)
                    }
                    session.setRepeatingRequest(request.build(), null, bgHandler)
                    recorder.start()
                    isRecording = true
                    Timber.i("Local recording started: ${outputFile.name}")
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
        startPreviewSession()
        Timber.i("Local recording stopped: ${currentRecordingFile?.name}")
        currentRecordingFile = null
    }

    /** 切换前置/后置摄像头 */
    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        Timber.i("切换摄像头: front=$useFrontCamera")
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
