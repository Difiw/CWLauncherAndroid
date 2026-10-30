plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "ru.cw.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.cw.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 27200
        versionName = "2.7.2"
        ndk {
            // The bundled Java 17 runtime and MJLauncher JVM backend are ARM64-only.
            // Do not let Android install an APK that cannot start the game.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Produce an installable optimized APK for local distribution.
            // Replace this with a private release keystore for public publishing.
            signingConfig = signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "zip"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The APK-side Cacio shim has the same SONAME as OpenJDK's
            // libawt_headless.so but does not export java.awt.Font.initIDs.
            // Shipping it lets Android's linker select the incompatible file.
            excludes += "/lib/arm64-v8a/libawt_headless.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
