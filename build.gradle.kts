import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "cx.tfe"
version = "1.0"

repositories {
    mavenCentral()
}

tasks.withType<JavaExec> { jvmArgs("--enable-native-access=ALL-UNNAMED") }

dependencies {
    implementation("org.jocl:jocl:2.0.5")
    implementation("it.unimi.dsi:fastutil:8.5.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "analyzer.MainKt"
    }
}

// One jar with every dependency inside (JOCL's native library, fastutil, coroutines).
tasks.shadowJar {
    archiveFileName.set("seedfinder-shadow.jar")
    manifest {
        attributes["Main-Class"] = "analyzer.MainKt"
    }
    mergeServiceFiles()   // coroutines registers itself through service files
}

// ---------------------------------------------------------------------------
// Self-contained Linux bundle: a trimmed Java runtime + the jar + a launcher.
//
//   ./gradlew bundleRun      -> build/distributions/seedfinder.run  (one file; see below)
//   ./gradlew bundleTar      -> build/distributions/seedfinder-1.0-linux-x64.tar.gz
//   ./gradlew bundle         -> the same thing unpacked, in build/bundle/seedfinder
//
// On the target machine:
//   tar xzf seedfinder-1.0-linux-x64.tar.gz
//   ./seedfinder/bin/seedfinder --chunk 64m
//
// Nothing needs installing except the GPU's OpenCL driver (ROCm). The runtime is built
// for the OS and CPU of the machine running Gradle, so build it on x86-64 Linux.
// ---------------------------------------------------------------------------

val appName = "seedfinder"

/**
 * Java modules the program uses. java.logging is for JOCL; jdk.unsupported is a small
 * safety net for libraries that touch sun.misc.Unsafe. If a run ever fails with
 * NoClassDefFoundError for a java.* or javax.* class, run ./gradlew printModuleDeps and
 * add what it lists here.
 */
val runtimeModules = listOf("java.base", "java.logging", "jdk.unsupported")

/** The same JDK the project compiles with, so jlink matches the bytecode. */
val toolchainHome = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}.map { it.metadata.installationPath }

val runtimeDir = layout.buildDirectory.dir("jlink/runtime")
val launcherFile = layout.buildDirectory.file("launcher/$appName")

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "Builds a trimmed Java runtime with only the modules the program needs."
    inputs.property("modules", runtimeModules)
    inputs.property("jdk", toolchainHome.map { it.asFile.absolutePath })
    outputs.dir(runtimeDir)
    val out = runtimeDir.map { it.asFile }
    val jdk = toolchainHome
    doFirst {
        out.get().deleteRecursively()   // jlink refuses to write into an existing directory
        executable = jdk.get().file("bin/jlink").asFile.absolutePath
    }
    args(
        "--add-modules", runtimeModules.joinToString(","),
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress", "zip-6",
        "--output", out.get().absolutePath,
    )
}

val launcherScript = tasks.register("launcherScript") {
    group = "distribution"
    description = "Writes the bin/seedfinder launcher script."
    val out = launcherFile
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            |#!/bin/sh
            |# Runs the seed finder on the Java runtime bundled next to it; no system Java needed.
            |# Extra JVM options can go in JAVA_OPTS, e.g. JAVA_OPTS="-Xmx8g" ./bin/seedfinder
            |APP_HOME="${'$'}(cd "${'$'}(dirname "${'$'}0")/.." && pwd)"
            |exec "${'$'}APP_HOME/runtime/bin/java" --enable-native-access=ALL-UNNAMED ${'$'}JAVA_OPTS \
            |    -jar "${'$'}APP_HOME/lib/$appName.jar" "${'$'}@"
            |""".trimMargin()
        )
        f.setExecutable(true, false)
    }
}

val bundle = tasks.register<Sync>("bundle") {
    group = "distribution"
    description = "Assembles the runnable bundle in build/bundle/$appName."
    into(layout.buildDirectory.dir("bundle/$appName"))
    from(jlinkRuntime) { into("runtime") }
    from(tasks.shadowJar) { into("lib") }
    from(launcherScript) { into("bin") }
    filesMatching(listOf("bin/*", "runtime/bin/*")) {
        permissions { unix("rwxr-xr-x") }
    }
}

val bundleTar = tasks.register<Tar>("bundleTar") {
    group = "distribution"
    description = "Packs the bundle as build/distributions/$appName-<version>-linux-x64.tar.gz."
    archiveFileName.set("$appName-$version-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    from(bundle) { into(appName) }
    filesMatching(listOf("$appName/bin/*", "$appName/runtime/bin/*")) {
        permissions { unix("rwxr-xr-x") }
    }
}

/**
 * One self-extracting file: packaging/run-header.sh followed by the tarball.
 *
 *   ./gradlew bundleRun      -> build/distributions/seedfinder.run
 *
 * Drag it onto the VM and run `sh seedfinder.run [flags]`. Starting it with `sh` means it
 * works even when the upload strips the executable bit. The build id stamped into the
 * header is a hash of the payload, so a new build unpacks fresh and an unchanged one
 * reuses the copy already unpacked.
 */
tasks.register("bundleRun") {
    group = "distribution"
    description = "Builds build/distributions/$appName.run, a single self-extracting file."
    val tar = bundleTar.flatMap { it.archiveFile }
    val header = layout.projectDirectory.file("packaging/run-header.sh")
    val out = layout.buildDirectory.file("distributions/$appName.run")
    inputs.file(tar)
    inputs.file(header)
    outputs.file(out)
    doLast {
        val payload = tar.get().asFile.readBytes()
        val id = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }.take(12)
        val head = header.asFile.readText().replace("@BUILD_ID@", id)
        val f = out.get().asFile
        f.outputStream().use { it.write(head.toByteArray()); it.write(payload) }
        f.setExecutable(true, false)
    }
}

/** Lists the Java modules the jar actually needs, if runtimeModules ever falls short. */
tasks.register<Exec>("printModuleDeps") {
    group = "distribution"
    description = "Prints the JDK modules the fat jar depends on (via jdeps)."
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    val jdk = toolchainHome
    inputs.file(jar)
    doFirst {
        executable = jdk.get().file("bin/jdeps").asFile.absolutePath
        args("--print-module-deps", "--ignore-missing-deps", "--multi-release", "25",
            jar.get().asFile.absolutePath)
    }
}