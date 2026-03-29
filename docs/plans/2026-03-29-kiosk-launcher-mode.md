# Kiosk Launcher Mode Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将执法仪 App 设为设备默认 Launcher，禁止退出，并在 App 内提供系统设置快捷入口。

**Architecture:** Manifest 添加 HOME category 使 App 成为可选 Launcher；MainActivity 拦截 Recent Apps 键并全屏沉浸式隐藏导航栏；开机广播自动启动 App；系统设置页新增 WiFi/蓝牙/音量/亮度/时间 等 Android 系统面板的快捷入口。

**Tech Stack:** Android SDK (Intent, WindowInsetsController, BroadcastReceiver, Settings.ACTION_*)

---

### Task 1: Manifest 添加 HOME category + 开机自启广播

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/hdcollection/enforcement/receiver/BootReceiver.kt`

**Step 1: 修改 Manifest**

在 MainActivity 的 intent-filter 中添加 HOME 和 DEFAULT category，注册 BootReceiver：

```xml
<activity
    android:name=".ui.main.MainActivity"
    android:exported="true"
    android:screenOrientation="portrait"
    android:keepScreenOn="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>

<!-- 在 </application> 前添加 -->
<receiver
    android:name=".receiver.BootReceiver"
    android:exported="true"
    android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

**Step 2: 创建 BootReceiver**

```kotlin
package com.hdcollection.enforcement.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hdcollection.enforcement.ui.main.MainActivity
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Timber.i("开机自启动: launching MainActivity")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
```

**Step 3: 编译验证**

```bash
cd /home/scx17/WorkSpace/wang/EnforcementApp && ./gradlew assembleDebug
```

**Step 4: 提交**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/hdcollection/enforcement/receiver/BootReceiver.kt
git commit -m "feat: App 注册为 Launcher + 开机自启广播"
```

---

### Task 2: MainActivity 全屏沉浸 + 拦截 Recent Apps 键

**Files:**
- Modify: `app/src/main/java/com/hdcollection/enforcement/ui/main/MainActivity.kt`

**Step 1: onCreate 中隐藏系统导航栏和状态栏**

在 `window.addFlags(FLAG_KEEP_SCREEN_ON)` 后添加：

```kotlin
// 全屏沉浸模式：隐藏导航栏和状态栏
window.decorView.systemUiVisibility = (
    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    or View.SYSTEM_UI_FLAG_FULLSCREEN
    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
)
```

**Step 2: onWindowFocusChanged 中恢复沉浸（防止系统弹窗后导航栏残留）**

```kotlin
override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }
}
```

**Step 3: onPause 中立即拉回前台（防止 Recent Apps 切走）**

在 onPause 中，延迟 200ms 重新拉起 MainActivity：

```kotlin
override fun onPause() {
    super.onPause()
    clockHandler.removeCallbacks(clockRunnable)
    // 如果被切走，立即拉回前台（拦截 Recent Apps）
    Handler(Looper.getMainLooper()).postDelayed({
        if (!isFinishing) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }, 200)
}
```

**Step 4: 编译验证并提交**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/hdcollection/enforcement/ui/main/MainActivity.kt
git commit -m "feat: 全屏沉浸模式 + 拦截 Recent Apps"
```

---

### Task 3: 系统设置页添加 Android 系统快捷入口

**Files:**
- Modify: `app/src/main/res/layout/fragment_settings_system.xml`
- Modify: `app/src/main/java/com/hdcollection/enforcement/ui/settings/SystemSettingsFragment.kt`

**Step 1: 修改布局 - 在保存按钮后添加系统设置快捷入口**

在 `btnSaveSystem` 按钮之后，`</LinearLayout>` 之前添加：

```xml
<!-- 分隔线 -->
<View android:layout_width="match_parent" android:layout_height="1dp"
    android:background="#333355" android:layout_marginTop="24dp" android:layout_marginBottom="16dp" />

<TextView android:text="系统设置" android:textColor="#FFFFFF" android:textSize="16sp"
    android:textStyle="bold" android:layout_width="wrap_content" android:layout_height="wrap_content"
    android:layout_marginBottom="12dp" />

<Button android:id="@+id/btnWifi" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:text="Wi-Fi 设置" android:backgroundTint="#2A2A3E" android:textColor="#FFFFFF"
    android:layout_marginBottom="8dp" />

<Button android:id="@+id/btnBluetooth" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:text="蓝牙设置" android:backgroundTint="#2A2A3E" android:textColor="#FFFFFF"
    android:layout_marginBottom="8dp" />

<Button android:id="@+id/btnDisplay" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:text="显示 / 亮度" android:backgroundTint="#2A2A3E" android:textColor="#FFFFFF"
    android:layout_marginBottom="8dp" />

<Button android:id="@+id/btnSound" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:text="声音 / 音量" android:backgroundTint="#2A2A3E" android:textColor="#FFFFFF"
    android:layout_marginBottom="8dp" />

<Button android:id="@+id/btnDateTime" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:text="日期和时间" android:backgroundTint="#2A2A3E" android:textColor="#FFFFFF"
    android:layout_marginBottom="8dp" />

<Button android:id="@+id/btnLocation" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:text="位置服务" android:backgroundTint="#2A2A3E" android:textColor="#FFFFFF"
    android:layout_marginBottom="8dp" />

<Button android:id="@+id/btnAbout" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:text="关于设备" android:backgroundTint="#2A2A3E" android:textColor="#FFFFFF"
    android:layout_marginBottom="8dp" />

<Button android:id="@+id/btnAllSettings" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:text="全部系统设置" android:backgroundTint="#1A237E" android:textColor="#FFFFFF"
    android:layout_marginBottom="8dp" />
```

**Step 2: 修改 Fragment - 绑定按钮点击打开系统设置面板**

在 `onViewCreated` 的 `btnSaveSystem` 点击事件之后添加：

```kotlin
import android.content.Intent
import android.provider.Settings

// 系统设置快捷入口
binding.btnWifi.setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
binding.btnBluetooth.setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
binding.btnDisplay.setOnClickListener { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
binding.btnSound.setOnClickListener { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
binding.btnDateTime.setOnClickListener { startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) }
binding.btnLocation.setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
binding.btnAbout.setOnClickListener { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
binding.btnAllSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
```

**Step 3: 编译验证并提交**

```bash
./gradlew assembleDebug
git add app/src/main/res/layout/fragment_settings_system.xml app/src/main/java/com/hdcollection/enforcement/ui/settings/SystemSettingsFragment.kt
git commit -m "feat: 系统设置页添加 WiFi/蓝牙/音量/亮度/时间等快捷入口"
```

---

### Task 4: 安装测试

**Step 1: 安装 APK 到设备**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Step 2: 验证 Launcher 选择器**

按 Home 键时系统应弹出"选择主屏幕应用"对话框，选择 EnforcementApp + "始终"。

**Step 3: 验证功能**

- Home 键 → 回到 App（不回桌面）
- Back 键 → 无反应（已有拦截）
- Recent Apps 键 → 被拉回 App
- 进入 设置 > 系统 Tab → 可看到 WiFi/蓝牙等快捷入口
- 点击各快捷入口 → 打开对应系统设置面板
- 从系统设置返回 → 回到 App

**Step 4: 重启设备验证开机自启**

```bash
adb reboot
```
设备重启后应自动进入 App。
