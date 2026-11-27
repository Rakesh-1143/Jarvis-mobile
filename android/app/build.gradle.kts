plugins {
    id("com.android.application")
}

val startUrl = providers.gradleProperty("JARVIS_START_URL")
    .orElse("https://rakesh-1143.github.io/Jarvis-mobile/frontend/")

// Each CI build passes a bigger number, so Android shows "Update" over the old app.
val appVersionCode = providers.gradleProperty("JARVIS_VERSION_CODE").orElse("7").get().toInt()

android {
    namespace = "com.growthos.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.growthos.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "0.7.$appVersionCode"
        // Moto G31 and almost every phone since 2017 is 64-bit (arm64). Shipping only that halves the
        // APK (the 32-bit copy of the speech engines alone was ~26 MB). For an old 32-bit phone, build
        // with -PJARVIS_ABIS=arm64-v8a,armeabi-v7a.
        val abis = providers.gradleProperty("JARVIS_ABIS").orElse("arm64-v8a").get().split(",").map { it.trim() }
        ndk { abiFilters += abis }
        buildConfigField("String", "JARVIS_START_URL", "\"${startUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    // Every build must be signed with the same key, otherwise Android refuses
    // to update the installed app ("App not installed"). CI writes the key from
    // the JARVIS_KEYSTORE_BASE64 secret and passes its path here.
    val keystorePath = System.getenv("JARVIS_KEYSTORE_FILE")
    signingConfigs {
        getByName("debug") {
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        // The app installed on the phone. Unlike debug it is not "debuggable" (other tools on a
        // USB-connected computer cannot read its private files) and Android fully compiles it, so
        // it runs faster and uses less battery. Signed with the same fixed key as before, so the
        // phone still shows "Update" over the old debug app.
        release {
            signingConfig = signingConfigs.getByName("debug")
            // Removes unused library code (mostly Kotlin) from the APK. Our own code is kept whole.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures { buildConfig = true }
    // Debug builds never ran lint either; keep CI builds as they were.
    lint { checkReleaseBuilds = false }
    packaging {
        // sherpa-onnx uses the onnxruntime from onnxruntime-android (CI removes its own copy).
        jniLibs { pickFirsts += listOf("**/libonnxruntime.so") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Offline wake word engine runtime (MIT licence, free, no key). Models are in assets/wakeword.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    // "Use my voice": offline speech with the user's own Piper voice. sherpa-onnx (Apache-2.0) 1.12.33;
    // CI downloads the AAR, checks its SHA-256 and drops parts we don't use (see .github/workflows/android.yml).
    implementation(files("libs/sherpa-onnx.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    testImplementation("junit:junit:4.13.2")
}
