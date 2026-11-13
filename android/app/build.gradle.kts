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
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
