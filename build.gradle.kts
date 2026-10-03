// Root: shared conventions for every module. Each module's own build
// script declares only its dependencies and module-specific tasks.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {
    apply(plugin = "java")

    group = "dev.universalexplorer"
    version = "0.1.0"

    repositories {
        mavenCentral()
        // JediTerm lives on JetBrains' artifact repository, not Maven
        // Central. Declared once here: repositories resolve in the
        // consuming project, so the terminal module's consumers need it
        // even though only terminal declares the dependency.
        maven("https://cache-redirector.jetbrains.com/packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }

    configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // Sources contain literal em-dashes/ellipses in UI strings; the
        // platform default (Cp1251 on a Russian Windows) mangles them.
        options.encoding = "UTF-8"
    }

    dependencies {
        "testImplementation"(catalog.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
    }

    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
