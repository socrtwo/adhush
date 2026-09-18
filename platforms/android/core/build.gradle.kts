plugins {
    kotlin("jvm") version "2.0.21"
}

group = "io.adhush"
version = "0.27.0"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // A self-signed client certificate for Android TV's remote protocol (ADR 0026); nothing else needs it.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); showStandardStreams = false }
}
