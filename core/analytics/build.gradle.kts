plugins {
    alias(libs.plugins.quietinbox.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.datetime)
    testImplementation(project(":core:testing"))
}
