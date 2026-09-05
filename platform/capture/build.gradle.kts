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
}
