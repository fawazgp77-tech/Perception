plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
buildscript {
    repositories { google(); mavenCentral(); maven("https://maven.mappls.com/repository/mappls/") }
    dependencies { classpath("com.mappls.services:mappls-services:1.0.0") }
}
