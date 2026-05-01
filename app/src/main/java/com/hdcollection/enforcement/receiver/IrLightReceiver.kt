package com.hdcollection.enforcement.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hdcollection.enforcement.hardware.DeviceHardwareManager
import com.hdcollection.enforcement.hardware.LightState
import timber.log.Timber

/**
 * adb 远程控制夜视开关：
 *   adb shell am broadcast -a com.hdcollection.enforcement.IR_LIGHT --ez on true
 * 开启时同时打开 IR-CUT（移除红外滤镜让红外光进 sensor）。
 * 弱光下打开夜视能让摄像头维持稳定 25fps，避免暗光下自动降帧导致推流抖动。
 */
class IrLightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        DeviceHardwareManager.setIRLight(on)
        if (on) DeviceHardwareManager.setIRCut(true)
        LightState.irOn = on
        Timber.i("IR light remote control: on=$on, vendor SDK available=${DeviceHardwareManager.isAvailable()}")
    }
}
