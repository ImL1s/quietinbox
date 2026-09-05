plugins {
    alias(libs.plugins.quietinbox.android.library.compose)
}

android {
    namespace = "dev.quietinbox.core.designsystem"

}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.compose.material3.adaptive.navigation.suite)
    api(libs.androidx.compose.material3.window.size)
    api(libs.androidx.compose.material3.adaptive)
    api(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.kotlinx.datetime)
}
