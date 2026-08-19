import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Release-Signatur aus keystore.properties (im Projekt-Root, gitignored). Fehlt sie,
// signiert der Release-Build mit dem Debug-Keystore (Fallback).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "com.selfmessenger.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.selfmessenger.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "0.1.18"
    }
    signingConfigs {
        // Android-Standard-Debug-Keystore aus der Toolchain (Passwort "android" ist kein Secret).
        create("self") {
            storeFile = rootProject.file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("self")
        }
        debug { signingConfig = signingConfigs.getByName("self") }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // App-Sperre: PIN / Fingerabdruck / Gesicht
    implementation("androidx.biometric:biometric:1.1.0")
    // Verschlüsselte Speicherung (Identität/Kontakte at-rest)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR-Code für persönliches Pairing
    implementation("com.google.zxing:core:3.5.3")
    // Signaling-Client (WebSocket zum Cloudflare-Kuppler)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // WebRTC (gepflegter Fork, org.webrtc-Namespace) — Direktverbindung + Anrufe
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    // WireGuard-Tunnel (VPN: IP verstecken, Mullvad/eigenes) — bringt libwg-go.so mit
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    // Push für geschlossene App (FCM sieht nur "neue Nachricht", keinen Inhalt)
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")
}
