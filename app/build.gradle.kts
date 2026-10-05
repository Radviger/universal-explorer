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

/** Renders the app mark to a multi-size PNG-in-ICO (+ PNGs) in build/icon. */
tasks.register<JavaExec>("icon") {
    group = "build"
    description = "Renders the application icon (dock.ico + PNGs) into build/icon."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dock.kit.AppIcon")
    args("build/icon")
}
