package com.hdcollection.enforcement.upgrade

import com.hdcollection.enforcement.sip.SipManager
import timber.log.Timber

/**
 * 全局忙碌状态管理。UpgradeWorker 在执行非强制升级前检查；忙则跳过当前周期，
 * 等下个 1 小时再来。强制升级无视忙状态，必装。
 *
 * 状态来源：
 *   - recording：MainActivity 的本地录像 toggle 同步进来
 *   - audioRecording：纯音频录制（与录像互斥，但在升级层都算"忙"）
 *   - SipManager.state：对讲中（INCOMING / IN_CALL）
 */
object AppBusyState {

    @Volatile var recording: Boolean = false

    /** 由调用方注入 SipManager 单例（在 EnforcementApp.onCreate 后即可用）。 */
    @Volatile var sipManagerProvider: () -> SipManager? = { null }

    fun isBusy(): Boolean {
        if (recording) return true
        val sip = sipManagerProvider() ?: return false
        return sip.state != SipManager.State.IDLE
    }

    fun describe(): String {
        val parts = mutableListOf<String>()
        if (recording) parts += "recording"
        val sipState = sipManagerProvider()?.state
        if (sipState != null && sipState != SipManager.State.IDLE) {
            parts += "sip=$sipState"
        }
        return if (parts.isEmpty()) "idle" else parts.joinToString(",")
    }
}
