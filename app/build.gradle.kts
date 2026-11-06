plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciRunNumber = providers.gradleProperty("ciRunNumber").orNull?.toInt()
val ciRunAttempt = providers.gradleProperty("ciRunAttempt").orNull?.toInt() ?: 1
val appVersionCode = (ciRunNumber ?: 0) * 100 + ciRunAttempt + 1000
val signingStorePath = System.getenv("CI_SIGNING_STORE_PATH")

android {
    namespace = "com.liar.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.liar.game"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    if (!signingStorePath.isNullOrBlank()) {
        signingConfigs.create("ciRelease") {
            storeFile = file(signingStorePath)
            storePassword = System.getenv("CI_SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("CI_SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("CI_SIGNING_KEY_PASSWORD")
        }
        buildTypes.getByName("release") {
            signingConfig = signingConfigs.getByName("ciRelease")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
