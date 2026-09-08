// Two modules. `core` is pure Kotlin/JVM: every detector, fusion, the state
// machine, fingerprinting and the Sharp client — buildable and testable on any
// machine with a JDK. `app` is the thin Android shell around it and needs the
// Android SDK. ADHUSH_CORE_ONLY=1 builds without the SDK or Google's Maven,
// which is how the core tests run in a sandbox that cannot reach either.
val coreOnly = System.getenv("ADHUSH_CORE_ONLY") == "1"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        if (System.getenv("ADHUSH_CORE_ONLY") != "1") google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        if (System.getenv("ADHUSH_CORE_ONLY") != "1") google()
    }
}
rootProject.name = "adhush-android"
include(":core")
if (!coreOnly) include(":app")
