plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
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
