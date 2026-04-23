package com.hdcollection.enforcement.hardware

import android.os.Build
import android.view.KeyEvent
import timber.log.Timber

/**
 * 不同厂商执法仪按键识别方式不同：
 * - 标3 (DSJ-Z6): 厂商发 android.intent.action.PRESS_*_KEY 广播 → HardwareKeyReceiver 处理，此 profile 不拦截
 * - 老2 (BT280T): 物理键上报 Linux KEY_F13~F18 → Android KEYCODE_F13~F18，用 keyCode 识别
 * - 红点1 (SDJW-F2S): 物理键映射到厂商 FN_* keylayout，Android keyCode 多为 UNKNOWN(0)，用 scanCode 识别
 *
 * 非广播机型由 MainActivity.dispatchKeyEvent 拦截后，通过本对象的 [resolve] 翻译为逻辑功能，
 * 再由 MainActivity 自行做长按/短按状态机并路由到 handleHardwareKey()。
 */
object KeyProfile {

    enum class Logical { VIDEO, PHOTO, RECORD, SOS, PTT }

    private data class Mapping(
        val byKeyCode: Map<Int, Logical> = emptyMap(),
        val byScanCode: Map<Int, Logical> = emptyMap()
    )

    // 老2 BT280T: Linux F13-F18 (scancode 183-188) → Android KEYCODE_F13 ~ F18
    private val BT280T = Mapping(
        byKeyCode = mapOf(
            KeyEvent.KEYCODE_F13 to Logical.RECORD,
            KeyEvent.KEYCODE_F14 to Logical.VIDEO,
            KeyEvent.KEYCODE_F15 to Logical.PTT,
            KeyEvent.KEYCODE_F17 to Logical.PHOTO,
            KeyEvent.KEYCODE_F18 to Logical.SOS
        ),
        byScanCode = mapOf(
            183 to Logical.RECORD,
            184 to Logical.VIDEO,
            185 to Logical.PTT,
            187 to Logical.PHOTO,
            188 to Logical.SOS
        )
    )

    // 红点1 SDJW-F2S: FN_* keylayout 在 AOSP KeyEvent 中无对应常量，必须按 scanCode 识别
    private val SDJW_F2S = Mapping(
        byScanCode = mapOf(
            489 to Logical.SOS,
            490 to Logical.PHOTO,
            491 to Logical.VIDEO,
            493 to Logical.RECORD,
            494 to Logical.PTT
        )
    )

    private val mapping: Mapping? by lazy {
        val model = Build.MODEL ?: ""
        when {
            model.equals("BT280T", ignoreCase = true) -> {
                Timber.i("KeyProfile: model=$model, 使用 BT280T (F13-F18)")
                BT280T
            }
            model.equals("SDJW-F2S", ignoreCase = true) ||
                model.equals("SDJW_F2S", ignoreCase = true) -> {
                Timber.i("KeyProfile: model=$model, 使用 SDJW-F2S (scanCode 489-494)")
                SDJW_F2S
            }
            else -> {
                Timber.i("KeyProfile: model=$model, 走厂商广播通路（HardwareKeyReceiver）")
                null
            }
        }
    }

    /** 本机型是否需要 dispatchKeyEvent 拦截（true = 需要；false = 走原广播通路） */
    fun needsDispatchIntercept(): Boolean = mapping != null

    /** 翻译 KeyEvent 为逻辑功能；未命中返回 null，调用方应放行给 super */
    fun resolve(event: KeyEvent): Logical? {
        val m = mapping ?: return null
        m.byKeyCode[event.keyCode]?.let { return it }
        m.byScanCode[event.scanCode]?.let { return it }
        return null
    }
}
