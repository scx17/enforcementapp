package com.hdcollection.enforcement.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.databinding.FragmentSettingsSystemBinding

class SystemSettingsFragment : Fragment() {
    private var _binding: FragmentSettingsSystemBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: AppSettings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsSystemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext().getSharedPreferences("app_settings", 0))
        binding.etDeviceId.setText(settings.deviceId)
        if (settings.networkPreference == "4g") binding.rb4g.isChecked = true
        else binding.rbWifi.isChecked = true

        binding.btnSaveSystem.setOnClickListener {
            settings.deviceId = binding.etDeviceId.text.toString().trim()
            settings.networkPreference = if (binding.rb4g.isChecked) "4g" else "wifi"
            Toast.makeText(context, "系统配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
