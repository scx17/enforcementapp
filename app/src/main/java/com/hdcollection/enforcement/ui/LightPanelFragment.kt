package com.hdcollection.enforcement.ui

import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.hardware.DeviceHardwareManager
import com.hdcollection.enforcement.hardware.LightState
import timber.log.Timber

class LightPanelFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_light_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val hw = DeviceHardwareManager

        val switchFlash = view.findViewById<SwitchCompat>(R.id.switchFlash)
        val switchIR = view.findViewById<SwitchCompat>(R.id.switchIR)
        val switchLaser = view.findViewById<SwitchCompat>(R.id.switchLaser)
        val switchStrobe = view.findViewById<SwitchCompat>(R.id.switchStrobe)
        val switchRedStrobe = view.findViewById<SwitchCompat>(R.id.switchRedStrobe)
        val switchBlueStrobe = view.findViewById<SwitchCompat>(R.id.switchBlueStrobe)

        // 恢复状态（先设值再绑监听，避免触发回调）
        switchFlash.isChecked = LightState.flashOn
        switchIR.isChecked = LightState.irOn
        switchLaser.isChecked = LightState.laserOn
        switchStrobe.isChecked = LightState.strobeRedBlueOn
        switchRedStrobe.isChecked = LightState.strobeRedOn
        switchBlueStrobe.isChecked = LightState.strobeBlueOn

        // 闪光灯（白光灯）
        switchFlash.setOnCheckedChangeListener { _, isChecked ->
            LightState.flashOn = isChecked
            if (hw.isAvailable()) {
                hw.setFlashLight(isChecked)
            } else {
                setTorchMode(isChecked)
            }
            Timber.d("Flash: $isChecked")
        }

        // 红外灯
        switchIR.setOnCheckedChangeListener { _, isChecked ->
            LightState.irOn = isChecked
            hw.setIRLight(isChecked)
            if (isChecked) hw.setIRCut(true)
            Timber.d("IR light: $isChecked")
        }

        // 激光灯
        switchLaser.setOnCheckedChangeListener { _, isChecked ->
            LightState.laserOn = isChecked
            hw.setLaserLight(isChecked)
            Timber.d("Laser light: $isChecked")
        }

        // 红蓝爆闪灯
        switchStrobe.setOnCheckedChangeListener { _, isChecked ->
            LightState.strobeRedBlueOn = isChecked
            if (isChecked) hw.setStrobeRedBlueBlink() else hw.setStrobeRedBlueOff()
            Timber.d("Strobe red-blue: $isChecked")
        }

        // 红色警示灯
        switchRedStrobe.setOnCheckedChangeListener { _, isChecked ->
            LightState.strobeRedOn = isChecked
            if (isChecked) hw.setStrobeRedBlink() else hw.setStrobeRedOff()
            Timber.d("Strobe red: $isChecked")
        }

        // 蓝色警示灯
        switchBlueStrobe.setOnCheckedChangeListener { _, isChecked ->
            LightState.strobeBlueOn = isChecked
            if (isChecked) hw.setStrobeBlueBlink() else hw.setStrobeBlueOff()
            Timber.d("Strobe blue: $isChecked")
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
}
