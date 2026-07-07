# google-tasks-store

[![](https://jitpack.io/v/automaciej/google-tasks-kotlin.svg)](https://jitpack.io/#automaciej/google-tasks-kotlin)

Android library that wraps the [Google Tasks API](https://developers.google.com/tasks)
with a local Room cache and exposes a reactive `TaskStoreApi`.

## Usage

Add the JitPack repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.automaciej:google-tasks-kotlin:0.1.0")
}
```

## Build

```
./gradlew build
```
