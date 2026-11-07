plugins {
    id("com.android.library")
}

// Keep the app's pinned NDK authoritative for both native libraries.
val appBuild = providers.fileContents(rootProject.layout.projectDirectory.file("app/build.gradle.kts")).asText.get()
val pinnedNdk = Regex("""val tilecastNdkVersion = "([^"]+)"""").find(appBuild)?.groupValues?.get(1)
    ?: error("Cannot find the app's pinned tilecastNdkVersion")

android {
    namespace = "io.antmedia.rtmp_client"
    compileSdk = 37
    ndkVersion = pinnedNdk
    defaultConfig {
        minSdk = 23
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
