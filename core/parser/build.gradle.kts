plugins {
    alias(libs.plugins.quietinbox.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    testImplementation(project(":core:testing"))
}
