pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TennisPro"

// Pure-JVM module: match state, scoring, and the phone<->watch wire protocol.
// Kept Android-free on purpose so it unit-tests on the JVM in milliseconds.
include(":core")

// Android application modules. They share `applicationId` and must be signed
// with the same key, otherwise the Wearable Data Layer will not connect them.
include(":phone")
include(":wear")
