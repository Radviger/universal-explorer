// S3 backend: hand-rolled AWS SigV4 over the JDK's own HttpClient —
// no SDK rides along; all signing lives in SigV4 and is pinned by the
// published AWS examples in SigV4Test.
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))
}
