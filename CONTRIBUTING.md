# Contributing to Universal Explorer

Thanks for wanting to help. This is a personal project shared with the
world — there is no company behind it, so review happens in spare time
and the pace is unhurried. That said, good changes do land.

## Getting set up

- Windows 10/11 with a **JDK 25** installed.
- `gradlew.bat :app:run` builds and launches (the first run fetches the
  pinned LibVLC runtime for media playback, ~80 MB).
- `gradlew.bat test` runs the full suite across all modules.

## The verification bar

Every change ships through the same gates:

```
gradlew.bat test
gradlew.bat :app:run --args="--screenshot"          # dark
gradlew.bat :app:run --args="--screenshot-session"  # session-mode UI
```

The screenshot harness is not just for pictures: it is the
deterministic UI verifier, asserting geometry, contrast, and rendering
programmatically. It must end with `ALL CHECKS PASSED`. Visual claims
are always verified by code, never by eyeballing or screenshot
diffing alone.

## How changes are shaped

- **Vertical slices.** One behavior per change, complete: code, tests,
  and (when user-visible) harness coverage. Each slice is a single
  commit with a message explaining the *why*.
- **Test names are specs.** A test is named after the behavior it
  guarantees — `guestSiteDropsItsUser`, not `testSite3`. If a name
  can't be read as a sentence about the app, the test is wrong.
- **Modules talk through SPIs.** The shell (`:app`) links only
  `:core`/`:kit`; protocol backends and tab-content frontends are
  discovered via `ServiceLoader`. New protocols join as new modules,
  never as shell imports.
- **Secrets never hit disk.** Anything credential-shaped goes through
  `CredentialManager` / the `SecretSource` SPI — never into logs,
  sessions.json, or test output.
- **House tone in UI strings**: short, plain, no exclamation marks.

## Pull requests

Keep them slice-sized and gated as above. Describe what behavior
changed and why; the commit history should read like a story of the
app growing. By contributing you agree your work is licensed under the
project's GPL-3.0 (see [LICENSE](LICENSE)).
