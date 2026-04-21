package com.hdcollection.enforcement.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 二维码扫描 Activity：扫描平台端生成的服务器配置二维码，将 JSON 原文返回给调用方。
 *
 * 返回 Intent 中携带 `EXTRA_QR_DATA` 字段（String），内容为二维码 JSON 原文。
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_DATA = "qr_data"
        private const val REQUEST_CAMERA = 1001
    }

    private lateinit var cameraExecutor: ExecutorService
    private var scanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏预览
        val previewView = androidx.camera.view.PreviewView(this)
        setContentView(previewView)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera(previewView)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val previewView = findViewById<androidx.camera.view.PreviewView>(android.R.id.content)
            startCamera(previewView.getChildAt(0) as androidx.camera.view.PreviewView)
        } else {
            Toast.makeText(this, "需要摄像头权限才能扫码", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startCamera(previewView: androidx.camera.view.PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // 预览
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // 条码分析
                val barcodeScanner = BarcodeScanning.getClient()
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(barcodeScanner, imageProxy)
                        }
                    }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, analyzer)

                Timber.i("QrScanActivity: 摄像头启动成功")
            } catch (e: Exception) {
                Timber.e(e, "QrScanActivity: 摄像头启动失败")
                Toast.makeText(this, "摄像头启动失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (!scanned) {
                    for (barcode in barcodes) {
                        if (barcode.format == Barcode.FORMAT_QR_CODE) {
                            val raw = barcode.rawValue
                            if (raw != null && raw.contains("hdc_server")) {
                                scanned = true
                                Timber.i("QrScanActivity: 扫描到服务器配置二维码")
                                val resultIntent = Intent().apply {
                                    putExtra(EXTRA_QR_DATA, raw)
                                }
                                setResult(RESULT_OK, resultIntent)
                                finish()
                                return@addOnSuccessListener
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Timber.w(e, "QrScanActivity: 条码识别失败")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
