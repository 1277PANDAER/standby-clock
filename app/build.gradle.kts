import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 本地私有配置（local.properties 已在 .gitignore 里）：和风天气的 host/key 只留在本机，不进仓库
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.standbyclock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.standbyclock"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.9"

        // 留空 = 不开和风（开源版默认）：只用免密钥的 Open-Meteo / wttr.in
        buildConfigField("String", "QW_HOST", "\"${localProps.getProperty("qw.host") ?: ""}\"")
        buildConfigField("String", "QW_KEY", "\"${localProps.getProperty("qw.key") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
