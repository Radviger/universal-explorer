// Image viewer: decode-and-display, swapped into a commander pane in place
// of the file table. The decoder dependencies are confined to this module —
// the commander mounts the panel directly, nothing else sees them.
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))

    // image4j's only Central home is a republish (net.ifok.image); the
    // packages inside stay net.sf.image4j.
    implementation(libs.imageio.webp)
    implementation(libs.imageio.psd)
    implementation(libs.imageio.jpeg)
    implementation(libs.image4j)
    implementation(libs.svg.salamander)
}
