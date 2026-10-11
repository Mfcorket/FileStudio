plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

description = "FileStudio desktop application (Kotlin + Compose Multiplatform)"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core-java21"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.runtime)

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 声明主类后，插件才会注册 run / packageDistribution 等任务
compose.desktop {
    application {
        mainClass = "com.filestudio.desktop.MainKt"

        nativeDistributions {
            packageName = "FileStudio"
            packageVersion = project.version.toString()
            description = "FileStudio — cross-platform file viewer & editor"
            modules("java.instrument", "java.management", "jdk.unsupported")

            windows { menu = true }
            macOS { bundleID = "com.filestudio.desktop" }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.test {
    useJUnitPlatform()
    // 纯逻辑测试，不启动窗口，因此不需要图形环境
    systemProperty("java.awt.headless", "true")
}

// 说明：Compose Desktop 插件已内置 packageDistribution / packageDeb / packageDmg 等
// 任务，这里不再重复注册，避免与插件提供的同名任务冲突。