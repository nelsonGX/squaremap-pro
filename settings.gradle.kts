pluginManagement {
  repositories {
    maven("https://maven.fabricmc.net/")
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "squaremap-pro"

include("nav-core")
include("nav-fabric")
