pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// Use the Windows profile for Android preferences when a reused Gradle daemon
// has an invalid Java user.home. Keep personal paths out of project files.
if (System.getProperty("ANDROID_USER_HOME") == null &&
    System.getenv("ANDROID_USER_HOME") == null &&
    System.getProperty("os.name").contains("Windows", ignoreCase = true)
) {
    System.getenv("USERPROFILE")?.let { profile ->
        System.setProperty("ANDROID_USER_HOME", java.io.File(profile, ".android").absolutePath)
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            content { includeGroupByRegex("ai\\.pleos\\.playground(\\..*)?") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "LuckyDraw"
include(":app")
