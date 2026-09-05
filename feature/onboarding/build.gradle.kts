plugins {
    alias(libs.plugins.quietinbox.android.feature)
}

android {
    namespace = "dev.quietinbox.feature.onboarding"

}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:capture"))
    implementation(libs.androidx.activity.compose)
}
