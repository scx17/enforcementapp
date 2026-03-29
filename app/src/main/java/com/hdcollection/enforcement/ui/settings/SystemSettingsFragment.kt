package com.hdcollection.enforcement.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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

        // 系统设置快捷入口
        binding.btnWifi.setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        binding.btnBluetooth.setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        binding.btnDisplay.setOnClickListener { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        binding.btnSound.setOnClickListener { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
        binding.btnDateTime.setOnClickListener { startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) }
        binding.btnLocation.setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        binding.btnAbout.setOnClickListener { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        binding.btnAllSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
