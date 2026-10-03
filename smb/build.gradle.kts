// SMB backend: pure-Java SMB2/3 client (guest + domain auth).
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))

    implementation(libs.smbj)
    // smbj's SMB3 signing/encryption leg (pulls bcprov).
    implementation(libs.bcpkix)
    // smbj's event bus rides mbassador at runtime only; we compile
    // against its @Handler annotation for connection-loss notification.
    compileOnly(libs.mbassador)
    // Share enumeration walks the Windows network via Winnetwk.
    implementation(libs.jna.platform)
}
