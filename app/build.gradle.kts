import java.net.URL
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.smartradio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.smartradio"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        compose = true
    }

    // YAMNet .tflite model is fetched by the downloadYamnetAssets task below.
    // Keep it out of compression so TFLite can mmap it directly.
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// This sandbox has no network access, so these assets couldn't be fetched
// while generating the project. Your actual build machine (GitHub Actions,
// Android Studio) does have network, so this task fetches them automatically
// the first time you build — no manual download step needed.
val downloadYamnetAssets by tasks.registering {
    val assetsDir = file("src/main/assets")
    val modelFile = File(assetsDir, "yamnet.tflite")
    val labelsFile = File(assetsDir, "yamnet_label_list.txt")
    outputs.files(modelFile, labelsFile)

    doLast {
        assetsDir.mkdirs()

        if (!modelFile.exists()) {
            logger.lifecycle("Downloading YAMNet model to ${modelFile.path} ...")
            URL("https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1?lite-format=tflite")
                .openStream().use { input -> modelFile.outputStream().use { output -> input.copyTo(output) } }
        }

        if (!labelsFile.exists()) {
            logger.lifecycle("Downloading YAMNet label map to ${labelsFile.path} ...")
            val csv = URL(
                "https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv"
            ).readText()
            // Columns are index,mid,display_name. display_name is always last,
            // but can itself contain commas (e.g. "Livestock, farm animals,
            // working animals") — the first two commas are always the real
            // field separators, so split on those rather than the last comma.
            fun displayNameOf(line: String): String {
                var commaCount = 0
                var i = 0
                while (i < line.length && commaCount < 2) {
                    if (line[i] == ',') commaCount++
                    i++
                }
                return line.substring(i).trim().trim('"')
            }
            val labels = csv.lineSequence()
                .drop(1) // header
                .filter { it.isNotBlank() }
                .map { line -> displayNameOf(line) }
                .joinToString("\n")
            labelsFile.writeText(labels)
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadYamnetAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media playback (handles both HTTP/ICY internet-radio streams and HLS)
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")

    // On-device audio scene classification (speech vs. music) via YAMNet.
    // Using LiteRT (Google's official successor to classic TensorFlow Lite)
    // instead of org.tensorflow:tensorflow-lite: the classic artifact's
    // native library (libtensorflowlite_jni.so) was built for 4 KB memory
    // pages and Google has confirmed it cannot be patched — LiteRT ships a
    // 16 KB-aligned build and exposes the same org.tensorflow.lite.Interpreter
    // API, so no source changes were needed beyond this dependency swap.
    implementation("com.google.ai.edge.litert:litert:2.1.0")

    // Persistence for station list + preference order. Deliberately plain
    // SharedPreferences rather than Jetpack DataStore: DataStore's native
    // counter library (libdatastore_shared_counter.so) has had an inconsistent
    // 16 KB-alignment history across versions, and SharedPreferences has no
    // native library at all, so the problem doesn't exist here by construction.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.guava:guava:33.2.1-android")

    // Station directory search (Radio Browser API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
