import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    `maven-publish`
}

group = "pl.blizinski"
version = "0.1.0"

kotlin {
    android {
        namespace = "pl.blizinski.googletasksstore"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTestBuilder {}.configure {
            isReturnDefaultValues = true
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
            // Resolved via JitPack normally; substituted for the local checkout when one exists
            // as a sibling directory — see settings.gradle.kts.
            implementation("com.github.automaciej:task-sync-kotlin:v0.1.1")
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}
