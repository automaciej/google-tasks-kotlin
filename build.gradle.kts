import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
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

// JitPack can't reliably resolve task-sync-kotlin's Gradle module metadata once it publishes
// more than one target (android + wasm-js) — the wasmJs target's own npm/compile-classpath
// resolution ends up trying (and failing) to pick a variant across targets. Since the wasmJs
// target here is a proof-of-concept unused by any JitPack-based project, it's left out of
// JitPack builds entirely (set via `-PjitpackBuild=true` in jitpack.yml); local development
// (including the wasmJs POC) is unaffected.
val isJitpackBuild = project.hasProperty("jitpackBuild")

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

    // wasmJs proof-of-concept target — see TaskCompass's
    // Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md. Additive only: does not touch
    // the android {} block above. Skipped on JitPack — see isJitpackBuild above.
    if (!isJitpackBuild) {
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
            browser()
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            // Resolved via JitPack normally; substituted for the local checkout when one exists
            // as a sibling directory — see settings.gradle.kts.
            implementation("com.github.automaciej:task-sync-kotlin:v0.2.0")
        }
        androidMain.dependencies {
            implementation(libs.serialization.json)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.google.api.client.android)
            implementation(libs.google.api.services.tasks)
            implementation(libs.work.runtime.ktx)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        if (!isJitpackBuild) {
            val wasmJsMain by getting {
                dependencies {
                    implementation(libs.serialization.json)
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.js)
                    implementation(libs.ktor.client.content.negotiation)
                    implementation(libs.ktor.serialization.kotlinx.json)
                    implementation("com.github.automaciej:task-sync-kotlin:v0.2.0")
                }
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}
