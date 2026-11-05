# Neutrino Docs for Android

The Android port of `neutrino_docs_ios_mobile`: end-to-end encrypted documents, stored in Neutrino
Drive as real `.docx` files that the web and iOS apps open as their own, with a native rich-text
editor, offline editing, version history and encrypted on-device search, on top of
[`wcherry/neutrino`](https://github.com/wcherry/neutrino) (`/api/v1`).

- `core/` — plain Kotlin/JVM: the editor's document model, projection and builder, editing
  commands and typing rules, the Drive/Docs API client, content, offline and sync services, and
  the search index.
- `app/` — the Android app (Jetpack Compose around an `EditText`-based editor).

## Layout

This repository must sit next to `neutrino_shared_android`, which it builds from source:

```
getneutrino.app/
├── neutrino_shared_android/
└── neutrino_docs_android_mobile/   ← this repo
```

## Build

Needs JDK 17 and the Android SDK (API 36).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test             # model, editing, typing rules, services against a fake server
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
NEUTRINO_LIVE_URL=http://localhost:8080 ./gradlew :core:test --tests '*LiveServerTest*'
```

The live test registers a fresh account each run — point it at a throwaway database.

## Platform registration

- **OAuth client** `neutrino-docs-android` is seeded by `neutrino/migrations/00143_oauth__*`.
  Sign-in fails against a server without it ("Unknown client").
- **App config and brand** are `NeutrinoApps.docs` (shared) and `DocsBrand`
  (`app/.../ui/Components.kt`). The prefs/secrets prefix is `ndoc`. Never change or reuse it.
- **App links** (`https://www.getneutrino.app/open/doc/<id>`) verify only once
  `deploy/assetlinks.json`, with the release certificate's SHA-256, is served by the site.

From the emulator, a server on your computer is `http://10.0.2.2:8080`. See
`agent_docs/android-port.md` for decisions, what is verified, and what is left.
