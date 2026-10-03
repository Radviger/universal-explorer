// Streaming media player: a localhost HTTP range bridge over the filesystem's
// positioned-read primitive (so playback never downloads more than it plays)
// and the in-pane player stand-in behind the MediaEngine seam. vlcj is
// confined to this module — the engine swap, if one ever comes, touches
// only VlcEngine.
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))

    implementation(libs.vlcj)
    // vlcj's native discovery probes the Windows registry for VLC installs
    // through JNA's platform bindings at engine construction.
    implementation(libs.jna.platform)

    // Bridge tests drive a live in-process SFTP server to prove ranged
    // reads end-to-end over a real seek backend.
    testImplementation(project(":sftp"))
    testImplementation(libs.sshd.core)
}
