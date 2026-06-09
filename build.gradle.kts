plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
    id("com.github.spotbugs") version "6.5.0"
}

qupathExtension {
    name = "qupath-extension-project-metadata-browser"
    group = "io.github.michaelsnelson"
    version = "1.0.0"
    description = "Browse, filter, and edit metadata for all images in a QuPath project."
    automaticModule = "io.github.michaelsnelson.extension.projectmetadatabrowser"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    testImplementation(libs.bundles.qupath)
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation(libs.bundles.logging)
    // WorkingCopy + UndoStack expose ObservableList / ReadOnly*Property,
    // so tests that touch them need the JavaFX modules on the compile path.
    testImplementation("org.openjfx:javafx-base:17.0.2")
    testImplementation("org.openjfx:javafx-graphics:17.0.2")
    testImplementation("org.openjfx:javafx-controls:17.0.2")
}

tasks.withType<JavaCompile> {
    options.release.set(21) // QuPath 0.7 runs on Java 21; pin bytecode target so any build JDK emits loadable classes
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// SpotBugs -- static bug detection (gates the build)
// ---------------------------------------------------------------------------
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}
