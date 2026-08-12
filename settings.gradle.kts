rootProject.name = "google-tasks-store"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// task-sync-kotlin is normally consumed via JitPack (see build.gradle.kts's
// "com.github.automaciej:task-sync-kotlin" dependency), so this library builds standalone for
// anyone who clones just this repo. When a checkout of task-sync-kotlin exists as a sibling
// directory (this project's own development layout, alongside TaskCompass), it's included as a
// composite build instead and substituted for that same coordinate — local edits are then
// reflected immediately, with no publish/tag/version-bump step required.
val taskSyncKotlinLocal = file("../task-sync-kotlin")
if (taskSyncKotlinLocal.exists()) {
    includeBuild(taskSyncKotlinLocal) {
        dependencySubstitution {
            substitute(module("com.github.automaciej:task-sync-kotlin")).using(project(":"))
        }
    }
}
