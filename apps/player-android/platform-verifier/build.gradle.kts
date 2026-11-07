import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.library") }

// The JNI protocol must stay synchronized with the pinned Rust counterpart.
val lock = rootProject.file("../../Cargo.lock").readText()
require(Regex("name = \"rustls-platform-verifier-android\"\\s+version = \"0\\.2\\.0\"").containsMatchIn(lock)) {
    "Review the local platform verifier before upgrading its Rust JNI counterpart"
}
val privateCa = providers.environmentVariable("TILECAST_PRIVATE_CA_SHA256").orElse("").get()
require(privateCa.isEmpty() || privateCa.matches(Regex("[0-9a-fA-F]{64}"))) {
    "TILECAST_PRIVATE_CA_SHA256 must be a SHA-256 certificate fingerprint"
}

android {
    namespace = "org.rustls.platformverifier"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
        buildConfigField("boolean", "TEST", "false")
        buildConfigField("String", "PRIVATE_CA_SHA256", "\"$privateCa\"")
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies { testImplementation("junit:junit:4.13.2") }
