group = "com.filestudio"
version = "0.1.0"

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
        tasks.withType<JavaCompile> {
            options.encoding = "UTF-8"
        }
        tasks.withType<Test> {
            useJUnitPlatform()
            maxParallelForks = 1
            maxHeapSize = "512m"
        }
        tasks.withType<Javadoc> {
            options.encoding = "UTF-8"
        }
    }
}
