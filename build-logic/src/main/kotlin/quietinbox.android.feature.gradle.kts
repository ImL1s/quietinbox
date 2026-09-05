// Feature module convention: Compose + Hilt ViewModels + Navigation 3 keys + design system.
plugins {
    id("quietinbox.android.library.compose")
    id("quietinbox.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(project(":core:model"))
    "implementation"(project(":core:designsystem"))
    "implementation"(libs.findLibrary("androidx-activity-compose").get())
    "implementation"(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    "implementation"(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    "implementation"(libs.findLibrary("androidx-hilt-lifecycle-viewmodel-compose").get())
    "implementation"(libs.findLibrary("androidx-navigation3-runtime").get())
    "implementation"(libs.findLibrary("kotlinx-collections-immutable").get())
    "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
    "implementation"(libs.findLibrary("kotlinx-datetime").get())
}
