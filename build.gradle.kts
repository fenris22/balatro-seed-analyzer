plugins {
    kotlin("jvm") version "2.4.10"
    id("me.champeau.jmh") version "0.7.2"
    kotlin("kapt") version "2.4.10"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    implementation("it.unimi.dsi:fastutil:8.5.13")
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

jmh {
    //profilers.set(listOf("async:libPath=/home/fenris/async-profiler-3.0-linux-x64/lib/libasyncProfiler.so;output=flamegraph;event=cpu"))
    profilers.set(listOf("stack", "gc"))
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
}