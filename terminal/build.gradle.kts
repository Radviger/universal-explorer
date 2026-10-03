plugins {
    // TerminalView exposes swing/JediTerm types.
    id("java-library")
}

dependencies {
    api(project(":core"))
    api(project(":kit"))

    // The shell channel rides the SSH session of an SFTP connection.
    implementation(project(":sftp"))
    implementation(libs.sshd.core)

    implementation(libs.jediterm.ui)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.typeahead)

    // The local shell spawn pokes Windows consoles.
    implementation(libs.jna.platform)
}
