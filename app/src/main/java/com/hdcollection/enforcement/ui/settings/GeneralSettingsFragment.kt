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
        binding.etBitrate.setText(settings.videoBitrate.toString())

        // 录像片段时长
        val segAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, segmentOptions)
        segAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSegment.adapter = segAdapter
        val currentSeg = settings.recordingSegmentMinutes
        val segIdx = segmentValues.indexOf(currentSeg).coerceAtLeast(0)
        binding.spinnerSegment.setSelection(segIdx)

        binding.btnSaveGeneral.setOnClickListener {
            settings.videoResolution = resolutions[binding.spinnerResolution.selectedItemPosition]
            settings.videoBitrate = binding.etBitrate.text.toString().toIntOrNull() ?: 2048
            settings.recordingSegmentMinutes = segmentValues[binding.spinnerSegment.selectedItemPosition]
            Toast.makeText(context, "通用配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
