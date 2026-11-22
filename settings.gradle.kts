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
        // Public, anonymous PICO Spatial SDK distribution.
        maven {
            name = "PicoSpatialPublic"
            url = uri("https://artifact.bytedance.com/repository/Volcengine")
            content { includeGroupByRegex("com\\.pico\\..*") }
        }
    }
}
rootProject.name = "SpatialPhoto"
include(":app")
