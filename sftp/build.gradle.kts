// SFTP backend: MINA SSHD client, known-hosts verifier, the Windows
// OpenSSH agent bridge, and the in-process demo server used by --demo
// and the integration tests of every module above core.
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))

    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)
    // EdDSA leg for sshd-common: without it, MINA cannot encode/decode
    // ed25519 keys — the shape every YubiKey offers via the SSH agent.
    implementation(libs.eddsa)
    // The agent bridge talks to \\.\pipe\openssh-ssh-agent.
    implementation(libs.jna.platform)
}
