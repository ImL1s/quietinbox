plugins {
    alias(libs.plugins.quietinbox.android.library)
    alias(libs.plugins.quietinbox.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.quietinbox.platform.backup"

}

dependencies {
    api(project(":core:model"))
    implementation(project(":platform:crypto"))
    implementation(project(":platform:storage"))
    implementation(libs.tink.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
}

// Kotest specs run on the JUnit Platform; every test in this module is a Kotest spec, so no
// JUnit4 vintage engine is needed.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}

