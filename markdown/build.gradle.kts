// Markdown viewer: renders a .md file into a themed read-only document,
// swapped into a commander pane in place of the file table. commonmark is
// confined to this module — the commander mounts the panel directly,
// nothing else sees the parser.
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))
    // Inline images decode through the image viewer's decoder.
    implementation(project(":viewer"))
    // Code cards highlight through the syntax module's engine-agnostic spans.
    implementation(project(":syntax"))

    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.task.list)
    implementation(libs.commonmark.autolink)
}
