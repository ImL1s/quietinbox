plugins {
    alias(libs.plugins.quietinbox.android.library)
    alias(libs.plugins.quietinbox.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.quietinbox.platform.capture"

}

dependencies {
    api(project(":core:model"))
    api(project(":core:parser"))
    implementation(project(":core:identity"))
    implementation(project(":core:reconcile"))
    api(project(":parsers:apps"))
    implementation(project(":platform:storage"))
    implementation(project(":platform:media"))
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
    testImplementation(project(":core:testing"))
}

// Kotest specs run on the JUnit Platform; every test in this module is a Kotest spec, so no
// JUnit4 vintage engine is needed.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}

