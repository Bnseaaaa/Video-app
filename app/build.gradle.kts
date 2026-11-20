plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.videoapp.vault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.videoapp.vault"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.8"
    }

    // Chỉ giữ ngôn ngữ tiếng Việt và tiếng Anh trong tài nguyên của thư viện
    androidResources {
        localeFilters += listOf("vi", "en")
    }

    buildTypes {
        release {
            // R8: loại bỏ code/icon/thư viện không dùng và thu gọn tên lớp
            isMinifyEnabled = true
            // Loại bỏ tài nguyên (ảnh, layout...) không dùng
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Tạm ký bằng khoá debug để cài thử được ngay.
            // Khi đưa lên Google Play cần thay bằng khoá release riêng.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}

// Sau mỗi lần build release: xoá các file KhoVideo-*.apk cũ ở thư mục gốc dự án
// rồi chép bản mới ra thành KhoVideo-<versionName>.apk
val exportApk by tasks.registering {
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val outDir = rootProject.projectDir
    val outName = "KhoVideo-${android.defaultConfig.versionName}.apk"
    mustRunAfter("assembleRelease")
    doLast {
        outDir.listFiles { f -> f.isFile && f.name.startsWith("KhoVideo-") && f.name.endsWith(".apk") }
            ?.forEach { it.delete(); println("Đã xoá bản cũ: ${it.name}") }
        val target = File(outDir, outName)
        apk.get().asFile.copyTo(target, overwrite = true)
        println("Bản mới: ${target.absolutePath}")
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(exportApk) }
