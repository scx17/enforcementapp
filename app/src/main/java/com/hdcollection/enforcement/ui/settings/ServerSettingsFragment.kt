package com.hdcollection.enforcement.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.databinding.FragmentSettingsServerBinding

class ServerSettingsFragment : Fragment() {
    private var _binding: FragmentSettingsServerBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: AppSettings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext().getSharedPreferences("app_settings", 0))
        binding.etSipServer.setText(settings.sipServer)
        binding.etSipPort.setText(settings.sipPort)
        binding.etSipUsername.setText(settings.sipUsername)
        binding.etSipPassword.setText(settings.sipPassword)
        binding.etApiUrl.setText(settings.platformApiUrl)
        binding.etLogInterval.setText(settings.logUploadInterval.toString())

        binding.btnSave.setOnClickListener {
            settings.sipServer = binding.etSipServer.text.toString().trim()
            settings.sipPort = binding.etSipPort.text.toString().trim().ifEmpty { "5060" }
            settings.sipUsername = binding.etSipUsername.text.toString().trim()
            settings.sipPassword = binding.etSipPassword.text.toString()
            settings.platformApiUrl = binding.etApiUrl.text.toString().trim()
            settings.logUploadInterval = binding.etLogInterval.text.toString().toIntOrNull() ?: 60
            Toast.makeText(context, "服务器配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
