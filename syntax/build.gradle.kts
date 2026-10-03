// Syntax highlighting: turns code text into colored spans any surface can
// paint — the markdown code card today, a full code viewer later. The
// RSyntaxTextArea lexer family is confined to this module — consumers see
// only the span records, never the library (the svgSalamander precedent).
// No project dependencies on purpose: a leaf any future module mounts.
dependencies {
    implementation(libs.rsyntaxtextarea)
}
