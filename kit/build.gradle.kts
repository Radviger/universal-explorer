plugins {
    // Exposes flatlaf as api so consumers compile against it transitively.
    id("java-library")
}

// Shared UI foundations: fonts, glyphs, design tokens, theming, and the
// small swing vocabulary (cards, toasts, tiles) every frontend uses.
dependencies {
    // The connect-form SPI speaks Site/Protocol, so core rides along as api.
    api(project(":core"))
    api(libs.flatlaf)
    implementation(libs.jna.platform)
}
