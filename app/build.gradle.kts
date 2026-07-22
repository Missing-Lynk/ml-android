import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// GStreamer Android universal SDK root (the dir containing arm64/ armv7/ ...). Set
// `gst.dir=/path/to/android-gst` in local.properties (machine-specific, not committed).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val gstDir: String = localProps.getProperty("gst.dir") ?: "/home/chris/android-gst"

android {
    namespace = "com.brushlesswhoop.missinglynk"
    compileSdk {
        version = release(36)
    }
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "at.websium.ml"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // arm64 only (the phone; the GStreamer payload otherwise bloats the APK).
        ndk {
            abiFilters += "arm64-v8a"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

// Build the GStreamer JNI lib via ndk-build and stage the .so's (plus libc++_shared.so)
// into jniLibs, the robust approach proven in v3xctrl (avoids externalNativeBuild's
// GStreamer.java-generation fragility; GStreamer.java is committed under src/main/java).
val ndkDir = android.ndkDirectory.absolutePath
val abiList = listOf("arm64-v8a")
val jniLibsDir = file("src/main/jniLibs")

val buildNativeLibs by tasks.registering {
    val jniDir = file("src/main/jni")
    inputs.files(fileTree(jniDir) { include("**/*.c", "**/*.h", "**/*.mk") })
    outputs.dir(jniLibsDir)

    doLast {
        abiList.forEach { abi ->
            exec {
                commandLine(
                    "$ndkDir/ndk-build",
                    "NDK_PROJECT_PATH=null",
                    "APP_BUILD_SCRIPT=$jniDir/Android.mk",
                    "NDK_APPLICATION_MK=$jniDir/Application.mk",
                    "APP_ABI=$abi",
                    "NDK_ALL_ABIS=$abi",
                    "APP_PLATFORM=android-24",
                    "NDK_OUT=${layout.buildDirectory.get()}/ndk/obj",
                    "NDK_LIBS_OUT=${layout.buildDirectory.get()}/ndk/libs",
                    "GSTREAMER_ROOT_ANDROID=$gstDir",
                )
                workingDir = projectDir
            }
            val outDir = file("${layout.buildDirectory.get()}/ndk/libs/$abi")
            val destDir = file("$jniLibsDir/$abi")
            destDir.mkdirs()
            outDir.listFiles()?.filter { it.extension == "so" }?.forEach { so ->
                so.copyTo(File(destDir, so.name), overwrite = true)
            }
            // libc++_shared.so is needed because APP_STL=c++_shared
            val triple = when (abi) {
                "arm64-v8a" -> "aarch64-linux-android"
                "armeabi-v7a" -> "arm-linux-androideabi"
                "x86_64" -> "x86_64-linux-android"
                else -> return@forEach
            }
            val stl = file("$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$triple/libc++_shared.so")
            if (stl.exists()) stl.copyTo(File(destDir, "libc++_shared.so"), overwrite = true)
        }
    }
}

tasks.named("preBuild") { dependsOn(buildNativeLibs) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference)
}
