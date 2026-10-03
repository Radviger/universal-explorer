// The dual-pane file commander: the default tab content for sessions.
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))
    // Enter-on-archive mounts the archive viewer's virtual filesystem.
    implementation(project(":archive"))
    // Enter/F3 on an image swaps the pane for the in-pane viewer.
    implementation(project(":viewer"))
    // Enter/F3 on a markdown file swaps the pane for the rendered reader.
    implementation(project(":markdown"))
    // F4 swaps the pane for the in-pane text editor.
    implementation(project(":editor"))
    // Enter/F3 on media swaps the pane for the streaming player.
    implementation(project(":media"))

    // The local drive list reads known-folder GUIDs from the registry.
    implementation(libs.jna.platform)

    // Explorer/hidden-files tests browse a live in-process SFTP server.
    testImplementation(project(":sftp"))
}
