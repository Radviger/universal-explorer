// Text editor: swaps into a commander pane in place of the file table and
// edits one local or remote file with live syntax highlighting. The
// RSyntaxTextArea editor component is confined to this module — the second
// org.fife consumer next to the lexer in :syntax, so a future engine swap
// touches only here; :syntax stays the read-only span IR. Markdown files
// open into the embedded render page (the :markdown surface) by default
// and flip between rendered and source in place.
dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))
    // Language detection rides the same alias table the spans use.
    implementation(project(":syntax"))
    // The rendered half of the markdown view-switch.
    implementation(project(":markdown"))

    implementation(libs.rsyntaxtextarea)
}
