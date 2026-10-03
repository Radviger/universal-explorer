// Headless foundations: the FileSystem/Session contracts, persisted
// config, the OS credential store, and the transfer engine. No UI, no
// protocol libraries — backends and frontends hang off this module.
dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jna.platform)

    // The transfer engine's tests run real SFTP transfers against the
    // in-process demo server. Test scope only — no build cycle.
    testImplementation(project(":sftp"))
    testImplementation(libs.sshd.core)
}
