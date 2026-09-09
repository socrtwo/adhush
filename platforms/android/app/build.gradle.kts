plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "io.adhush.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.adhush.android"
        minSdk = 26          // AudioSource.UNPROCESSED (24), notification channels (26)
        targetSdk = 35
        versionCode = 4
        versionName = "0.7.3"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "META-INF/*.kotlin_module" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
