plugins {
  id("fabric-loom") version "1.13.6"
}

base {
  archivesName = "squaremap-pro"
}

dependencies {
  minecraft("com.mojang:minecraft:1.21.11")
  mappings(loom.officialMojangMappings())
  modImplementation("net.fabricmc:fabric-loader:0.18.4")
  modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")

  compileOnly("xyz.jpenilla:squaremap-api:1.3.12")

  implementation(project(":nav-core"))
  include(project(":nav-core"))

  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation(testFixtures(project(":nav-core")))
}

tasks.processResources {
  val version = project.version.toString()
  inputs.property("version", version)
  filesMatching("fabric.mod.json") {
    expand("version" to version)
  }
}

tasks.test {
  useJUnitPlatform()
}
