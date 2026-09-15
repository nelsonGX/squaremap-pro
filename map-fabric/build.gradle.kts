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

  implementation(project(":map-core"))
  include(project(":map-core"))
  // map-core's FeatureStore needs the SQLite JDBC driver at runtime; nest it in the mod jar.
  implementation("org.xerial:sqlite-jdbc:3.53.4.0")

  // HTTP server. Javalin 7.2.3 (Java 17 bytecode) on Jetty 12.1.12 + kotlin-stdlib.
  // slf4j-api is provided by Minecraft (org.slf4j:slf4j-api:2.0.17) and ASM by Fabric Loader
  // (org.ow2.asm:*:9.9); exclude both so dev/test classpaths match production and the loader's copies
  // are the only ones. Loom `include` is not transitive: every other runtime dependency is listed.
  implementation("io.javalin:javalin:7.2.3") {
    exclude(group = "org.slf4j")
    exclude(group = "org.ow2.asm")
  }
  listOf(
    "io.javalin:javalin:7.2.3",
    "org.jetbrains.kotlin:kotlin-stdlib:2.2.20",
    "org.jetbrains:annotations:13.0",
    "org.eclipse.jetty:jetty-server:12.1.12",
    "org.eclipse.jetty:jetty-http:12.1.12",
    "org.eclipse.jetty:jetty-io:12.1.12",
    "org.eclipse.jetty:jetty-util:12.1.12",
    "org.eclipse.jetty:jetty-security:12.1.12",
    "org.eclipse.jetty:jetty-session:12.1.12",
    "org.eclipse.jetty:jetty-annotations:12.1.12",
    "org.eclipse.jetty:jetty-jndi:12.1.12",
    "org.eclipse.jetty:jetty-plus:12.1.12",
    "org.eclipse.jetty:jetty-xml:12.1.12",
    "org.eclipse.jetty.ee:jetty-ee-webapp:12.1.12",
    "org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.12",
    "org.eclipse.jetty.ee10:jetty-ee10-annotations:12.1.12",
    "org.eclipse.jetty.ee10:jetty-ee10-plus:12.1.12",
    "org.eclipse.jetty.ee10:jetty-ee10-webapp:12.1.12",
    "org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-jetty-server:12.1.12",
    "org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-servlet:12.1.12",
    "org.eclipse.jetty.websocket:jetty-websocket-core-common:12.1.12",
    "org.eclipse.jetty.websocket:jetty-websocket-core-server:12.1.12",
    "org.eclipse.jetty.websocket:jetty-websocket-jetty-api:12.1.12",
    "org.eclipse.jetty.websocket:jetty-websocket-jetty-common:12.1.12",
    "org.eclipse.jetty.websocket:jetty-websocket-jetty-server:12.1.12",
    "jakarta.servlet:jakarta.servlet-api:6.0.0",
    "jakarta.annotation:jakarta.annotation-api:2.1.1",
    "jakarta.enterprise:jakarta.enterprise.cdi-api:4.0.1",
    "jakarta.enterprise:jakarta.enterprise.lang-model:4.0.1",
    "jakarta.inject:jakarta.inject-api:2.0.1",
    "jakarta.interceptor:jakarta.interceptor-api:2.1.0",
    "jakarta.transaction:jakarta.transaction-api:2.0.1",
  ).forEach { include(it) }
  include("org.xerial:sqlite-jdbc:3.53.4.0")

  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

