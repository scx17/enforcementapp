plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.hdcollection.enforcement"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hdcollection.enforcement"
        minSdk = 25
        targetSdk = 36
        versionCode = 35
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 远程升级零现场配置兜底：卸载重装后 SharedPreferences 全清，App 启动时
        // 如果 platformApiUrl 为空就用这个默认值，能联网后通过 IMEI 拉规范配置。
        // 现场每台首次配置一次后，编译时常量被 settings 覆盖；以后再卸载重装也不用现场配。
        buildConfigField("String", "DEFAULT_PLATFORM_API_URL", "\"http://106.53.166.236\"")
    }

    // 远程升级链路要求新旧包签名一致。release 走本地 keystore（不进 git，
    // 配置在 ~/.gradle/gradle.properties 里），未提供属性时退化用 debug 签名
    // 让本地随手 ./gradlew assembleRelease 不会断。
    signingConfigs {
        create("release") {
            val ksPath = (project.findProperty("ENFORCEMENT_KEYSTORE_FILE") as? String).orEmpty()
            val ksPassword = (project.findProperty("ENFORCEMENT_KEYSTORE_PASSWORD") as? String).orEmpty()
            val keyAlias = (project.findProperty("ENFORCEMENT_KEY_ALIAS") as? String).orEmpty()
            val keyPassword = (project.findProperty("ENFORCEMENT_KEY_PASSWORD") as? String).orEmpty()
            if (ksPath.isNotEmpty() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = ksPassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 仅当 release keystore 配置完整时才用，否则回退 debug 签名（仅用于本地构建）
            val rc = signingConfigs.getByName("release")
            signingConfig = if (rc.storeFile != null) rc else signingConfigs.getByName("debug")
        }
    }

    // 输出 APK 命名：HdcEnforcement-v<versionName>-vc<versionCode>.apk（不含 debug/release 字样）
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName =
                "HdcEnforcement-v${variant.versionName}-vc${variant.versionCode}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // 执法仪厂商硬件 SDK
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.viewpager2)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Logging
    implementation(libs.timber)

    // SignalR
    implementation(libs.signalr)

    // WorkManager
    implementation(libs.workmanager)

    // SIP 对讲：使用内置 AudioRecord/AudioTrack + 扩展 GB28181 SIP 实现

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // CameraX + ML Kit 二维码扫描
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
}
