plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-project-metadata-browser"
    group = "io.github.michaelsnelson"
    version = "0.1.0-SNAPSHOT"
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
