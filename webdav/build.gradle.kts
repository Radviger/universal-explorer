// WebDAV backend (PROPFIND/MKCOL/MOVE over HTTP) on Apache HttpClient.
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))

    implementation(libs.sardine)
}
