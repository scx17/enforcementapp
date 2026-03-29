package com.hdcollection.enforcement.hardware

import timber.log.Timber

/**
 * 执法仪厂商硬件 SDK 封装，通过反射调用 DeviceManager，
 * 在非执法仪设备上自动降级为无操作。
 */
object DeviceHardwareManager {

    private val deviceManager: Any?
    private val dmClass: Class<*>?
    private var available = false

    init {
        var dm: Any? = null
        var clz: Class<*>? = null
        try {
            clz = Class.forName("android.app.devicemanager.DeviceManager")
            val getInstance = clz.getMethod("getInstance")
            dm = getInstance.invoke(null)
            available = dm != null
            Timber.i("DeviceHardwareManager: SDK available=$available")
        } catch (e: Exception) {
            Timber.w("DeviceHardwareManager: vendor SDK not available, running in fallback mode")
        }
        deviceManager = dm
        dmClass = clz
    }

    fun isAvailable(): Boolean = available

    // ---- 闪光灯 (白光灯) ----
    fun setFlashLight(on: Boolean) {
        invoke("openFlash", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(on))
    }

    fun getFlashLightStatus(): Boolean {
        return invokeReturn("getFlashLightStatus", Boolean::class.javaPrimitiveType!!) as? Boolean ?: false
    }

    // ---- 红外灯 ----
    fun setIRLight(on: Boolean) {
        invoke("irLedEnable", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(on))
    }

    // ---- IR-CUT ----
    fun setIRCut(on: Boolean) {
        invoke("irCutEnable", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(on))
    }

    // ---- 激光灯 ----
    fun setLaserLight(on: Boolean) {
        invoke("laserLightEnabled", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(on))
    }

    fun isLaserLightEnabled(): Boolean {
        return invokeReturn("isLaserLightEnabled", Boolean::class.javaPrimitiveType!!) as? Boolean ?: false
    }

    // ---- 红蓝爆闪灯 ----
    fun setStrobeLightStatus(color: Int, isBlink: Boolean, isOpen: Boolean) {
        invoke(
            "setStrobeLightStatus",
            arrayOf(Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            arrayOf(color, isBlink, isOpen)
        )
    }

    fun setStrobeRedOn() = setStrobeLightStatus(0x00FF0000.toInt(), false, true)
    fun setStrobeRedOff() = setStrobeLightStatus(0x00FF0000.toInt(), false, false)
    fun setStrobeRedBlink() = setStrobeLightStatus(0x00FF0000.toInt(), true, false)

    fun setStrobeBlueOn() = setStrobeLightStatus(0x000000FF, false, true)
    fun setStrobeBlueOff() = setStrobeLightStatus(0x000000FF, false, false)
    fun setStrobeBlueBlink() = setStrobeLightStatus(0x000000FF, true, false)

    fun setStrobeRedBlueBlink() = setStrobeLightStatus(0x00FF00FF, true, false)
    fun setStrobeRedBlueOff() = setStrobeLightStatus(0x00FF00FF, false, false)

    // ---- 旧版爆闪灯（简单开关） ----
    fun setStrobeLightSimple(on: Boolean) {
        invoke("strobeLightEnable", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(on))
    }

    // ---- 三色指示灯 ----
    fun setIndicatorFlash(color: Int, onMs: Int, offMs: Int) {
        invoke(
            "setFlash",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(color, onMs, offMs)
        )
    }

    fun setIndicatorOff() = setIndicatorFlash(0xFF000000.toInt(), 0, 1)

    // ---- USB 磁盘模式 ----
    fun setUsbDisk(on: Boolean): Boolean {
        return invokeWithReturn("setUsbDisk", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(on)) as? Boolean ?: false
    }

    fun isUsbDiskOpen(): Boolean {
        return invokeReturn("isUsbDiskOpen", Boolean::class.javaPrimitiveType!!) as? Boolean ?: false
    }

    // ---- 设备信息 ----
    fun getSn(): String {
        return invokeReturn("getSn", String::class.java) as? String ?: ""
    }

    fun getImei(): String {
        return invokeReturn("getImei", String::class.java) as? String ?: ""
    }

    // ---- 反射调用辅助 ----
    private fun invoke(methodName: String, paramTypes: Array<Class<*>>, args: Array<Any>) {
        if (!available || dmClass == null || deviceManager == null) {
            Timber.d("DeviceHardwareManager.$methodName: SDK not available, skipped")
            return
        }
        try {
            val method = dmClass.getMethod(methodName, *paramTypes)
            method.invoke(deviceManager, *args)
            Timber.d("DeviceHardwareManager.$methodName(${args.joinToString()}) OK")
        } catch (e: Exception) {
            Timber.w(e, "DeviceHardwareManager.$methodName failed")
        }
    }

    private fun invokeReturn(methodName: String, returnType: Class<*>): Any? {
        if (!available || dmClass == null || deviceManager == null) return null
        return try {
            val method = dmClass.getMethod(methodName)
            method.invoke(deviceManager)
        } catch (e: Exception) {
            Timber.w(e, "DeviceHardwareManager.$methodName failed")
            null
        }
    }

    private fun invokeWithReturn(methodName: String, paramTypes: Array<Class<*>>, args: Array<Any>): Any? {
        if (!available || dmClass == null || deviceManager == null) return null
        return try {
            val method = dmClass.getMethod(methodName, *paramTypes)
            method.invoke(deviceManager, *args)
        } catch (e: Exception) {
            Timber.w(e, "DeviceHardwareManager.$methodName failed")
            null
        }
    }
}
