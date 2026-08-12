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
    // the android {} block above.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            // Generic root coordinate works here (unlike the androidMain/wasmJsMain
            // dependencies below): commonMain consumes task-sync-kotlin's Kotlin metadata
            // variant, which JitPack does publish correctly under the plain group.
            implementation("com.github.automaciej:task-sync-kotlin:v0.2.0")
        }
        androidMain.dependencies {
            implementation(libs.serialization.json)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.google.api.client.android)
            implementation(libs.google.api.services.tasks)
            implementation(libs.work.runtime.ktx)
            // Resolved via JitPack normally; substituted for the local checkout when one exists
            // as a sibling directory — see settings.gradle.kts. Pinned to the target-specific
            // "-android" artifact rather than the generic root coordinate: JitPack's rewritten
            // Gradle module metadata doesn't reliably resolve cross-artifact "available-at"
            // variants once task-sync-kotlin publishes more than one target.
            implementation("com.github.automaciej.task-sync-kotlin:task-sync-kotlin-android:v0.2.0")
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                // See the androidMain dependency above for why this is pinned to the
                // target-specific artifact instead of the generic root coordinate.
                implementation("com.github.automaciej.task-sync-kotlin:task-sync-kotlin-wasm-js:v0.2.0")
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}
