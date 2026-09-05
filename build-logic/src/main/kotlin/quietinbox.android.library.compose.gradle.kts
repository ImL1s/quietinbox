// Android library + Jetpack Compose (Material 3 Expressive) convention.
plugins {
    id("quietinbox.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    val bom = libs.findLibrary("androidx-compose-bom").get()
    "implementation"(platform(bom))
    "androidTestImplementation"(platform(bom))
    "implementation"(libs.findLibrary("androidx-compose-ui").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-graphics").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    "implementation"(libs.findLibrary("androidx-compose-foundation").get())
    "implementation"(libs.findLibrary("androidx-compose-material3").get())
    "implementation"(libs.findLibrary("androidx-compose-material-icons-extended").get())
    "implementation"(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    "debugImplementation"(libs.findLibrary("androidx-compose-ui-tooling").get())
    "androidTestImplementation"(libs.findLibrary("androidx-compose-ui-test-junit4").get())
    "debugImplementation"(libs.findLibrary("androidx-compose-ui-test-manifest").get())
}
