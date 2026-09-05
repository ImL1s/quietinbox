plugins {
    alias(libs.plugins.quietinbox.android.feature)
}

android {
    namespace = "dev.quietinbox.feature.conversation"

}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:capture"))
    implementation(project(":platform:media"))
}
