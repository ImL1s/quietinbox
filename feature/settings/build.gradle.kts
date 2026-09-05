plugins {
    alias(libs.plugins.quietinbox.android.feature)
}

android {
    namespace = "dev.quietinbox.feature.settings"

}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:capture"))
    implementation(project(":platform:backup"))
    implementation(project(":platform:crypto"))
    implementation(libs.androidx.biometric)
}
