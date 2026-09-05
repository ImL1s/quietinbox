plugins {
    alias(libs.plugins.quietinbox.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:parser"))
    testImplementation(project(":core:testing"))
}
