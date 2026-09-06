plugins {
    alias(libs.plugins.quietinbox.android.feature)
}

android {
    namespace = "dev.quietinbox.feature.search"

}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:capture"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}

// Kotest specs run on the JUnit Platform; every test in this module is a Kotest spec.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}
