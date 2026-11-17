import java.io.BufferedReader

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lizongying.mytv0"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lizongying.mytv0"
        minSdk = 21
        targetSdk = 35
        // 本机没有 git，无法从 tag 取版本号；固定成一个高于上游 1.3.9.8 的版本，
        // 保证能覆盖安装（降级安装会被 pm 拒绝）
        versionCode = 2 * 16777216 + 2
        versionName = "2.0.2-lite"

        // ffmpeg 的 aar 里四种架构的 so 都齐（arm64-v8a / armeabi-v7a / x86 / x86_64）。
        // 只打 v7a 会让纯 64 位电视装不上，现在同时带上 arm64-v8a：
        // 32 位老盒子走 v7a，64 位新电视走 v8a，代价是包大约多 1.1 MB。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        // 中文资源在 values-zh-rCN，只写 "zh" 会把它过滤掉、界面回退成英文
        resourceConfigurations += listOf("zh", "zh-rCN")
    }

    // 上游 release 不配签名，本地自签一个，方便反复覆盖安装
    signingConfigs {
        create("lite") {
            storeFile = file("lite.keystore")
            storePassword = "lite2026"
            keyAlias = "lite"
            keyPassword = "lite2026"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("lite")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("lite")
        }
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        // For AGP 4.1+
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

fun getTag(): String {
    return try {
        val process = Runtime.getRuntime().exec("git describe --tags --always")
        process.waitFor()
        process.inputStream.bufferedReader().use(BufferedReader::readText).trim().removePrefix("v")
    } catch (_: Exception) {
        ""
    }
}

fun getVersionCode(): Int {
    return try {
        val arr = (getTag().replace(".", " ").replace("-", " ") + " 0").split(" ")
        arr[0].toInt() * 16777216 + arr[1].toInt() * 65536 + arr[2].toInt() * 256 + arr[3].toInt()
    } catch (_: Exception) {
        1
    }
}

fun getVersionName(): String {
    return getTag().ifEmpty {
        "0.0.0-1"
    }
}

dependencies {
    // For AGP 7.4+
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.datasource.rtmp)

    implementation(libs.nanohttpd)
    implementation(libs.gua64)
    implementation(libs.zxing)
    implementation(libs.glide)

    implementation(libs.gson)
    implementation(libs.okhttp)

    implementation(libs.core.ktx)
    implementation(libs.coroutines)

    implementation(libs.constraintlayout)
    implementation(libs.appcompat)
    implementation(libs.recyclerview)
    implementation(libs.lifecycle.viewmodel)

    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
}