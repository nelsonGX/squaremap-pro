subprojects {
  group = rootProject.group
  version = rootProject.version

  repositories {
    mavenCentral()
  }

  plugins.withType<JavaPlugin> {
    extensions.configure<JavaPluginExtension> {
      toolchain.languageVersion = JavaLanguageVersion.of(21)
    }
    tasks.withType<JavaCompile>().configureEach {
      options.encoding = "UTF-8"
      options.release = 21
    }
  }
}
