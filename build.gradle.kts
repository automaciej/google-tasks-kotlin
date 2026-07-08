import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    `maven-publish`
}

group = "pl.blizinski"
version = "0.1.0"

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.google.api.client.android)
            implementation(libs.google.api.services.tasks)
            implementation(libs.work.runtime.ktx)
            implementation("pl.blizinski:task-sync-kotlin:0.1.0-SNAPSHOT")
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}

android {
    namespace = "pl.blizinski.googletasksstore"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
