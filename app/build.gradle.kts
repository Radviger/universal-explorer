import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    java
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))

    // Production shell paths speak only the SPIs; every backend and
    // frontend rides in as a ServiceLoader discovery.
    runtimeOnly(project(":smb"))
    runtimeOnly(project(":webdav"))
    runtimeOnly(project(":ftp"))
    runtimeOnly(project(":s3"))
    runtimeOnly(project(":commander"))
    runtimeOnly(project(":terminal"))

    // The compile-time exceptions, all dev scaffolding: the
    // --demo/--screenshot/--selftest launchers drive the in-process demo
    // server, its SFTP client, the commander's panes (the screenshot
    // self-checks count FilePanes and cast PathBars), the image viewer
    // (the viewer shot casts its panel), the markdown reader (the md shot
    // walks its document), and the terminal's diagnostics helper directly.
    implementation(project(":sftp"))
    implementation(project(":commander"))
    implementation(project(":viewer"))
    implementation(project(":markdown"))
    // The editor shot drives the in-pane editor directly.
    implementation(project(":editor"))
    // The video shot drives the in-pane player directly.
    implementation(project(":media"))
    // The code card's public accessors expose the syntax module's span
    // types; the md shot's highlight check reads them.
    implementation(project(":syntax"))
    implementation(project(":terminal"))
    implementation(libs.sshd.core)

    implementation(libs.jna.platform)
    runtimeOnly(libs.slf4j.nop)

    // Dialog/launcher tests assert against the concrete spec shapes and
    // drive the shell's session tabs.
    testImplementation(project(":smb"))
    testImplementation(project(":s3"))
    testImplementation(project(":webdav"))
    testImplementation(project(":ftp"))
    testImplementation(project(":terminal"))
}

application {
    mainClass = "dock.DockApp"
}

// The About box shows the version read from the manifest; a direct IDE
// run has no jar and shows none.
tasks.named<Jar>("jar") {
    manifest { attributes("Implementation-Version" to project.version) }
}

// The bundled LibVLC runtime (batteries included: no external player
// required). The official VideoLAN build for this OS, pinned by URL and
// SHA-256 — never "latest" — unpacked into app/vlc/ (git-ignored) in the
// layout VlcRuntime looks for. Linux has no official binary build: there
// the player uses the distro's libvlc and this task does nothing. Tests and
// the screenshot harness run against the engine seam's fakes and never
// need it.
val vlcVersion = "3.0.24"
val vlcDir = layout.projectDirectory.dir("vlc")
val hostOs = System.getProperty("os.name").lowercase()
val isWindowsHost = hostOs.startsWith("windows")
val isMacHost = hostOs.startsWith("mac")

/** One pinned official build: where it lives and the file proving it unpacked. */
data class VlcBuild(val url: String, val sha256: String, val marker: String)

val vlcBuild: VlcBuild? = when {
    isWindowsHost -> VlcBuild(
            "https://download.videolan.org/pub/videolan/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip",
            "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323",
            "libvlc.dll")
    isMacHost && System.getProperty("os.arch") == "aarch64" -> VlcBuild(
            "https://download.videolan.org/pub/videolan/vlc/$vlcVersion/macosx/vlc-$vlcVersion-arm64.dmg",
            "64a89d93cdd30b0e97131743e246373db82d6826ea882d265d46d74b136da2b7",
            "lib/libvlc.dylib")
    isMacHost -> VlcBuild(
            "https://download.videolan.org/pub/videolan/vlc/$vlcVersion/macosx/vlc-$vlcVersion-intel64.dmg",
            "1ef6c903e2dc026d4e58ddf7b2a9ed0eb6f8caf80a9a9f46f96490340b962707",
            "lib/libvlc.dylib")
    else -> null
}

tasks.register("downloadVlc") {
    group = "build"
    description = "Downloads and unpacks the pinned LibVLC runtime into app/vlc (batteries included)."
    val build = vlcBuild
    if (build == null) {
        doLast { logger.lifecycle("No bundled LibVLC for this OS — media playback uses the system's libvlc (e.g. the 'vlc' package).") }
        return@register
    }
    outputs.file(vlcDir.file(build.marker))
    outputs.upToDateWhen { vlcDir.file(build.marker).asFile.exists() }
    doLast {
        val target = File(layout.buildDirectory.asFile.get(), "vlc/" + build.url.substringAfterLast('/'))
        target.parentFile.mkdirs()
        val sha256 = MessageDigest.getInstance("SHA-256")
        fun digest(file: File) =
                sha256.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (!target.exists() || digest(target) != build.sha256) {
            logger.lifecycle("Downloading LibVLC $vlcVersion (~60-80 MB, once)…")
            URI(build.url).toURL().openStream().use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(digest(target) == build.sha256) { "VLC download digest mismatch: ${digest(target)}" }
        }
        val root = vlcDir.asFile
        root.mkdirs()
        if (isWindowsHost) unpackWindowsZip(target, root) else unpackMacDmg(target, root)
        check(File(root, build.marker).isFile) {
            "the VLC download unpacked without ${build.marker} — layout changed?"
        }
    }
}

/** The win64 zip: libvlc + libvlccore + the plugin tree + the license
 *  texts that must ship with it, flattened into app/vlc. */
fun unpackWindowsZip(zipFile: File, root: File) {
    val keepFiles = setOf("libvlc.dll", "libvlccore.dll",
            "COPYING.txt", "AUTHORS.txt", "THANKS.txt", "NEWS.txt", "README.txt")
    val keepDirs = setOf("plugins")
    val rootPath = root.canonicalFile.toPath()
    ZipFile(zipFile).use { zip ->
        // The official zip is single-rooted (vlc-<version>/…); strip
        // that prefix so app/vlc holds the runtime directly.
        val names = zip.entries().toList().map { it.name }
        val zipRoot = names.firstOrNull()?.substringBefore('/') ?: error("empty VLC zip")
        val singleRooted = zipRoot.isNotEmpty()
                && names.all { it.startsWith("$zipRoot/") }
        fun strip(name: String) =
                if (singleRooted && name.startsWith("$zipRoot/"))
                    name.substring(zipRoot.length + 1) else name
        for (entry in zip.entries()) {
            val stripped = strip(entry.name)
            if (stripped.isEmpty()) continue
            val top = stripped.substringBefore('/')
            if (top !in keepFiles && top !in keepDirs) continue
            val out = File(root, stripped)
            // Zip-slip guard: every entry must stay inside app/vlc.
            check(out.canonicalFile.toPath().startsWith(rootPath)) {
                "bad zip entry: ${entry.name}"
            }
            if (entry.isDirectory) out.mkdirs()
            else {
                out.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

/** The macOS dmg: VLC.app/Contents/MacOS/{lib,plugins} copied as-is into
 *  app/vlc (ditto keeps the dylib symlinks), plus VLC's README. */
fun unpackMacDmg(dmg: File, root: File) {
    fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
    }
    val mount = Files.createTempDirectory("vlc-dmg").toFile()
    run("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", mount.path, dmg.path)
    try {
        val macos = File(mount, "VLC.app/Contents/MacOS")
        for (dir in listOf("lib", "plugins")) {
            run("ditto", File(macos, dir).path, File(root, dir).path)
        }
        File(mount, "VLC.app/Contents/Resources/README")
                .copyTo(File(root, "README.txt"), overwrite = true)
    } finally {
        run("hdiutil", "detach", mount.path)
        mount.delete()
    }
}

tasks.named("run") { dependsOn("downloadVlc") }

/** Removes a packaging output, retried: a freshly written
 *  "Universal Explorer.exe" can sit delete-pending in a Windows
 *  defender scan for a moment, and deleteRecursively() reports that
 *  as a silent false — which jpackage then answers with a confusing
 *  "destination directory already exists". */
fun deleteForPackaging(dir: File) {
    if (!dir.isDirectory) return
    repeat(5) {
        if (dir.deleteRecursively() && !dir.exists()) return
        Thread.sleep(1000)
    }
    check(!dir.exists()) { "${dir.path} is locked (antivirus scan of a fresh build?)" }
}

/** Renders the app mark to a multi-size PNG-in-ICO (+ PNGs) in build/icon. */
tasks.register<JavaExec>("icon") {
    group = "build"
    description = "Renders the application icon (dock.ico + PNGs) into build/icon."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dock.kit.AppIcon")
    args("build/icon")
}

/**
 * A self-contained app for this OS in build/package (jpackage): its own
 * Java runtime, the bundled LibVLC beside the jars (VlcRuntime finds it
 * there), and the app icon. macOS gets "Universal Explorer.app", Windows
 * and Linux their app-image folder. Unsigned — fine for the machine that
 * built it.
 */
tasks.register("packageApp") {
    group = "distribution"
    description = "Builds a self-contained app for this OS into build/package."
    dependsOn("jar", "downloadVlc", "icon")
    val runtimeJars = configurations.named("runtimeClasspath")
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
            .map { it.metadata.installationPath.asFile }
    doLast {
        val out = File(layout.buildDirectory.asFile.get(), "package")
        val input = File(out, "input")
        deleteForPackaging(out)
        input.mkdirs()
        fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "${cmd.first()} failed:\n$text" }
        }
        // The app and every library side by side; jpackage puts --input on the class path.
        appJar.get().asFile.copyTo(File(input, appJar.get().asFile.name))
        runtimeJars.get().files.filter { it.name.endsWith(".jar") }
                .forEach { it.copyTo(File(input, it.name), overwrite = true) }
        val vlc = vlcDir.asFile
        if (vlc.isDirectory) {
            // ditto keeps the macOS dylib symlinks intact.
            if (isMacHost) run("ditto", vlc.path, File(input, "vlc").path)
            else vlc.copyRecursively(File(input, "vlc"))
            // jpackage puts every jar under --input on the class path; VLC's
            // Blu-ray menu jars have no business there.
            File(input, "vlc").walkTopDown().filter { it.name.endsWith(".jar") }
                    .forEach { it.delete() }
        }
        val iconDir = File(layout.buildDirectory.asFile.get(), "icon")
        val icon = when {
            isMacHost -> File(iconDir, "dock.icns").also {
                run("iconutil", "-c", "icns", File(iconDir, "dock.iconset").path, "-o", it.path)
            }
            isWindowsHost -> File(iconDir, "dock.ico")
            else -> File(iconDir, "dock-256.png")
        }
        val jpackage = File(jdk.get(), "bin/jpackage" + if (isWindowsHost) ".exe" else "")
        val args = mutableListOf(jpackage.path,
                "--type", "app-image",
                "--name", "Universal Explorer",
                "--dest", out.path,
                "--input", input.path,
                "--main-jar", appJar.get().asFile.name,
                "--main-class", "dock.DockApp",
                "--icon", icon.path,
                "--vendor", "Universal Explorer contributors",
                "--java-options", "--enable-native-access=ALL-UNNAMED",
                "--java-options", "-Dfile.encoding=UTF-8")
        if (isMacHost) {
            // macOS refuses a 0.x bundle version; the About box reads the
            // real one from the jar manifest.
            args += listOf("--mac-package-identifier", "dev.universalexplorer.app")
        } else {
            args += listOf("--app-version", project.version.toString())
        }
        run(*args.toTypedArray())
        input.deleteRecursively()
        logger.lifecycle("Packaged: " + out.listFiles()!!.joinToString { it.path })
    }
}

/** macOS: packages and copies the app into /Applications, replacing an older copy. */
tasks.register("installApp") {
    group = "distribution"
    description = "Packages the app and installs it into /Applications (macOS)."
    dependsOn("packageApp")
    onlyIf { isMacHost }
    doLast {
        val app = File(layout.buildDirectory.asFile.get(), "package/Universal Explorer.app")
        val target = File("/Applications/Universal Explorer.app")
        target.deleteRecursively()
        val p = ProcessBuilder("ditto", app.path, target.path).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "ditto failed:\n$text" }
        logger.lifecycle("Installed: ${target.path}")
    }
}
