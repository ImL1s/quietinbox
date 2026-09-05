plugins {
    alias(libs.plugins.quietinbox.android.library)
    alias(libs.plugins.quietinbox.android.hilt)
}

android {
    namespace = "dev.quietinbox.platform.media"

}

dependencies {
    api(project(":core:model"))
    implementation(project(":platform:crypto"))
    implementation(project(":platform:storage"))
}
