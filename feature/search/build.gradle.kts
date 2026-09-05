plugins {
    alias(libs.plugins.quietinbox.android.feature)
}

android {
    namespace = "dev.quietinbox.feature.search"

}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:capture"))
}
