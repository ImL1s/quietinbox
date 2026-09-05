plugins {
    alias(libs.plugins.quietinbox.android.library)
    alias(libs.plugins.quietinbox.android.hilt)
}

android {
    namespace = "dev.quietinbox.platform.crypto"

}

dependencies {
    api(project(":core:model"))
    implementation(libs.tink.android)
}
