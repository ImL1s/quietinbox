pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QuietInbox"

include(":app")

// Pure Kotlin (JVM) modules: no android.* dependency allowed.
include(":core:model")
include(":core:parser")
include(":core:identity")
include(":core:reconcile")
include(":core:analytics")
include(":core:testing")
include(":parsers:apps")

// Android platform modules.
include(":core:designsystem")
include(":platform:crypto")
include(":platform:storage")
include(":platform:capture")
include(":platform:media")
include(":platform:backup")

// Feature (UI) modules.
include(":feature:onboarding")
include(":feature:inbox")
include(":feature:conversation")
include(":feature:search")
include(":feature:health")
include(":feature:settings")
include(":feature:analytics")
