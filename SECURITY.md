# Security policy

Universal Explorer handles your credentials and your files, so
security-relevant bugs are taken seriously even though this is a
hobby-scale project.

## Scope

- Credential storage: the Windows Credential Manager integration
  (targets `Universal Explorer/<site>` and `/key`), and the in-memory
  lifetime of secrets — passwords and passphrases must never reach
  disk, logs, or test output.
- Protocol handling: parsing of server responses on all five protocols
  (SFTP/SSH, SMB, WebDAV, FTP, S3 XML), including the hand-rolled
  SigV4 signer in `:s3`.
- Local file operations: path handling, archive extraction,
  any place untrusted input touches the filesystem.

Out of scope: crashes without data exposure, denial of service by a
server you deliberately connected to, and issues in third-party
dependencies that are not reachable through this app.

## Reporting a vulnerability

Please do **not** open a public issue.

- Preferred: use GitHub's *Report a vulnerability* (private security
  advisory) on this repository.
- Or email: **radviger@gmail.com** with `[security]` in the subject.

Include what you found, how to reproduce it, and which version you
tested. You will get an acknowledgement that the report arrived; fix
timelines depend on real life — this is a one-maintainer project, so
please be patient. Coordinated disclosure is the default: we agree on
a publication date once a fix is out.
