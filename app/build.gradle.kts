plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.aohp.driver"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.aohp.driver"
        // AppAuth OAuth redirect: aohp://oauth/<provider> is captured by RedirectUriReceiverActivity.
        manifestPlaceholders["appAuthRedirectScheme"] = "aohp"
        minSdk = 34
        targetSdk = 36
        versionCode = 25
        versionName = "0.9.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // app/src/main/jniLibs/arm64-v8a carries llama-server (as libllama-server.so) and its ggml
        // backends (CPU + Hexagon NPU skels). They are executed by aohp-containerd as a HostExec= unit,
        // not loaded by the app, so they must be real files on disk: legacy packaging + extractNativeLibs.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    // agent bridge (ported from the stock AOHPAgentDriver): ws server + Keystore-backed secrets
    implementation(libs.java.websocket)
    implementation(libs.androidx.security.crypto)
    implementation(libs.appauth)
    implementation(libs.zxing.android.embedded)   // QR scan of a provider key (Keys tab); pure Java, no Play Services
    implementation(libs.zxing.core)
}
