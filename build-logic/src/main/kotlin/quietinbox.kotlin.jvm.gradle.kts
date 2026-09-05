// Pure-Kotlin/JVM module convention: no android.* on the classpath, Kotest on JUnit Platform.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(17)
}

dependencies {
    "implementation"(libs.findLibrary("kotlinx-coroutines-core").get())
    "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
    "implementation"(libs.findLibrary("kotlinx-collections-immutable").get())
    "testImplementation"(libs.findLibrary("kotest-runner-junit5").get())
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("kotest-property").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}
