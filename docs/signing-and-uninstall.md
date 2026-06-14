# EnforcementApp Release 签名 & 卸载指南

## Release 签名

### Keystore 信息

| 项目 | 值 |
|------|-----|
| 文件路径 | `~/.android/enforcement-release.jks` |
| Key Alias | `enforcement` |
| 密码 | 见 `~/.gradle/gradle.properties` 中 `ENFORCEMENT_*` 变量 |
| 证书 DN | `CN=HdCollection Enforcement, O=HdCollection, L=Shenzhen, C=CN` |

### 配置方式

密码存储在 `~/.gradle/gradle.properties`（不入 git），由 `app/build.gradle.kts` 的 `signingConfigs.release` 读取。

`~/.gradle/gradle.properties` 内容：

```properties
ENFORCEMENT_KEYSTORE_FILE=/home/scx17/.android/enforcement-release.jks
ENFORCEMENT_KEYSTORE_PASSWORD=<密码>
ENFORCEMENT_KEY_ALIAS=enforcement
ENFORCEMENT_KEY_PASSWORD=<密码>
```

> **注意**：新开发机部署时需要从旧机拷贝 `~/.android/enforcement-release.jks` 和 `~/.gradle/gradle.properties` 中的 `ENFORCEMENT_*` 行。缺失时构建不会报错但会回退 debug 签名，无法覆盖安装已用 release 签名的设备。

### 构建 Release APK

```bash
cd EnforcementApp
./gradlew assembleRelease --no-daemon
# 输出: app/build/outputs/apk/release/HdcEnforcement-v<版本>-vc<版本号>.apk
```

---

## 完全卸载执法通（Device Owner 设备）

执法通注册了 **Device Owner**，常规 `adb uninstall`、`pm clear`、`pm disable` 全部被系统拦截。
必须先用 Release 签名的 APK 覆盖安装，通过内置广播释放 Device Owner，再卸载。

### 前提

- 有与设备上已安装 APK 签名一致的 keystore（`enforcement-release.jks`）
- 如果丢失 keystore，**只能恢复出厂设置**（见下方）

### 步骤

```bash
# 1. 构建 release APK（必须 release 签名）
cd EnforcementApp
./gradlew assembleRelease --no-daemon

# 2. 覆盖安装到目标设备
adb -s <设备序列号> install -r app/build/outputs/apk/release/HdcEnforcement-*.apk

# 3. 发送广播释放 Device Owner（必须显式组件名，隐式广播 Android 14+ 不投递）
adb -s <设备序列号> shell "am broadcast -n com.hdcollection.enforcement/.upgrade.ReleaseDeviceOwnerReceiver -a com.hdcollection.enforcement.RELEASE_DEVICE_OWNER"

# 4. 验证 Device Owner 已释放（应无输出）
adb -s <设备序列号> shell dumpsys device_policy | grep -A1 "Device Owner"

# 5. 卸载
adb -s <设备序列号> uninstall com.hdcollection.enforcement
```

### 恢复出厂设置（keystore 丢失时的最后手段）

```bash
adb -s <设备序列号> shell "am broadcast -a android.intent.action.MASTER_CLEAR"
```

> **不可逆**，设备所有数据会被清除。

---

## 相关代码

| 文件 | 说明 |
|------|------|
| `app/src/main/java/.../upgrade/AppDeviceAdminReceiver.kt` | DeviceAdminReceiver + ReleaseDeviceOwnerReceiver |
| `app/src/main/AndroidManifest.xml` | ReleaseDeviceOwnerReceiver 注册 |
| `app/build.gradle.kts` | signingConfigs.release 配置 |
