// Top-level build file. Plugins are declared here and applied in module build files.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Required for Compose with Kotlin 2.0+ (replaces kotlinCompilerExtensionVersion).
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
