// The Kotlin plugins are declared once here (apply false) so that Gradle loads
// them a single time for both modules; each module applies its own without a
// version. The Android Gradle Plugin stays in `app` only, so that `core` never
// needs it resolved (see settings.gradle.kts).
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
}
