package com.hdcollection.enforcement.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.databinding.FragmentSettingsServerBinding
import timber.log.Timber

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

        // 加载已保存的值，未保存则显示默认值
        binding.etApiUrl.setText(settings.platformApiUrl.ifEmpty { "http://192.168.5.110:5004" })
        binding.etSipServer.setText(settings.sipServer.ifEmpty { "" })
        binding.etSipPort.setText(settings.sipPort.ifEmpty { "5060" })
        binding.etSipUsername.setText(settings.sipUsername.ifEmpty { "" })
        binding.etSipPassword.setText(settings.sipPassword.ifEmpty { "admin123" })
        binding.etLogInterval.setText(settings.logUploadInterval.toString())

        // 自动配置按钮：从平台 API 获取 SIP 配置 + 自动生成设备编码
        binding.btnAutoConfig.setOnClickListener {
            val apiUrl = binding.etApiUrl.text.toString().trim().trimEnd('/')
            if (apiUrl.isEmpty()) {
                Toast.makeText(context, "请输入平台地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnAutoConfig.isEnabled = false
            binding.btnAutoConfig.text = "配置中..."

            Thread {
                try {
                    // 1. 调平台 API 获取 SIP 配置
                    val url = "$apiUrl/api/device/config"
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder().url(url).get().build()
                    val response = client.newCall(request).execute()
                    val json = response.body?.string() ?: ""
                    val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                    if (!root.get("success").asBoolean) throw Exception("服务器返回失败")
                    val data = root.getAsJsonObject("data")

                    val sipServer = data.get("sipServer").asString
                    val sipPort = data.get("sipPort").asString
                    val sipPassword = data.get("sipPassword").asString
                    val deviceIdPrefix = data.get("deviceIdPrefix").asString

                    // 2. 通过 DeviceManager.getImei() 获取设备 IMEI
                    val imei = try {
                        val dm = android.app.devicemanager.DeviceManager.getInstance()
                        dm.imei?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) {
                        Timber.w(e, "DeviceManager.getImei() 不可用")
                        null
                    }
                    // IMEI 通常 15 位数字，取后 7 位拼接设备编码前缀生成 20 位国标编码
                    val imeiStr = imei ?: "0000000000000000"
                    val suffix = imeiStr.filter { it.isDigit() }.takeLast(7).padStart(7, '0')
                    val deviceId = deviceIdPrefix + suffix
                    Timber.i("自动配置: IMEI=$imei, 设备编码=$deviceId")

                    // 3. 保存配置
                    settings.platformApiUrl = apiUrl
                    settings.sipServer = sipServer
                    settings.sipPort = sipPort
                    settings.sipUsername = deviceId
                    settings.sipPassword = sipPassword
                    settings.deviceId = deviceId

                    activity?.runOnUiThread {
                        binding.etApiUrl.setText(apiUrl)
                        binding.etSipServer.setText(sipServer)
                        binding.etSipPort.setText(sipPort)
                        binding.etSipUsername.setText(deviceId)
                        binding.etSipPassword.setText(sipPassword)
                        binding.btnAutoConfig.isEnabled = true
                        binding.btnAutoConfig.text = "从平台自动获取以下配置"
                        Toast.makeText(context, "自动配置成功！设备编码: $deviceId", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "自动配置失败")
                    activity?.runOnUiThread {
                        binding.btnAutoConfig.isEnabled = true
                        binding.btnAutoConfig.text = "从平台自动获取以下配置"
                        Toast.makeText(context, "自动配置失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        // 保存按钮
        binding.btnSave.setOnClickListener {
            settings.platformApiUrl = binding.etApiUrl.text.toString().trim()
            settings.sipServer = binding.etSipServer.text.toString().trim()
            settings.sipPort = binding.etSipPort.text.toString().trim().ifEmpty { "5060" }
            settings.sipUsername = binding.etSipUsername.text.toString().trim()
            settings.sipPassword = binding.etSipPassword.text.toString()
            settings.logUploadInterval = binding.etLogInterval.text.toString().toIntOrNull() ?: 60
            // 设备编码与 SIP 账号保持一致
            val username = binding.etSipUsername.text.toString().trim()
            if (username.isNotEmpty()) settings.deviceId = username
            Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
