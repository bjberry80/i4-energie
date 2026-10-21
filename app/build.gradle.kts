import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Upload-Schlüssel liegt bewusst außerhalb des Projekts (nicht in Nextcloud/Git).
val uploadKey: Properties? = File(System.getProperty("user.home"), ".i4energie-signing/keystore.properties")
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "de.i4energie"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.i4energie"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Eigene Paket-ID, damit die Debug-Version neben der Play-Version installiert sein kann.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "i4 Energie Debug")
        }
        release {
            resValue("string", "app_name", "i4 Energie")
            isMinifyEnabled = false
            // Mit Upload-Schlüssel für Google Play; ohne ihn (z. B. in CI) mit dem Debug-Schlüssel.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Sideload-App ohne Play-Store-Review: Lint soll den Release-Build nicht blockieren.
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val carApp = "1.7.0"
    implementation("androidx.car.app:app:$carApp")
    implementation("androidx.car.app:app-projected:$carApp")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Echte org.json-Implementierung für Parser-Tests (android.jar enthält nur Stubs).
    testImplementation("org.json:json:20250517")
}
