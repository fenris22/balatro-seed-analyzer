import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "cx.tfe"
// Set by the release workflow from the git tag (v1.2 -> 1.2). Locally: ./gradlew release -PappVersion=1.2
version = providers.gradleProperty("appVersion").getOrElse("1.0")

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

// One jar with every dependency inside. The JOCL jar already carries its native library
// for both Linux and Windows, so the same jar works on either.
tasks.shadowJar {
    archiveFileName.set("seedfinder.jar")
    manifest {
        attributes["Main-Class"] = "analyzer.MainKt"
    }
    mergeServiceFiles()   // coroutines registers itself through service files
}

// ---------------------------------------------------------------------------
// Self-contained bundle: a trimmed Java runtime + the jar + a launcher.
//
// The runtime is built for the OS of the machine running Gradle, so the Linux files are
// built on Linux and the Windows files on Windows (the GitHub workflow does both).
//
//   ./gradlew release   -> build/release/ with everything for this OS:
//        Linux:   seedfinder-<ver>-linux-x64.run      (one self-extracting file)
//                 seedfinder-<ver>-linux-x64.tar.gz
//        Windows: seedfinder-<ver>-windows-x64.zip    (unzip, run seedfinder.bat)
//
// Nothing needs installing on the target except the GPU's OpenCL driver.
// ---------------------------------------------------------------------------

val appName = "seedfinder"
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val platform = if (isWindows) "windows-x64" else "linux-x64"
val exe = if (isWindows) ".exe" else ""

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
val launcherDir = layout.buildDirectory.dir("launcher")

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
        executable = jdk.get().file("bin/jlink$exe").asFile.absolutePath
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

/**
 * Linux: bin/seedfinder (sh). Windows: seedfinder.bat at the top of the folder, so it is
 * the obvious thing to double-click. With no arguments the .bat waits for a key at the
 * end, so a double-clicked window stays open long enough to read the results.
 */
val launcherScript = tasks.register("launcherScript") {
    group = "distribution"
    description = "Writes the launcher for this OS."
    val out = launcherDir
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        if (isWindows) {
            val f = File(dir, "$appName.bat")
            f.parentFile.mkdirs()
            val lines = listOf(
                "@echo off",
                "rem Runs the seed finder on the Java runtime bundled next to it; no system Java needed.",
                "rem Extra JVM options can go in JAVA_OPTS, e.g.  set JAVA_OPTS=-Xmx8g",
                "setlocal",
                "set \"APP_HOME=%~dp0\"",
                "\"%APP_HOME%runtime\\bin\\java.exe\" --enable-native-access=ALL-UNNAMED %JAVA_OPTS% " +
                        "-jar \"%APP_HOME%lib\\$appName.jar\" %*",
                "set \"RC=%ERRORLEVEL%\"",
                "if \"%~1\"==\"\" pause",
                "exit /b %RC%",
            )
            f.writeText(lines.joinToString("\r\n", postfix = "\r\n"))   // cmd wants CRLF
        } else {
            val f = File(dir, "bin/$appName")
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
}

val bundle = tasks.register<Sync>("bundle") {
    group = "distribution"
    description = "Assembles the runnable bundle in build/bundle/$appName."
    into(layout.buildDirectory.dir("bundle/$appName"))
    from(jlinkRuntime) { into("runtime") }
    from(tasks.shadowJar) { into("lib") }
    from(launcherScript)
    filesMatching(listOf("bin/*", "runtime/bin/*")) {
        permissions { unix("rwxr-xr-x") }
    }
}

val distDir = layout.buildDirectory.dir("distributions")

val bundleTar = tasks.register<Tar>("bundleTar") {
    group = "distribution"
    description = "Packs the bundle as a .tar.gz (Linux)."
    archiveFileName.set("$appName-$version-linux-x64.tar.gz")
    destinationDirectory.set(distDir)
    compression = Compression.GZIP
    from(bundle) { into(appName) }
    filesMatching(listOf("$appName/bin/*", "$appName/runtime/bin/*")) {
        permissions { unix("rwxr-xr-x") }
    }
}

val bundleZip = tasks.register<Zip>("bundleZip") {
    group = "distribution"
    description = "Packs the bundle as a .zip (Windows)."
    archiveFileName.set("$appName-$version-windows-x64.zip")
    destinationDirectory.set(distDir)
    from(bundle) { into(appName) }
}

/**
 * One self-extracting file: packaging/run-header.sh followed by the tarball.
 * Run it with `sh seedfinder-<ver>-linux-x64.run [flags]`; starting it with `sh` works
 * even when a download or upload strips the executable bit. The build id stamped into the
 * header is a hash of the payload, so a new build unpacks fresh and an unchanged one
 * reuses the copy already unpacked.
 */
val bundleRun = tasks.register("bundleRun") {
    group = "distribution"
    description = "Builds a single self-extracting .run file (Linux)."
    val tar = bundleTar.flatMap { it.archiveFile }
    val header = layout.projectDirectory.file("packaging/run-header.sh")
    val out = distDir.map { it.file("$appName-$version-linux-x64.run") }
    inputs.file(tar)
    inputs.file(header)
    outputs.file(out)
    doLast {
        val payload = tar.get().asFile.readBytes()
        val id = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }.take(12)
        // Strip any CR so the header still works if the repo was checked out with CRLF.
        val head = header.asFile.readText().replace("\r", "").replace("@BUILD_ID@", id)
        val f = out.get().asFile
        f.outputStream().use { it.write(head.toByteArray()); it.write(payload) }
        f.setExecutable(true, false)
    }
}

/** Everything a release needs for this OS, collected in build/release/. */
tasks.register<Sync>("release") {
    group = "distribution"
    description = "Builds the release files for this OS into build/release/."
    into(layout.buildDirectory.dir("release"))
    if (isWindows) {
        from(bundleZip)
    } else {
        from(bundleRun)
        from(bundleTar)
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
        executable = jdk.get().file("bin/jdeps$exe").asFile.absolutePath
        args("--print-module-deps", "--ignore-missing-deps", "--multi-release", "25",
            jar.get().asFile.absolutePath)
    }
}