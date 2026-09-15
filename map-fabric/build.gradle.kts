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

  // Permission checks (`squaremappro.edit`). 0.6.1 is the last release for Minecraft 1.21.11
  // (its fabric.mod.json: "minecraft": ">=1.21.11"; 0.7.0 requires ">=26.1"). Maven Central,
  // https://github.com/lucko/fabric-permissions-api/releases/tag/v0.6.1. Tiny, so nested.
  modImplementation("me.lucko:fabric-permissions-api:0.6.1")
  include("me.lucko:fabric-permissions-api:0.6.1")

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

  // The squaremap marker mapping is unit-tested against the real API classes.
  testImplementation("xyz.jpenilla:squaremap-api:1.3.12")
  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- web bundle (PLAN task 10) ------------------------------------------------------------------
// `./gradlew build` runs `npm ci` (when node_modules is missing or package-lock.json changed) and
// `npm run build` (Next static export) in ../web, then packs web/out/** into the jar under `web/`,
// where MapHttpServer serves it. `-PskipWeb` skips all of it: the jar then has no bundle and the
// server answers "web bundle missing".
val skipWeb = providers.gradleProperty("skipWeb").isPresent
val webDir = rootProject.layout.projectDirectory.dir("web")
val webOut = webDir.dir("out")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmCi by tasks.registering(Exec::class) {
  group = "web"
  description = "Installs web dependencies with npm ci when package-lock.json changed."
  enabled = !skipWeb
  workingDir = webDir.asFile
  commandLine(npmExecutable, "ci", "--no-audit", "--no-fund")
  inputs.file(webDir.file("package.json")).withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.file(webDir.file("package-lock.json")).withPathSensitivity(PathSensitivity.RELATIVE)
  // npm writes node_modules/.package-lock.json on install; tracking it (not all of node_modules)
  // keeps the up-to-date check cheap and re-runs `npm ci` when node_modules is deleted.
  outputs.file(webDir.file("node_modules/.package-lock.json"))
}

val webBuild by tasks.registering(Exec::class) {
  group = "web"
  description = "Builds the static Next.js export into web/out."
  enabled = !skipWeb
  dependsOn(npmCi)
  workingDir = webDir.asFile
  commandLine(npmExecutable, "run", "build")
  // Production bundle is same-origin and never uses the fixture API, whatever .env files say
  // (process environment overrides .env files in Next.js).
  environment("NEXT_PUBLIC_USE_FIXTURES", "0")
  environment("NEXT_PUBLIC_API_BASE", "")
  environment("NEXT_PUBLIC_TILES_BASE", "")
  environment("NEXT_TELEMETRY_DISABLED", "1")
  inputs.files(fileTree(webDir) {
    include("app/**", "components/**", "lib/**", "public/**")
    include("package.json", "package-lock.json", "next.config.*", "tsconfig.json", ".env", ".env.production")
    exclude("**/*.test.ts", "**/*.test.tsx")
  }).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(webOut)
  doLast {
    val index = webOut.file("index.html").asFile
    check(index.isFile) { "web build did not produce ${index}" }
    val fixtureMarker = "Fixture data (NEXT_PUBLIC_USE_FIXTURES=1)"
    val leaked = webOut.asFileTree.matching { include("**/*.js", "**/*.html") }
      .filter { it.readText().contains(fixtureMarker) }
    check(leaked.isEmpty) { "fixture mode leaked into the production web bundle: ${leaked.files}" }
  }
}

tasks.processResources {
  val version = project.version.toString()
  inputs.property("version", version)
  filesMatching("fabric.mod.json") {
    expand("version" to version)
  }
  if (!skipWeb) {
    dependsOn(webBuild)
    from(webOut) {
      into("web")
    }
  }
}

tasks.test {
  useJUnitPlatform()
}

