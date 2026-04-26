plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
    id("com.github.spotbugs") version "6.5.0"
}

qupathExtension {
    name = "qupath-extension-project-metadata-browser"
    group = "io.github.michaelsnelson"
    version = "0.1.1"
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
}

tasks.withType<JavaCompile> {
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
