import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
}

// Keys come from -P<NAME> (scripts/r8-smoke.sh), else strait.properties (yours, git-ignored), else the
// placeholders in strait.example.properties.
val straitFile = Properties().apply {
    val own = rootProject.file("strait.properties")
    (if (own.exists()) own else rootProject.file("strait.example.properties")).inputStream().use { load(it) }
}
fun strait(name: String): String = providers.gradleProperty(name).orNull ?: straitFile.getProperty(name)

android {
    namespace = "com.straitlink.example"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.straitlink.example"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "STRAIT_PUBLISHABLE_KEY", "\"${strait("STRAIT_PUBLISHABLE_KEY")}\"")
        buildConfigField("String", "STRAIT_ENDPOINT", "\"${strait("STRAIT_ENDPOINT")}\"")
        // The App Links host in the manifest is your endpoint's host, so one setting points both at your workspace.
        manifestPlaceholders["straitHost"] = URI(strait("STRAIT_ENDPOINT")).host
    }
    buildFeatures { buildConfig = true }
    // Shrunk and obfuscated with R8, like a typical release: scripts/r8-smoke.sh runs this build on a device, so SDK code
    // that breaks under R8 (reflection, missing keep rules) fails a check. Debug-keystore signed so it installs.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Built from this repo's source (settings.gradle.kts). In your app: the JitPack coordinates
    // with a version, as in the README's Install section.
    implementation("com.github.strait-link:strait-sdk-android")
}
