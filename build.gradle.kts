plugins {
    kotlin("jvm") version "2.3.10"
    application
    antlr
}

group = "io.github.jwyoon1220"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    // Source: https://mvnrepository.com/artifact/it.unimi.dsi/fastutil
    implementation("it.unimi.dsi:fastutil:8.5.18")
    // Source: https://mvnrepository.com/artifact/org.ow2.asm/asm
    implementation("org.ow2.asm:asm:9.7")
    // Source: https://mvnrepository.com/artifact/uk.co.caprica/vlcj
    implementation("uk.co.caprica:vlcj:4.8.2")
    // Source: https://mvnrepository.com/artifact/org.apache.commons/commons-collections4
    implementation("org.apache.commons:commons-collections4:4.4")
    // Source: https://mvnrepository.com/artifact/com.google.guava/guava
    implementation("com.google.guava:guava:33.5.0-jre")
    // Cross-platform JavaScript engine fallback (JDK-21 compatible, no GraalVM required)
    // Source: https://mvnrepository.com/artifact/org.openjdk.nashorn/nashorn-core
    implementation("org.openjdk.nashorn:nashorn-core:15.6")

    // ANTLR 4
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
}

tasks.generateGrammarSource {
    maxHeapSize = "128m"
    arguments = listOf("-visitor", "-listener")
}

tasks.register<Exec>("buildNativeDLL") {
    workingDir = rootDir
    val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
    if (isWin) {
        commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", "${rootDir}/build_native.ps1")
    } else {
        commandLine("bash", "-c", "${rootDir}/build_native.sh")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(tasks.generateGrammarSource)
    dependsOn("buildNativeDLL")
}

tasks.named<JavaExec>("run") {
    jvmArgs("-XX:+UseZGC", "-Xmx512m")
    systemProperty("java.library.path", file("build/native").absolutePath)
}

application {
    mainClass.set("io.github.jwyoon1220.khromium.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}