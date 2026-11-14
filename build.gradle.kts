// I pin the Kotlin Gradle plugin here so it matches the Compose compiler plugin below.
// AGP 9 brings its own built-in Kotlin, and I don't want the two versions drifting apart.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
