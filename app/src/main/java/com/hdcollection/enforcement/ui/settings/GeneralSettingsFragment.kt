package com.hdcollection.enforcement.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.hdcollection.enforcement.EnforcementApp
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.databinding.FragmentSettingsGeneralBinding

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
        val cfg = app.remoteConfigManager.config.value

        binding.spinnerResolution.adapter = makeAdapter(resolutionLabels)
        binding.spinnerResolution.setSelection(resolutionValues.indexOf(cfg.videoResolution).coerceAtLeast(0))

        binding.spinnerFps.adapter = makeAdapter(fpsValues.map { "$it fps" })
        binding.spinnerFps.setSelection(fpsValues.indexOf(cfg.videoFps).coerceAtLeast(0))

        binding.spinnerBitrate.adapter = makeAdapter(bitrateLabels)
        binding.spinnerBitrate.setSelection(
            bitrateValues.indexOf(cfg.videoBitrateKbps).let { if (it < 0) bitrateValues.indexOf(2048) else it }
        )

        binding.spinnerSegment.adapter = makeAdapter(segmentOptions)
        binding.spinnerSegment.setSelection(
            segmentValues.indexOf(settings.recordingSegmentMinutes).coerceAtLeast(0)
        )

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

    private fun makeAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(requireContext(), R.layout.item_spinner_dark, items)
        adapter.setDropDownViewResource(R.layout.item_spinner_dark_dropdown)
        return adapter
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
