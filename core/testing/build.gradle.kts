plugins {
    alias(libs.plugins.quietinbox.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotest.assertions.core)
}
