import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// release 签名信息从仓库外的 keystore.properties 读取。
// 这个文件和 .jks 都在 .gitignore 里 —— 私钥永远不进版本库。
// 没有这个文件时 release 仍可构建，只是产物未签名（assembleRelease 会生成 *-unsigned.apk）。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.kc0ver.ncmdump"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kc0ver.ncmdump"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // 只打包 native/build.sh 实际编译出的 ABI
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 原生 ncmdump 二进制在 jniLibs 里，不受 R8 影响；
            // Kotlin/Compose 部分没有反射，可以放心开混淆和资源压缩。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // 必须解压 native 库：ncmdump 二进制要以可执行文件形式从
            // applicationInfo.nativeLibraryDir 被 exec()，压缩在 APK 里是不行的。
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    debugImplementation(libs.androidx.ui.tooling)
}
