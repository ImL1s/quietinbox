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
}
