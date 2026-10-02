plugins {
    `java-library`
}

description = "FileStudio core engine (Java 21): file parsing, plugin management, document model"

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Run the FileStudio core engine smoke demo"
    mainClass.set("com.filestudio.core.FileStudioMain")
    classpath = sourceSets.main.get().runtimeClasspath
    if (project.hasProperty("file")) {
        args = listOf(project.property("file").toString())
    }
}
