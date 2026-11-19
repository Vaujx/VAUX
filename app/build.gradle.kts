import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "app.vaux"
    compileSdk = 35
    defaultConfig { applicationId = "app.vaux"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" 
        
    ndk { abiFilters += "arm64-v8a" }
    }
    val ks = rootProject.file("keystore.properties")
    signingConfigs { if (ks.exists()) create("release") {
        val p = Properties().apply { ks.inputStream().use { load(it) } }
        storeFile = rootProject.file(p["file"] as String); storePassword = p["pass"] as String
        keyAlias = "vaux"; keyPassword = p["pass"] as String } }
    packaging { jniLibs { useLegacyPackaging = true } }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
