plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The active translation engine is ML Kit. Keep the separate desktop/experimental
// CTranslate2 bridge opt-in so normal APKs do not fetch, build or ship it.
val experimentalTranslation = providers.gradleProperty("itantraExperimentalTranslation")
    .map(String::toBoolean).orElse(false)

android {
    namespace = "com.example.itantra"
    // NOTE: verify API 37 is actually installed / released in whatever environment builds
    // this (local machine + CI). If this was meant to be 35 or 36, a typo here is a hard
    // build failure, not a warning - worth confirming explicitly rather than assuming.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.itantra"
        minSdk = 26
        targetSdk = 37
        versionCode = 16
        versionName = "1.15-security"

        ndk {
            // Restricting to arm64-v8a only will crash on launch (UnsatisfiedLinkError) on any
            // 32-bit (armeabi-v7a) or x86/x86_64 device or emulator, since the sherpa-onnx .so
            // libs won't be packaged for those ABIs. Fine if every demo/judging device is a
            // modern arm64 phone - just confirm that before locking it in, and note that the
            // default Android Studio emulator images are x86_64 and won't run this build at all.
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts.add("**/libonnxruntime.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }
    if (experimentalTranslation.get()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }
    sourceSets {
        // Sherpa and ONNX native libraries come from the patched AAR. The legacy
        // jniLibs copies otherwise silently override that runtime in the APK.
        getByName("main").jniLibs.setSrcDirs(emptyList<String>())
        getByName("test").assets.srcDirs("$projectDir/schemas")
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.compose.material:material-icons-extended")

    // Jetpack DataStore (Settings Persistence)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room persistence & SQLite (Message queue, retry state)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.19.0@aar")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines for asynchronous pipeline execution
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Google ML Kit on-device translation (Phase 5: Hindi↔English offline MT)
    implementation("com.google.mlkit:translate:17.0.3")
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0") {
            because("ML Kit's transitive OkHttp 3.0.0 has certificate-validation advisories")
        }
    }
    // Kotlin coroutines integration for Google Play Services Tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")

    // Local AAR libraries (sherpa-onnx, etc.)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // KotlinX Serialization for Language Pack manifests
    implementation(libs.kotlinx.serialization.json)

    // ViewModel Compose integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    testImplementation(libs.junit)
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.json:json:20231013")
    testImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Model weights are always installed on demand. Keep local benchmark/model files
// available to developers without accidentally embedding them in either APK.
tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
    doLast {
        val mergeTask = this as? com.android.build.gradle.tasks.MergeSourceSetFolders ?: return@doLast
        val outDir = mergeTask.outputDir.orNull?.asFile ?: return@doLast
        val packsDir = File(outDir, "language_packs")
        if (packsDir.isDirectory) {
            check(packsDir.canonicalFile.toPath().startsWith(outDir.canonicalFile.toPath()))
            packsDir.walkTopDown().filter { it.isFile && it.extension == "onnx" }.forEach { model ->
                check(model.delete()) { "Could not exclude on-demand model from APK: $model" }
            }
        }
    }
}

// A local fine-tuning manifest is never allowed into a build without checking
// it against the complete FLEURS validation/test protection index.
val verifySpeechDataLeakage by tasks.registering(Exec::class) {
    onlyIf { rootProject.file("tools/stt_training/manifests").isDirectory }
    workingDir = rootProject.projectDir
    commandLine("python", "tools/check_speech_leakage.py", "workspace-check")
}
tasks.matching { it.name == "testDebugUnitTest" || it.name == "check" }.configureEach {
    dependsOn(verifySpeechDataLeakage)
}
