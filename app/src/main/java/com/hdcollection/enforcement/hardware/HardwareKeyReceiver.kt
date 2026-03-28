package com.hdcollection.enforcement.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import timber.log.Timber

/**
 * 执法仪硬件按键广播接收器。
 * 设备通过系统广播发送按键事件，本类统一接收并分发给回调处理。
 */
class HardwareKeyReceiver : BroadcastReceiver() {

    var onKeyAction: ((KeyAction) -> Unit)? = null

    enum class KeyAction {
        SOS_PRESS, SOS_LONG_PRESS,
        VIDEO_PRESS, VIDEO_LONG_PRESS,
        RECORD_PRESS, RECORD_LONG_PRESS,
        PHOTO_PRESS, PHOTO_LONG_PRESS, PHOTO_LONG_PRESS_CANCELED,
        PTT_DOWN, PTT_UP,
        MARK_PRESS, MARK_LONG_PRESS,
        FN_PRESS, FN_LONG_PRESS,
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ACTION_SOS_PRESS -> KeyAction.SOS_PRESS
            ACTION_SOS_LONG_PRESS -> KeyAction.SOS_LONG_PRESS
            ACTION_VIDEO_PRESS -> KeyAction.VIDEO_PRESS
            ACTION_VIDEO_LONG_PRESS -> KeyAction.VIDEO_LONG_PRESS
            ACTION_RECORD_PRESS -> KeyAction.RECORD_PRESS
            ACTION_RECORD_LONG_PRESS -> KeyAction.RECORD_LONG_PRESS
            ACTION_PHOTO_PRESS -> KeyAction.PHOTO_PRESS
            ACTION_PHOTO_LONG_PRESS -> KeyAction.PHOTO_LONG_PRESS
            ACTION_PHOTO_LONG_PRESS_CANCELED -> KeyAction.PHOTO_LONG_PRESS_CANCELED
            ACTION_PTT_DOWN -> KeyAction.PTT_DOWN
            ACTION_PTT_UP -> KeyAction.PTT_UP
            ACTION_MARK_PRESS -> KeyAction.MARK_PRESS
            ACTION_MARK_LONG_PRESS -> KeyAction.MARK_LONG_PRESS
            ACTION_FN_PRESS -> KeyAction.FN_PRESS
            ACTION_FN_LONG_PRESS -> KeyAction.FN_LONG_PRESS
            else -> {
                Timber.d("HardwareKeyReceiver: unknown action ${intent.action}")
                return
            }
        }
        Timber.i("HardwareKeyReceiver: $action")
        onKeyAction?.invoke(action)
    }

    companion object {
        // SOS键
        private const val ACTION_SOS_PRESS = "android.intent.action.PRESS_SOS_KEY"
        private const val ACTION_SOS_LONG_PRESS = "android.intent.action.LONG_PRESS_SOS_KEY"
        // 录像键
        private const val ACTION_VIDEO_PRESS = "android.intent.action.PRESS_VIDEO_KEY"
        private const val ACTION_VIDEO_LONG_PRESS = "android.intent.action.LONG_PRESS_VIDEO_KEY"
        // 录音键
        private const val ACTION_RECORD_PRESS = "android.intent.action.PRESS_RECORD_KEY"
        private const val ACTION_RECORD_LONG_PRESS = "android.intent.action.LONG_PRESS_RECORD_KEY"
        // 拍照键
        private const val ACTION_PHOTO_PRESS = "android.intent.action.PRESS_PIC_KEY"
        private const val ACTION_PHOTO_LONG_PRESS = "android.intent.action.LONG_PRESS_PIC_KEY"
        private const val ACTION_PHOTO_LONG_PRESS_CANCELED = "android.intent.action.LONG_PRESS_PIC_KEY_CANCELED"
        // PTT键
        private const val ACTION_PTT_DOWN = "android.intent.action.DOWN_PTT_KEY"
        private const val ACTION_PTT_UP = "android.intent.action.UP_PTT_KEY"
        // Mark键
        private const val ACTION_MARK_PRESS = "android.intent.action.PRESS_MARK_KEY"
        private const val ACTION_MARK_LONG_PRESS = "android.intent.action.LONG_PRESS_MARK_KEY"
        // 多功能键
        private const val ACTION_FN_PRESS = "android.intent.action.PRESS_FN_KEY"
        private const val ACTION_FN_LONG_PRESS = "android.intent.action.LONG_PRESS_FN_KEY"

        fun createIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(ACTION_SOS_PRESS)
                addAction(ACTION_SOS_LONG_PRESS)
                addAction(ACTION_VIDEO_PRESS)
                addAction(ACTION_VIDEO_LONG_PRESS)
                addAction(ACTION_RECORD_PRESS)
                addAction(ACTION_RECORD_LONG_PRESS)
                addAction(ACTION_PHOTO_PRESS)
                addAction(ACTION_PHOTO_LONG_PRESS)
                addAction(ACTION_PHOTO_LONG_PRESS_CANCELED)
                addAction(ACTION_PTT_DOWN)
                addAction(ACTION_PTT_UP)
                addAction(ACTION_MARK_PRESS)
                addAction(ACTION_MARK_LONG_PRESS)
                addAction(ACTION_FN_PRESS)
                addAction(ACTION_FN_LONG_PRESS)
            }
        }
    }
}
