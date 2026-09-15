pluginManagement {
  repositories {
    maven("https://maven.fabricmc.net/")
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "squaremap-pro"

include("map-core")
include("map-fabric")
