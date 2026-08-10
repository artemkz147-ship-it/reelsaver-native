plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.artem.neuroreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.artem.neuroreader"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("onnx", "bin", "json")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(files("libs/sherpa-onnx.aar"))
}
