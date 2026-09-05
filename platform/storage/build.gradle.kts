plugins {
    alias(libs.plugins.quietinbox.android.library)
    alias(libs.plugins.quietinbox.android.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.quietinbox.platform.storage"
    defaultConfig {
        testInstrumentationRunner = "dev.quietinbox.platform.storage.HiltTestRunner"
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:parser"))
    api(project(":core:identity"))
    api(project(":core:reconcile"))
    api(project(":core:analytics"))
    api(project(":platform:crypto"))
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.work.testing)
}

room {
    schemaDirectory("$projectDir/schemas")
}
