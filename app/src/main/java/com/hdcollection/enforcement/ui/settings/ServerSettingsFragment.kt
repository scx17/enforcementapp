package com.hdcollection.enforcement.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.databinding.FragmentSettingsServerBinding

class ServerSettingsFragment : Fragment() {
    private var _binding: FragmentSettingsServerBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: AppSettings
    private var advancedExpanded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext().getSharedPreferences("app_settings", 0))

        // 加载已保存的值
        binding.etApiUrl.setText(settings.platformApiUrl)
        binding.etSipServer.setText(settings.sipServer)
        binding.etSipPort.setText(settings.sipPort)
        binding.etSipUsername.setText(settings.sipUsername)
        binding.etSipPassword.setText(settings.sipPassword)
        binding.etLogInterval.setText(settings.logUploadInterval.toString())

        // 高级设置折叠/展开
        binding.tvAdvancedToggle.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.layoutAdvanced.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            binding.tvAdvancedToggle.text = if (advancedExpanded) "\u25BC 高级设置（SIP 参数）" else "\u25B6 高级设置（SIP 参数）"
        }

        // 自动配置按钮
        binding.btnAutoConfig.setOnClickListener {
            val apiUrl = binding.etApiUrl.text.toString().trim().trimEnd('/')
            if (apiUrl.isEmpty()) {
                Toast.makeText(context, "请输入平台地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存平台地址
            settings.platformApiUrl = apiUrl

            // 禁用按钮防止重复点击
            binding.btnAutoConfig.isEnabled = false
            binding.btnAutoConfig.text = "配置中..."

            // 调用自动配置 API
            Thread {
                try {
                    val url = "$apiUrl/api/device/config"
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder().url(url).get().build()
                    val response = client.newCall(request).execute()
                    val json = response.body?.string() ?: ""
                    val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                    val success = root.get("success").asBoolean
                    if (!success) {
                        throw Exception("服务器返回失败")
                    }
                    val data = root.getAsJsonObject("data")

                    val sipServer = data.get("sipServer").asString
                    val sipPort = data.get("sipPort").asString
                    val sipPassword = data.get("sipPassword").asString
                    val deviceIdPrefix = data.get("deviceIdPrefix").asString

                    // 从设备序列号生成设备编码
                    @Suppress("DEPRECATION")
                    val serialNumber = android.os.Build.SERIAL.let {
                        if (it == "unknown" || it.isBlank()) android.os.Build.DEVICE else it
                    }
                    // 取序列号后7位数字，不足补0
                    val suffix = serialNumber.filter { it.isDigit() }.takeLast(7).padStart(7, '0')
                    val deviceId = deviceIdPrefix + suffix

                    // 保存所有配置
                    settings.sipServer = sipServer
                    settings.sipPort = sipPort
                    settings.sipUsername = deviceId
                    settings.sipPassword = sipPassword
                    settings.deviceId = deviceId

                    activity?.runOnUiThread {
                        // 更新 UI 显示
                        binding.etSipServer.setText(sipServer)
                        binding.etSipPort.setText(sipPort)
                        binding.etSipUsername.setText(deviceId)
                        binding.etSipPassword.setText(sipPassword)
                        binding.btnAutoConfig.isEnabled = true
                        binding.btnAutoConfig.text = "自动配置"
                        Toast.makeText(context, "自动配置成功！设备编码: $deviceId", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        binding.btnAutoConfig.isEnabled = true
                        binding.btnAutoConfig.text = "自动配置"
                        Toast.makeText(context, "自动配置失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        // 保存按钮
        binding.btnSave.setOnClickListener {
            settings.sipServer = binding.etSipServer.text.toString().trim()
            settings.sipPort = binding.etSipPort.text.toString().trim().ifEmpty { "5060" }
            settings.sipUsername = binding.etSipUsername.text.toString().trim()
            settings.sipPassword = binding.etSipPassword.text.toString()
            settings.platformApiUrl = binding.etApiUrl.text.toString().trim()
            settings.logUploadInterval = binding.etLogInterval.text.toString().toIntOrNull() ?: 60
            // 同步 deviceId 与 sipUsername
            val username = binding.etSipUsername.text.toString().trim()
            if (username.isNotEmpty()) {
                settings.deviceId = username
            }
            Toast.makeText(context, "服务器配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
