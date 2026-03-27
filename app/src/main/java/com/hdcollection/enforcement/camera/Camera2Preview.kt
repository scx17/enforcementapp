package com.hdcollection.enforcement.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
        val cameraId = manager.cameraIdList.firstOrNull() ?: return
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
            // 创建 MediaCodec H.264 编码器
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 25)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
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
                    Timber.i("Camera encoding started -> $rtpIp:$rtpPort")
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
        encoderThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isEncoding) {
                try {
                    val codec = mediaCodec ?: break
                    val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        val data = ByteArray(bufferInfo.size)
                        buffer.get(data)
                        codec.releaseOutputBuffer(index, false)

                        val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        rtpSender?.sendVideoFrame(data, bufferInfo.presentationTimeUs / 1000, isKeyFrame)
                        Timber.v("RTP frame sent: ${data.size} bytes, keyframe=$isKeyFrame")
                    }
                } catch (e: Exception) {
                    if (isEncoding) Timber.e(e, "Encoder output error")
                }
            }
        }.also { it.start() }
    }

    fun stopEncoding() {
        if (!isEncoding) return
        isEncoding = false
        encoderThread?.join(2000)
        encoderThread = null

        mediaCodec?.apply { stop(); release() }
        mediaCodec = null
        rtpSender?.stop()
        rtpSender = null

        // 恢复预览 session（不含编码器 Surface）
        startPreviewSession()
        Timber.i("Camera encoding stopped")
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

    private fun closeCamera() {
        stopEncoding()
        if (isRecording) stopLocalRecording()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
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
