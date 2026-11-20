plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.spatialphoto"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    defaultConfig {
        applicationId = if(providers.gradleProperty("cpuProbe").orNull == "true") "com.example.spatialphoto.cpuprobe" else if(providers.gradleProperty("legacyMigrationBridge").isPresent) "com.example.sharpspatial.p8a41c6d9" else "com.example.spatialphoto"
        manifestPlaceholders["migrationBridgeEnabled"] = providers.gradleProperty("legacyMigrationBridge").isPresent.toString()
        minSdk = 35
        targetSdk = 35
        versionCode = 32
        versionName = "0.32-spatialphoto"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
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
    // Opt-in only: allow the test process to reach the vendor HTP transport.
    if (providers.gradleProperty("npuProbe").orNull == "true") {
        sourceSets.getByName("debug").manifest.srcFile("src/npuProbe/AndroidManifest.xml")
    }
    if (providers.gradleProperty("cpuProbe").orNull == "true") {
        sourceSets.getByName("debug").manifest.srcFile("src/cpuProbe/AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    androidResources { noCompress += listOf("onnx", "data") }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.spatial.bom))
    implementation(libs.spatial.core)
    implementation(libs.spatial.ui.platform)
    implementation(libs.spatial.ui.foundation)
    implementation(libs.spatial.ui.design)
    implementation(libs.spatial.ui.sense)
    implementation(libs.spatial.ui.tracking)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation("com.pico.spatial.ml:securemr")
    implementation("com.pico.spatial.ml:readback")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.junit)
    // Diagnostic-only GPU/NPU providers; production remains the validated CPU backend.
    androidTestImplementation("com.qualcomm.qti:onnxruntime-android-qnn:2.7.0")
    androidTestImplementation("com.qualcomm.qti:qnn-runtime:2.51.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling.preview)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}
