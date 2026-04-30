package com.hdcollection.enforcement.upgrade

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import com.hdcollection.enforcement.data.AppSettings
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 升级流程总控：
 *   checkUpdate() — 调平台 /api/app/check-update
 *   downloadApk() — OkHttp + Range 断点续传，校验 SHA-256
 *   install() — 优先 PackageInstaller.Session 静默装(Device Owner)，否则系统弹窗
 *
 * UpgradeWorker 周期触发；MainActivity 启动时也调一次。
 */
object UpgradeManager {

    private const val UPGRADE_DIR = "upgrade"
    private const val DOWNLOAD_TIMEOUT_MIN = 10L
    // 下载分片大小，进度上报粒度 ~5%
    private const val PROGRESS_REPORT_PERCENT = 5

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT_MIN, TimeUnit.MINUTES)
            .build()
    }

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val size: Long,
        val sha256: String,
        val changelog: String?,
        val isForce: Boolean
    )

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        } catch (t: Throwable) {
            Timber.w(t, "Upgrade: 取 versionCode 失败")
            0
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (t: Throwable) { "" }
    }

    /** 调 /api/app/check-update。无更新返回 null。 */
    fun checkUpdate(context: Context, settings: AppSettings): UpdateInfo? {
        if (settings.platformApiUrl.isEmpty()) return null
        val currentVc = getCurrentVersionCode(context)
        val deviceId = settings.deviceId

        val url = "${settings.platformApiUrl.trimEnd('/')}/api/app/check-update" +
                "?currentVersionCode=$currentVc" +
                if (deviceId.isNotEmpty()) "&deviceId=$deviceId" else ""

        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("Upgrade: check-update HTTP ${resp.code}")
                    return null
                }
                val json = resp.body?.string() ?: return null
                val root = JsonParser.parseString(json).asJsonObject
                if (root.get("success")?.asBoolean != true) return null
                if (root.get("hasUpdate")?.asBoolean != true) return null
                UpdateInfo(
                    versionCode = root.get("versionCode").asInt,
                    versionName = root.get("versionName").asString,
                    downloadUrl = root.get("downloadUrl").asString,
                    size = root.get("size").asLong,
                    sha256 = root.get("sha256").asString,
                    changelog = root.get("changelog")?.takeIf { !it.isJsonNull }?.asString,
                    isForce = root.get("isForce")?.asBoolean ?: false
                )
            }
        } catch (t: Throwable) {
            Timber.w(t, "Upgrade: check-update 异常")
            null
        }
    }

    /**
     * 下载 APK 到 cacheDir/upgrade/，支持断点续传。
     * 返回本地文件 path；失败返回 null。
     */
    fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: ((percent: Int) -> Unit)? = null
    ): File? {
        val dir = File(context.cacheDir, UPGRADE_DIR).apply { mkdirs() }
        // 老版本下载残留清掉(versionCode 不同的 apk)
        dir.listFiles()?.forEach { f ->
            if (!f.name.contains("vc${info.versionCode}")) {
                runCatching { f.delete() }
            }
        }
        val target = File(dir, "HdcEnforcement-vc${info.versionCode}.apk")
        // 下载 URL 拼绝对地址(后端返回相对 /presigned 或绝对都可能)
        val absUrl = if (info.downloadUrl.startsWith("http")) info.downloadUrl
                     else "${getApiBase(context)}${info.downloadUrl}"

        return try {
            val existed = if (target.exists()) target.length() else 0L
            if (existed >= info.size && verifySha256(target, info.sha256)) {
                Timber.i("Upgrade: 已有完整 APK，跳过下载: ${target.name}")
                onProgress?.invoke(100)
                return target
            }
            val builder = Request.Builder().url(absUrl)
            if (existed > 0L && existed < info.size) {
                builder.header("Range", "bytes=$existed-")
                Timber.i("Upgrade: 续传从 ${existed}B 开始")
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    Timber.w("Upgrade: 下载 HTTP ${resp.code}")
                    return null
                }
                val body = resp.body ?: return null
                val append = existed > 0L && resp.code == 206
                FileOutputStream(target, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    val total = info.size
                    var written = if (append) existed else 0L
                    var lastReportedPercent = -1
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            val percent = ((written * 100) / total).toInt().coerceAtMost(100)
                            if (percent - lastReportedPercent >= PROGRESS_REPORT_PERCENT) {
                                lastReportedPercent = percent
                                onProgress?.invoke(percent)
                            }
                        }
                    }
                }
            }
            // SHA-256 校验
            if (!verifySha256(target, info.sha256)) {
                Timber.e("Upgrade: SHA-256 校验失败，删除本地文件")
                target.delete()
                return null
            }
            onProgress?.invoke(100)
            Timber.i("Upgrade: 下载完成 ${target.length()}B → ${target.absolutePath}")
            target
        } catch (t: Throwable) {
            Timber.e(t, "Upgrade: 下载异常")
            null
        }
    }

    private fun getApiBase(context: Context): String {
        val settings = AppSettings(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE))
        return settings.platformApiUrl.trimEnd('/')
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            Timber.e("Upgrade: SHA-256 不一致: expected=$expected actual=$actual")
            return false
        }
        return true
    }

    /**
     * 静默安装(Device Owner)或弹窗安装。返回是否触发成功（不代表实际装完）。
     */
    fun install(context: Context, apk: File): Boolean {
        if (isDeviceOwner(context)) {
            return installSilent(context, apk)
        }
        return installInteractive(context, apk)
    }

    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (t: Throwable) {
            Timber.w(t, "Upgrade: isDeviceOwnerApp 异常")
            false
        }
    }

    private fun installSilent(context: Context, apk: File): Boolean {
        Timber.i("Upgrade: Device Owner 静默装 ${apk.name}")
        val installer = context.packageManager.packageInstaller
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val pi = android.app.PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent("com.hdcollection.enforcement.UPGRADE_INSTALL_RESULT").setPackage(context.packageName),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                session.commit(pi.intentSender)
            }
            Timber.i("Upgrade: 静默装 commit 已发起")
            true
        } catch (t: Throwable) {
            Timber.e(t, "Upgrade: 静默装失败")
            false
        }
    }

    private fun installInteractive(context: Context, apk: File): Boolean {
        Timber.i("Upgrade: 系统弹窗装 ${apk.name}")
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Timber.e(t, "Upgrade: 弹窗装失败")
            false
        }
    }

    /** 上报升级流程事件到平台。失败静默。 */
    fun reportEvent(
        context: Context, settings: AppSettings,
        toVersionCode: Int, event: String, message: String? = null
    ) {
        if (settings.platformApiUrl.isEmpty()) return
        val deviceId = settings.deviceId
        if (deviceId.isEmpty()) return
        val fromVc = getCurrentVersionCode(context)

        val url = "${settings.platformApiUrl.trimEnd('/')}/api/app/upgrade-report"
        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("fromVersionCode", fromVc)
            put("toVersionCode", toVersionCode)
            put("event", event)
            if (message != null) put("message", message)
        }.toString()

        try {
            val body = json.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { /* ignore body */ }
        } catch (t: Throwable) {
            Timber.w(t, "Upgrade: reportEvent 失败 event=$event")
        }
    }
}
