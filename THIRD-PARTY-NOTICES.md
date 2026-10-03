# Third-party notices

Universal Explorer stands on the third-party components listed below.
Each component is governed by its own license; the project's own code is
licensed under the GNU GPL v3 (see [LICENSE](LICENSE)).

Versions are the ones resolved onto the app's runtime classpath. License
statements were read from the jars' embedded `META-INF/LICENSE`/`NOTICE`
files or from the components' published POMs — not from memory.

## Apache License 2.0

| Component | Version | Upstream |
|---|---|---|
| FlatLaf | 3.7.2 | github.com/JFormDesigner/FlatLaf |
| Jackson (annotations, core, databind) | 2.19.2 | github.com/FasterXML/jackson |
| Sardine WebDAV client | 5.13 | github.com/lookfirst/sardine |
| smbj SMB client | 0.13.0 | github.com/hierynomus/smbj |
| asn-one | 0.6.0 | github.com/hierynomus/asn-one |
| Apache MINA SSHD (core, common, sftp) | 2.19.0 | github.com/apache/mina-sshd |
| Apache HttpComponents (httpclient, httpcore) | 4.5.14 / 4.4.16 | hc.apache.org |
| Apache Commons (codec, io, lang3, compress, net, logging) | 1.17.1 / 2.21.0 / 3.16.0 / 1.27.1 / 3.13.0 / 1.2 | commons.apache.org |
| image4j | 0.7.2 | sourceforge.net/projects/image4j |
| Kotlin stdlib | 2.4.0 | kotlinlang.org |
| JetBrains java annotations | 24.0.1 | github.com/JetBrains/java-annotations |

Copyrights: © The Apache Software Foundation (all Apache Commons /
HttpComponents / MINA SSHD entries); © FormDev Software; Jackson
© Tatu Saloranta and FasterXML contributors; smbj/asn-one © the
hierynomus project; image4j © the image4j project; Kotlin and
annotations © JetBrains.

## BSD licenses

| Component | Version | License | Upstream |
|---|---|---|---|
| TwelveMonkeys ImageIO (common-image/io/lang, imageio-core/jpeg/metadata/psd/webp) | 3.12.0 | BSD 3-Clause, © Harald Kuhr | github.com/haraldk/TwelveMonkeys |
| RSyntaxTextArea | 4.0.1 | BSD 3-Clause, © Robert Futrell | github.com/bobbylight/RSyntaxTextArea |
| commonmark + extensions (gfm-tables, gfm-strikethrough, task-list-items, autolink) | 0.30.0 | BSD 3-Clause, © Atlassian Pty Ltd | github.com/commonmark/commonmark-java |
| svgSalamander (FormDev fork) | 1.1.3 | BSD 2-Clause | github.com/JFormDesigner/svgSalamander |
| Jakarta/Eclipse JAXB stack (jakarta.xml.bind-api, jakarta.activation-api, angus-activation, jaxb-core, jaxb-runtime, txw2, istack-commons-runtime) | 4.0.2 / 2.1.3 / 2.0.2 / 4.0.5 / 4.1.2 | EDL 1.0 (BSD 3-Clause), © Oracle and/or its affiliates, Eclipse Foundation | projects.eclipse.org |

## MIT / MIT-style

| Component | Version | License | Upstream |
|---|---|---|---|
| SLF4J (api, nop, jcl-over-slf4j) | 2.0.16 / 1.7.36 | MIT, © 2004-2022 QOS.ch Sarl | slf4j.org |
| MBassador | 1.3.0 | MIT, © the MBassador project | github.com/bennidi/mbassador |
| autolink-java | 0.12.0 | MIT, © the autolink-java project | github.com/robinst/autolink-java |
| Bouncy Castle (bcprov, bcpkix, bcutil jdk18on) | 1.78.1 | Bouncy Castle Licence (MIT-style), © 2000-2023 The Legion of the Bouncy Castle Inc. | bouncycastle.org |

## Other permissive

| Component | Version | License | Upstream |
|---|---|---|---|
| JNA (jna, jna-platform 5.17.0; jpms variants 5.12.1) | 5.17.0 / 5.12.1 | Apache-2.0 OR LGPL-2.1-or-later (dual, either at your choice) | github.com/java-native-access/jna |
| XZ for Java | 1.10 | 0BSD, © Lasse Collin | tukaani.org/xz/java.html |
| EdDSA-Java | 0.3.0 | CC0 1.0 Universal | github.com/str4d/ed25519-java |

## LGPL

| Component | Version | License | Upstream |
|---|---|---|---|
| JediTerm (core, ui, typeahead) | 3.76 / 2.69 | LGPL 3.0, © JetBrains | github.com/JetBrains/JediTerm |

## GPL v3 (same license as this app)

| Component | Version | License | Upstream |
|---|---|---|---|
| vlcj (incl. vlcj-natives) | 4.8.2 / 4.8.1 | GPL v3, © Caprica Software Limited | github.com/caprica/vlcj |

## Bundled fonts (kit/src/main/resources/dock/fonts)

| Font | License | Upstream |
|---|---|---|
| Inter (4 weights) | SIL OFL 1.1, © Rasmus Andersson | rsms.me/inter |
| JetBrains Mono (3 weights) | SIL OFL 1.1, © JetBrains | jetbrains.com/mono |
| Symbols Nerd Font + Symbols Nerd Font Mono | MIT for the font-building side; glyphs aggregated from many icon sets under their own permissive licenses | nerdfonts.com |

The respective license texts ship beside the font binaries as
`LICENSE-inter.txt`, `LICENSE-jetbrains-mono.txt`, and
`LICENSE-symbols-nerd-font.txt`. The Nerd Fonts glyph-source breakdown:
github.com/ryanoasis/nerd-fonts/blob/master/license-audit.md.

## Native runtime downloaded at build time

Media playback loads **LibVLC 3.0.24** (win64, official VideoLAN build,
pinned by SHA-256) via the `downloadVlc` Gradle task into `app/vlc/`
(git-ignored). LibVLC is © VideoLAN, LGPL-2.1-or-later; its `COPYING.txt`
is retained in the unpacked runtime.
