package com.hdcollection.enforcement.ui.settings

import android.os.Bundle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
    private val handler = Handler(Looper.getMainLooper())
    private var codeCheckRunnable: Runnable? = null
    private var codeAvailable = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext().getSharedPreferences("app_settings", 0))

        // 加载已保存的值
        binding.etApiUrl.setText(settings.platformApiUrl.ifEmpty { "http://192.168.5.110:5004" })
        binding.etCustomCode.setText(settings.customCode)
        binding.etSipServer.setText(settings.sipServer.ifEmpty { "" })
        binding.etSipPort.setText(settings.sipPort.ifEmpty { "5060" })
        binding.etSipUsername.setText(settings.sipUsername.ifEmpty { "" })
        binding.etSipPassword.setText(settings.sipPassword.ifEmpty { "admin123" })
        binding.etLogInterval.setText(settings.logUploadInterval.toString())

        // 设备编号输入校验（防抖 300ms）
        binding.etCustomCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                codeAvailable = false
                codeCheckRunnable?.let { handler.removeCallbacks(it) }
                val code = s?.toString()?.trim() ?: ""
                if (code.length < 3) {
                    binding.tvCodeStatus.text = if (code.isEmpty()) "" else "至少 3 位"
                    binding.tvCodeStatus.setTextColor(0xFF888888.toInt())
                    return
                }
                binding.tvCodeStatus.text = "校验中..."
                binding.tvCodeStatus.setTextColor(0xFF888888.toInt())
                codeCheckRunnable = Runnable { checkCode(code) }
                handler.postDelayed(codeCheckRunnable!!, 300)
            }
        })

        // 自动配置按钮：POST /api/device/config（提交 customCode + imei）
        binding.btnAutoConfig.setOnClickListener {
            val apiUrl = binding.etApiUrl.text.toString().trim().trimEnd('/')
            val customCode = binding.etCustomCode.text.toString().trim()
            if (apiUrl.isEmpty()) {
                Toast.makeText(context, "请输入平台地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (customCode.length < 3) {
                Toast.makeText(context, "请先输入设备编号（3-7 位）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!codeAvailable) {
                Toast.makeText(context, "设备编号不可用，请更换", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnAutoConfig.isEnabled = false
            binding.btnAutoConfig.text = "配置中..."

            Thread {
                try {
                    val imei = getImei()
                    val url = "$apiUrl/api/device/config"
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val body = okhttp3.RequestBody.create(
                        "application/json".toMediaTypeOrNull(),
                        """{"customCode":"$customCode","imei":"$imei"}"""
                    )
                    val request = okhttp3.Request.Builder().url(url).post(body).build()
                    val response = client.newCall(request).execute()
                    val json = response.body?.string() ?: ""
                    val root = com.google.gson.JsonParser.parseString(json).asJsonObject

                    if (!root.get("success").asBoolean) {
                        val reason = root.get("reason")?.asString ?: ""
                        if (reason == "already_used") {
                            val suggestion = root.get("suggestion")?.asString
                            throw Exception("编号已被占用" + if (suggestion != null) "，建议: $suggestion" else "")
                        }
                        throw Exception(root.get("message")?.asString ?: "服务器返回失败")
                    }

                    val data = root.getAsJsonObject("data")
                    val sipServer = data.get("sipServer").asString
                    val sipPort = data.get("sipPort").asString
                    val sipPassword = data.get("sipPassword").asString
                    val sipDeviceId = data.get("sipDeviceId").asString
                    val returnedCode = data.get("customCodeDisplay")?.asString ?: customCode

                    Timber.i("自动配置成功: SipDeviceId=$sipDeviceId, CustomCode=$returnedCode, IMEI=$imei")

                    // 保存配置
                    settings.platformApiUrl = apiUrl
                    settings.sipServer = sipServer
                    settings.sipPort = sipPort
                    settings.sipUsername = sipDeviceId
                    settings.sipPassword = sipPassword
                    settings.deviceId = sipDeviceId
                    settings.customCode = returnedCode
                    settings.customCodeUpdatedAt = System.currentTimeMillis()

                    activity?.runOnUiThread {
                        binding.etSipServer.setText(sipServer)
                        binding.etSipPort.setText(sipPort)
                        binding.etSipUsername.setText(sipDeviceId)
                        binding.etSipPassword.setText(sipPassword)
                        binding.btnAutoConfig.isEnabled = true
                        binding.btnAutoConfig.text = "从平台自动获取以下配置"
                        Toast.makeText(context, "配置成功！编号: $returnedCode", Toast.LENGTH_LONG).show()
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
            val customCode = binding.etCustomCode.text.toString().trim()
            if (customCode.length < 3) {
                Toast.makeText(context, "请输入设备编号（3-7 位）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settings.platformApiUrl = binding.etApiUrl.text.toString().trim()
            settings.sipServer = binding.etSipServer.text.toString().trim()
            settings.sipPort = binding.etSipPort.text.toString().trim().ifEmpty { "5060" }
            settings.sipUsername = binding.etSipUsername.text.toString().trim()
            settings.sipPassword = binding.etSipPassword.text.toString()
            settings.logUploadInterval = binding.etLogInterval.text.toString().toIntOrNull() ?: 60
            settings.customCode = customCode
            val username = binding.etSipUsername.text.toString().trim()
            if (username.isNotEmpty()) settings.deviceId = username
            Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkCode(code: String) {
        val apiUrl = binding.etApiUrl.text.toString().trim().trimEnd('/')
        if (apiUrl.isEmpty()) {
            binding.tvCodeStatus.text = "请先填平台地址"
            binding.tvCodeStatus.setTextColor(0xFFFF6B6B.toInt())
            return
        }
        Thread {
            try {
                val excludeDeviceId = settings.deviceId.ifEmpty { null }
                val body = okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    """{"code":"$code"${if (excludeDeviceId != null) ""","excludeDeviceId":"$excludeDeviceId"""" else ""}}"""
                )
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("$apiUrl/api/device/check-code")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: ""
                val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                val available = root.get("available")?.asBoolean ?: false
                val suggestion = root.get("suggestion")?.asString

                activity?.runOnUiThread {
                    if (available) {
                        binding.tvCodeStatus.text = "可用"
                        binding.tvCodeStatus.setTextColor(0xFF4CAF50.toInt())
                        codeAvailable = true
                    } else {
                        val reason = root.get("reason")?.asString
                        val msg = if (reason == "format_invalid") "格式不合法" else "已被占用"
                        binding.tvCodeStatus.text = msg + if (suggestion != null) "，建议: $suggestion" else ""
                        binding.tvCodeStatus.setTextColor(0xFFFF6B6B.toInt())
                        codeAvailable = false
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "设备编号校验失败")
                activity?.runOnUiThread {
                    binding.tvCodeStatus.text = "无法校验（网络异常）"
                    binding.tvCodeStatus.setTextColor(0xFFFF6B6B.toInt())
                    codeAvailable = false
                }
            }
        }.start()
    }

    private fun getImei(): String {
        return try {
            val dm = android.app.devicemanager.DeviceManager.getInstance()
            dm.imei?.takeIf { it.isNotBlank() } ?: "0000000000000000"
        } catch (e: Exception) {
            Timber.w(e, "DeviceManager.getImei() 不可用")
            "0000000000000000"
        }
    }

    override fun onDestroyView() {
        codeCheckRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
