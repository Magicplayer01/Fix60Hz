plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint") version "12.2.0" // 👈 ДОБАВЬТЕ ЭТУ СТРОКУ
}

android {
    namespace = "com.example.fix60hz"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.fix60hz"
        minSdk = 31
        targetSdk = 34
        versionCode = 99
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("de.robv.android.xposed:api:82:sources")
}
