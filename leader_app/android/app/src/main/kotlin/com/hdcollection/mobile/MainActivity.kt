package com.hdcollection.mobile

import android.content.Context
import android.media.AudioManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "hdc/audio"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                when (call.method) {
                    // 1对1 对讲：强制扬声器外放(VoIP 模式)，否则全双工录+放会走听筒/不出声
                    "speakerphone" -> {
                        val on = call.argument<Boolean>("on") ?: true
                        am.mode = if (on) AudioManager.MODE_IN_COMMUNICATION
                        else AudioManager.MODE_NORMAL
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = on
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
