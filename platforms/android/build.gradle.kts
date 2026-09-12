// Intentionally empty: each module declares its own plugins, with versions,
// so that `core` never needs the Android Gradle Plugin resolved (see
// settings.gradle.kts). Gradle warns that the Kotlin plugin is loaded twice;
// the usual cure (declaring it here with `apply false`) puts the Kotlin
// Android plugin on a classpath that cannot see AGP and fails outright, so
// the warning is the lesser evil until core-only builds can be dropped.
