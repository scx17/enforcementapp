package com.hdcollection.enforcement.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.databinding.FragmentSettingsGeneralBinding

class GeneralSettingsFragment : Fragment() {
    private var _binding: FragmentSettingsGeneralBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: AppSettings
    private val resolutions = listOf("720P", "1080P", "1440P")
    private val segmentOptions = listOf("5 分钟", "10 分钟", "30 分钟")
    private val segmentValues = listOf(5, 10, 30)
    private val bitrateOptions = listOf(
        "512 kbps (流畅)",
        "1024 kbps (标清)",
        "2048 kbps (高清)",
        "3072 kbps (超清)",
        "4096 kbps (蓝光)",
        "6144 kbps (蓝光+)",
        "8192 kbps (原画)"
    )
    private val bitrateValues = listOf(512, 1024, 2048, 3072, 4096, 6144, 8192)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsGeneralBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext().getSharedPreferences("app_settings", 0))

        // 分辨率
        val resAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, resolutions)
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerResolution.adapter = resAdapter
        binding.spinnerResolution.setSelection(resolutions.indexOf(settings.videoResolution).coerceAtLeast(0))

        // 码率
        val bitrateAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, bitrateOptions)
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBitrate.adapter = bitrateAdapter
        val currentBitrate = settings.videoBitrate
        val bitrateIdx = bitrateValues.indexOf(currentBitrate)
            .let { if (it < 0) bitrateValues.indexOf(2048) else it }
        binding.spinnerBitrate.setSelection(bitrateIdx)

        // 录像片段时长
        val segAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, segmentOptions)
        segAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSegment.adapter = segAdapter
        val currentSeg = settings.recordingSegmentMinutes
        val segIdx = segmentValues.indexOf(currentSeg).coerceAtLeast(0)
        binding.spinnerSegment.setSelection(segIdx)

        binding.btnSaveGeneral.setOnClickListener {
            settings.videoResolution = resolutions[binding.spinnerResolution.selectedItemPosition]
            settings.videoBitrate = bitrateValues[binding.spinnerBitrate.selectedItemPosition]
            settings.recordingSegmentMinutes = segmentValues[binding.spinnerSegment.selectedItemPosition]
            Toast.makeText(context, "通用配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
