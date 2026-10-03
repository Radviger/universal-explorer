// Archive viewer: read-only virtual filesystems over archive files that
// live on any backend. Commons Compress is confined to this module — the
// commander mounts ArchiveFs directly, nothing else sees the dependency.
dependencies {
    implementation(project(":core"))

    implementation(libs.commons.compress)
    // Commons Compress's XZ streams delegate to XZ for Java at runtime.
    implementation(libs.xz)
}
