// Android library convention: SDK levels, Java 17, shared opt-ins and unit-test deps.
plugins {
    id("com.android.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    "implementation"(libs.findLibrary("kotlinx-coroutines-android").get())
    "implementation"(libs.findLibrary("androidx-core-ktx").get())
    "testImplementation"(libs.findLibrary("junit").get())
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-ext-junit").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
    "androidTestImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "androidTestImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
}
