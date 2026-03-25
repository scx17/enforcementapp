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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsGeneralBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext().getSharedPreferences("app_settings", 0))
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, resolutions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerResolution.adapter = adapter
        binding.spinnerResolution.setSelection(resolutions.indexOf(settings.videoResolution).coerceAtLeast(0))
        binding.etBitrate.setText(settings.videoBitrate.toString())

        binding.btnSaveGeneral.setOnClickListener {
            settings.videoResolution = resolutions[binding.spinnerResolution.selectedItemPosition]
            settings.videoBitrate = binding.etBitrate.text.toString().toIntOrNull() ?: 2048
            Toast.makeText(context, "通用配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
