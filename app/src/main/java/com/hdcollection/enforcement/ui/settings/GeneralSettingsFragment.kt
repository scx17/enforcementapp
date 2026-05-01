package com.hdcollection.enforcement.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hdcollection.enforcement.EnforcementApp
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.config.RemoteConfig
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.databinding.FragmentSettingsGeneralBinding
import kotlinx.coroutines.launch

class GeneralSettingsFragment : Fragment() {
    private var _binding: FragmentSettingsGeneralBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: AppSettings

    // 选项必须与平台「设备远程配置」DeviceConfigPage.vue 完全一致 —— 两端通过同一份
    // RemoteConfig（resolution = "1280x720" 长格式）保持双向同步。
    private val resolutionLabels = listOf("720P (1280x720)", "1080P (1920x1080)", "1440P (2560x1440)")
    private val resolutionValues = listOf("1280x720", "1920x1080", "2560x1440")
    private val fpsValues = listOf(15, 20, 25, 30)
    private val bitrateLabels = listOf(
        "512 kbps (流畅)",
        "1024 kbps (标清)",
        "2048 kbps (高清)",
        "3072 kbps (超清)",
        "4096 kbps (蓝光)",
        "6144 kbps (蓝光+)",
        "8192 kbps (原画)"
    )
    private val bitrateValues = listOf(512, 1024, 2048, 3072, 4096, 6144, 8192)
    private val segmentOptions = listOf("5 分钟", "10 分钟", "30 分钟")
    private val segmentValues = listOf(5, 10, 30)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsGeneralBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext().getSharedPreferences("app_settings", 0))
        val app = requireContext().applicationContext as EnforcementApp

        binding.spinnerResolution.adapter = makeAdapter(resolutionLabels)
        binding.spinnerFps.adapter = makeAdapter(fpsValues.map { "$it fps" })
        binding.spinnerBitrate.adapter = makeAdapter(bitrateLabels)
        binding.spinnerSegment.adapter = makeAdapter(segmentOptions)

        // 录像分段是本地设置（不在 RemoteConfig 里），只初始化一次
        binding.spinnerSegment.setSelection(
            segmentValues.indexOf(settings.recordingSegmentMinutes).coerceAtLeast(0)
        )

        // 监听 RemoteConfig StateFlow：平台推送 / 自身保存后 UI 自动刷新
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.remoteConfigManager.config.collect { cfg ->
                    applyConfigToUi(cfg)
                }
            }
        }

        binding.btnSaveGeneral.setOnClickListener {
            val resolution = resolutionValues[binding.spinnerResolution.selectedItemPosition]
            val fps = fpsValues[binding.spinnerFps.selectedItemPosition]
            val bitrate = bitrateValues[binding.spinnerBitrate.selectedItemPosition]
            settings.recordingSegmentMinutes = segmentValues[binding.spinnerSegment.selectedItemPosition]
            // 视频参数走 RemoteConfigManager —— 自动持久化 + 同步平台 + 触发编码器热重启
            app.remoteConfigManager.applyLocalVideoChange(resolution, fps, bitrate)
            Toast.makeText(context, "通用配置已保存（视频参数已同步到平台）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyConfigToUi(cfg: RemoteConfig) {
        val resIdx = resolutionValues.indexOf(cfg.videoResolution).coerceAtLeast(0)
        if (binding.spinnerResolution.selectedItemPosition != resIdx) {
            binding.spinnerResolution.setSelection(resIdx)
        }
        val fpsIdx = fpsValues.indexOf(cfg.videoFps).coerceAtLeast(0)
        if (binding.spinnerFps.selectedItemPosition != fpsIdx) {
            binding.spinnerFps.setSelection(fpsIdx)
        }
        val brIdx = bitrateValues.indexOf(cfg.videoBitrateKbps)
            .let { if (it < 0) bitrateValues.indexOf(2048) else it }
        if (binding.spinnerBitrate.selectedItemPosition != brIdx) {
            binding.spinnerBitrate.setSelection(brIdx)
        }
    }

    private fun makeAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(requireContext(), R.layout.item_spinner_dark, items)
        adapter.setDropDownViewResource(R.layout.item_spinner_dark_dropdown)
        return adapter
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
