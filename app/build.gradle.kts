import java.io.File

plugins {
    id("com.android.application")
}

// OneDrive can lock Gradle's generated resources while syncing them.
// Keep generated output and the local debug signing file in the Windows user profile.
val localStateDir = System.getenv("LOCALAPPDATA")?.let { File(it, "LuckyDrawAndroidStudio") }
if (localStateDir != null) {
    val projectId = Integer.toHexString(rootProject.projectDir.canonicalPath.lowercase().hashCode())
    layout.buildDirectory.set(File(localStateDir, "build/$projectId/app"))
}

android {
    namespace = "com.example.luckydraw"
    compileSdk {
        version = release(37)
    }

    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.example.luckydraw"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (localStateDir != null) {
        signingConfigs.getByName("debug") {
            storeFile = File(localStateDir, "debug.keystore")
        }
    }

}

dependencies {
    // Pin the cached AndroidX versions required by Material and Pleos Vehicle.
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // gradle.properties skips this uncached artifact by default; enable it after Pleos Maven access works.
    if (providers.gradleProperty("skipPleosNavi").orNull != "true") {
        implementation("ai.pleos.playground:NaviHelper:3.0.5")
    }
}

