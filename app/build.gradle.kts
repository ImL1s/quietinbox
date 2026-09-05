import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.quietinbox.android.hilt)
}

// Release signing: keystore.properties (gitignored) on a maintainer machine, or QUIETINBOX_KEYSTORE_*
// environment variables in CI. Without either, release builds stay unsigned (CI permission gate only).
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
val envKeystore: String? = System.getenv("QUIETINBOX_KEYSTORE_FILE")

android {
    namespace = "dev.quietinbox"
    compileSdk = 37

    signingConfigs {
        create("release") {
            when {
                keystoreProps != null -> {
                    fun prop(name: String) = requireNotNull(keystoreProps.getProperty(name)) { "keystore.properties is missing '$name'" }
                    storeFile = file(prop("storeFile"))
                    storePassword = prop("storePassword")
                    keyAlias = prop("keyAlias")
                    keyPassword = prop("keyPassword")
                }
                envKeystore != null -> {
                    fun env(name: String) = requireNotNull(System.getenv(name)) { "$name must be set together with QUIETINBOX_KEYSTORE_FILE" }
                    storeFile = file(envKeystore)
                    storePassword = env("QUIETINBOX_KEYSTORE_PASSWORD")
                    keyAlias = env("QUIETINBOX_KEY_ALIAS")
                    keyPassword = env("QUIETINBOX_KEY_PASSWORD")
                }
            }
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "dev.quietinbox.app"
        minSdk = 26
        // Baseline target per plan §4; an API 37 compatibility lane is tracked in docs/COMPATIBILITY.md.
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.0"
        testInstrumentationRunner = "dev.quietinbox.HiltTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hant")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
        jniLibs.useLegacyPackaging = false
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
        )
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":platform:crypto"))
    implementation(project(":platform:storage"))
    implementation(project(":platform:capture"))
    implementation(project(":platform:media"))
    implementation(project(":platform:backup"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:inbox"))
    implementation(project(":feature:conversation"))
    implementation(project(":feature:search"))
    implementation(project(":feature:health"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:analytics"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
