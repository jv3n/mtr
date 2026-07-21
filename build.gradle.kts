plugins {
    // Kotlin 2.3.0: supports Java 25 (IntelliJ JBR) and Gradle 9.x.
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("com.diffplug.spotless") version "7.0.2"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    val ktor = "3.0.1"
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-websockets:$ktor")

    // .env loader (local dev); prod injects real env vars.
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    // Tiny SLF4J backend for Ktor's logging (avoids logback's logback.xml CVEs).
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.5.0")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.5.0")
    }
}

application {
    // Default entry point; override with -PmainClass=... via run tasks below.
    mainClass.set("mtr.MainKt")
}

// Convenience tasks to run the paper scripts.
tasks.register<JavaExec>("smoke") {
    group = "mtr"
    description = "Read-only connector smoke test (-Psym=AAPL)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mtr.scripts.SmokeTestKt")
    doFirst { args = listOfNotNull(project.findProperty("sym")?.toString()) }
}

tasks.register<JavaExec>("order") {
    group = "mtr"
    description = "PAPER: place one short order (-Psym=SHPH -Pqty=10)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mtr.scripts.OrderTestKt")
    doFirst {
        args =
            listOf(
                project.findProperty("sym")?.toString() ?: "SHPH",
                project.findProperty("qty")?.toString() ?: "10",
            )
    }
}

tasks.register<JavaExec>("cover") {
    group = "mtr"
    description = "PAPER: cover/close a position (-Psym=SHPH)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mtr.scripts.CoverKt")
    doFirst { args = listOf(project.findProperty("sym")?.toString() ?: "SHPH") }
}

tasks.test {
    useJUnitPlatform()
}
