import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val w8Parts = rootProject.fileTree("model-weights") { include("w8-*.bin") }
val generatedAssets = layout.buildDirectory.dir("generated/w8Assets")
val mergedWeights = generatedAssets.map { it.file("models/sharp-w8.onnx.data") }
val prepareW8Weights = tasks.register("prepareW8Weights") {
    inputs.files(w8Parts)
    inputs.file("src/main/assets/models/sharp-w8.onnx")
    outputs.file(mergedWeights)
    doLast {
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                DigestInputStream(input, digest).use { it.copyTo(OutputStream.nullOutputStream()) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        check(hash(file("src/main/assets/models/sharp-w8.onnx")) == "11b0a7e6332e6a736c2e717d173ff6ef42d4724425ab272f50065bd696f53489")
        val target = mergedWeights.get().asFile
        target.parentFile.mkdirs()
        val temp = File.createTempFile("w8-", ".partial", target.parentFile)
        try {
            temp.outputStream().buffered().use { output ->
                for (part in w8Parts.files.sortedBy { it.name }) {
                    part.inputStream().use { it.copyTo(output, 1024 * 1024) }
                }
            }
            check(hash(temp) == "39a785d63590114d99bc06a12d97ce141092887d752244ab1838e4e2c076b0cd") { "W8 weights do not match the selected model" }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
}

android {
    namespace = "com.example.spatialphoto"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    defaultConfig {
        applicationId = if (providers.gradleProperty("localW8").orNull == "true") "com.example.spatialphoto.w8" else "com.example.spatialphoto"
        manifestPlaceholders["migrationBridgeEnabled"] = "false"
        manifestPlaceholders["applicationLabel"] = "@string/app_name"
        minSdk = 35
        targetSdk = 35
        versionCode = 34
        versionName = "0.34-w8"
        buildConfigField("boolean", "DEPTH_SMALL_MODEL", "false")
        buildConfigField("boolean", "WEIGHT_ONLY_MODEL", "true")
        buildConfigField("long", "WEIGHT_ONLY_GRAPH_BYTES", "6742651L")
        buildConfigField("long", "WEIGHT_ONLY_DATA_BYTES", "740485504L")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
    }
    sourceSets.getByName("main").assets.srcDir(generatedAssets)
    tasks.named("preBuild") { dependsOn(prepareW8Weights) }

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // Models are read as streams then copied to private storage, never mmap'ed
    // from APK assets. ZIP compression preserves the validated weights exactly.
    buildFeatures {
        compose = true
        buildConfig = true
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
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation("com.pico.spatial.ml:securemr")
    implementation("com.pico.spatial.ml:readback")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.junit)
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
