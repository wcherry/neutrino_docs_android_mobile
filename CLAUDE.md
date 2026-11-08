# CLAUDE.md — Neutrino Docs for Android

Part of the Neutrino platform — read the root `../CLAUDE.md` coupling rules first. This is the
Android port of `neutrino_docs_ios_mobile`, built on `../neutrino_shared_android` (consumed by
local path through `includeBuild`, unversioned — a change there reaches this app, Drive and
Calendar at once). Its OAuth client id is `neutrino-docs-android`
(`neutrino/migrations/00143_oauth__*`); its prefs/secrets prefix is `ndoc`. Never change or reuse
either.

- Build with JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Put editing logic in `:core` (JVM-tested), not `:app`. The editor is an `AttributedText` model
  (`core/document/`) with pure commands and typing rules (`core/editing/`); the `EditText` in
  `app/.../ui/editor/` only mirrors it and draws presentation spans. A behaviour change belongs in
  a core test first — `TypingTest` replays keystrokes the way the editor feeds them.
- The `.docx` reader/writer is in the shared library (`com.neutrino.shared.core.ooxml`), not here.
  It is a format shared with the web and iOS: change it there and run its
  `scripts/crosscheck-web-docx.sh`.
- Documents, pictures and the search index are E2EE through the shared code — never add crypto here.
- Status, decisions, the iOS → Android feature map and the next-session checklist:
  `agent_docs/android-port.md`.
