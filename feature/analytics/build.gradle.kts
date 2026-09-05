plugins {
    alias(libs.plugins.quietinbox.android.feature)
}

android {
    namespace = "dev.quietinbox.feature.analytics"

}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:capture"))
    implementation(project(":core:analytics"))
}
