plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors = false
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    api(libs.kotlinx.collections.immutable)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
