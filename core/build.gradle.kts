import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    // Target 17 bytecode rather than requesting a JDK 17 *toolchain*. The Android
    // modules consume this jar and cannot read newer class files, but pinning a
    // toolchain would make the build demand a JDK 17 installation specifically —
    // and Android Studio ships a JDK 21 runtime, so that fails on an otherwise
    // perfectly good machine. Targeting the bytecode level works on any JDK 17+.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // The protocol is a sealed hierarchy shared across two independently installed
        // APKs, so a non-exhaustive `when` is a real wire-compatibility bug rather than
        // a style nit. Kept as warnings for now; tighten once the phases settle.
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
