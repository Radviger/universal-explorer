rootProject.name = "universal-explorer"

// One module per protocol backend and per tab-content frontend, plus the
// shared core/kit foundations and the app shell. Modules appear here as
// they are extracted; the shell discovers them via ServiceLoader, never
// by compile-time reference.
include("core", "kit", "sftp", "smb", "webdav", "ftp", "s3", "archive", "viewer", "syntax", "markdown", "editor", "media", "commander", "terminal", "app")
