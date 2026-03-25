package com.hdcollection.enforcement.ui

import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hdcollection.enforcement.R
import timber.log.Timber

class LightPanelFragment : BottomSheetDialogFragment() {

    private var strobeThread: Thread? = null
    private var strobeRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_light_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switchFlash = view.findViewById<SwitchCompat>(R.id.switchFlash)
        val switchStrobe = view.findViewById<SwitchCompat>(R.id.switchStrobe)

        // 闪光灯：使用 Camera2 FLASH_MODE_TORCH
        switchFlash.setOnCheckedChangeListener { _, isChecked ->
            setTorchMode(isChecked)
            Timber.d("Flash: $isChecked")
        }

        // 红外灯：执法仪厂商 SDK 控制（此处通过系统广播占位）
        view.findViewById<SwitchCompat>(R.id.switchIR).setOnCheckedChangeListener { _, isChecked ->
            sendLightCommand("ir", isChecked)
            Timber.d("IR light: $isChecked")
        }

        // 激光灯：执法仪厂商 SDK 控制
        view.findViewById<SwitchCompat>(R.id.switchLaser).setOnCheckedChangeListener { _, isChecked ->
            sendLightCommand("laser", isChecked)
            Timber.d("Laser light: $isChecked")
        }

        // 爆闪灯：快速闪烁 torch
        switchStrobe.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startStrobe() else stopStrobe()
            Timber.d("Strobe: $isChecked")
        }
    }

    private fun setTorchMode(on: Boolean) {
        val ctx = context ?: return
        try {
            val cameraManager = ctx.getSystemService(CameraManager::class.java)
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, on)
        } catch (e: Exception) {
            Timber.e(e, "setTorchMode failed")
        }
    }

    private fun startStrobe() {
        strobeRunning = true
        strobeThread = Thread {
            while (strobeRunning) {
                setTorchMode(true)
                Thread.sleep(100)
                setTorchMode(false)
                Thread.sleep(100)
            }
        }.also { it.start() }
    }

    private fun stopStrobe() {
        strobeRunning = false
        strobeThread?.join(500)
        strobeThread = null
        setTorchMode(false)
    }

    private fun sendLightCommand(type: String, on: Boolean) {
        // 执法仪厂商 SDK 集成点
        // 实际通过 Intent 或 JNI 调用厂商 SDK
        Timber.d("Light command: type=$type on=$on (vendor SDK integration point)")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStrobe()
    }
}
