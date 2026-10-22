pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor (YouTube) and its nanojson dependency are published on JitPack only.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.(?i)teamnewpipe.*") }
        }
    }
}

rootProject.name = "StorySaver"
include(":app")
