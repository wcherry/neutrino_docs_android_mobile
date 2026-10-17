# Neutrino Docs for Android — port notes and handoff

**Started:** 2026-10-16. **Status:** feature parity with the iOS Docs app as of that date (its
phases 1, 2 and 5); run on an Android 14 emulator against a real local server. Never yet on a
physical device or the hosted server.

## 1. Decisions

| Decision | Why |
|---|---|
| Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 36` | Same toolkit and floor as Drive and Calendar for Android, so the shared library has one target. |
| `:core` is plain Kotlin/JVM; `:app` is Android | The document model, editing commands, typing rules, API client, offline/sync and search are tested on the JVM in seconds, against `FakeServer` from the shared test fixtures. |
| Documents are `.docx`, read and written by `neutrino_shared_android`'s `ooxml` package | The web and iOS store Docs as OOXML with a ProseMirror tree mapping and a `customXml/item1.xml` extras part. Putting the codec in the shared library leaves it reusable by Sheets/Slides ports. `scripts/crosscheck-web-docx.sh` there runs the web's own reader/writer against it both ways. |
| Editor: an `EditText` mirroring an `AttributedText` model | The iOS editor is a `UITextView` over an `NSAttributedString` whose attributes carry the block, marks and atoms. Android has no attribute-carrying string a text view keeps intact, so the model is our own (`core/document/AttributedText`), the text view holds plain characters, and `EditorRenderer` derives presentation spans from the model after every change. Every command reads the model, never the screen. |
| Keystrokes reach the typing rules one at a time | A keyboard composing a word reports every key as "replace the word"; `EditorController.onUserEdit` reduces that to the one character added or removed, so `# `, Return in a list, brackets and Backspace-at-start fire under Gboard as under a hardware keyboard. |
| Own undo stack | The model, not the text view, is the document; `EditText`'s undo would undo characters without their blocks. |
| An empty last line's block | A projected document ends without a separator, so an empty last line has no character of its own. As on iOS, the separator before it carries its block — but that fails when the text is empty or the line before is empty too. `AttributedText.trailingBlock` holds it then (iOS gets this from `typingAttributes`). |
| OAuth client `neutrino-docs-android` | Root CLAUDE.md rule 3. Registered by `neutrino/migrations/00143_oauth__2026-10-16-000001_add_android_docs_client` (branch `feature/android-docs-client`). **Sign-in fails against any server without it.** |
| Prefix `ndoc`, search-index key in Keystore alias `neutrino.docs.search.v1` | Same namespace as iOS Docs. The index is encrypted at rest with a key that never leaves the phone. |
| Search by name is local | `/drive/search` was removed from the server (neutrino `01604d0`, 2026-08-04); names are matched against the listings already loaded, content against the on-device index. |
| No key vault | The Argon2 key vault iOS still links is legacy; the keyring is client-only since neutrino `1678bc0`, which is what the shared library implements. |
| `/auth/sessions` "this device" by name | The server's sessions carry no `isCurrentDevice`; the app matches the header-safe device name it signs in with. |
| Background sync: 15-minute periodic WorkManager job | Android's equivalent of iOS's background refresh; queued edits are ciphertext on disk and are uploaded with an `expectedContentVersion` guard (409 → a conflict the user resolves). |

## 2. Feature map (iOS → Android)

Ported directly: sign-in/registration/2FA, key setup, pairing, recovery kit · document browser
(My Drive, Favorites, Shared, Recent, Trash; folders; sort; create, rename, duplicate, move, star,
trash, restore, delete forever, empty trash) · editor with headings, marks, links, lists, task
lists, quotes, code blocks with folding, a starter table (drawn as a block, edited on the web), rules, alignment, indent/outdent,
markdown line shortcuts, bracket closing, find and replace, outline, word/character/reading-time
stats, auto-save with a version guard · pictures (photos, files, a link, Drive; stored in
Drive's `Attachments` folder, encrypted; properties: alignment, width, border, shadow, filters,
caption, alt text) · version history (save named, preview, restore) · offline copies, offline
edits, conflict resolution, queued deletes/moves/renames · encrypted on-device search with
filters and saved searches · settings (theme, editor font and size, auto-save interval, typing
options, devices, app lock, report a bug).

Mapped to Android equivalents:

| iOS | Android |
|---|---|
| `UITextView` + `NSAttributedString` | `EditText` + `AttributedText` model + `PresentationSpan`s |
| `NSTextAttachment` pictures | `PictureSpan` (`ReplacementSpan`) |
| Rounded system font | Default sans — Android ships no rounded face |
| Universal links, `neutrinodocs://` | App Links (`/open/doc/<id>`, see `deploy/assetlinks.json`) and the same custom scheme |
| `BGAppRefreshTask` | WorkManager |

**Not ported:** justified alignment is kept in the document but drawn left (Android's
justification is per view, not per paragraph); inline `**bold**`/`_italic_` typing shortcuts —
iOS has none either.

## 3. Deviations from iOS (bugs fixed here, not there)

- **Indent/outdent.** iOS's indent wraps the item in a new list; here it nests the item under its
  previous sibling and outdent lifts it to a sibling of its parent, which is what ProseMirror's
  `sinkListItem`/`liftListItem` (the web) do.
- **Empty trailing blocks.** Return after a heading, a new list item or a marker typed on the last
  line could lose its block on reprojection; and a marker typed into an empty document (or after
  an empty line) did nothing. See the `trailingBlock` decision above; covered by `TypingTest`.
- **Search text of formatted words.** iOS joins a paragraph's text runs with spaces, so
  "he**ll**o" indexes as "he ll o" and a search for "hello" misses it (and previews show doubled
  spaces). Fixed here (`DocumentSearchContent.text`); **iOS still has it**.
- **The web's docx reader** dropped a header band written behind a watermark paragraph — how iOS
  and Android write it. Fixed in `neutrino` (`web/.../ooxml/docx/read.ts`, same branch).

## 4. What is verified (2026-10-16, macOS host)

- `./gradlew :core:test` — 61 tests (+ the live test, skipped without a server): projection and
  builder round trips, editing commands, typing rules and keystroke replays, search, services
  against a fake backend (create, save with version guard and conflict, attachments, offline
  queue and sync, versions, organising).
- `neutrino_shared_android`: 111 core tests, incl. 68 for the docx codec; `crosscheck-web-docx.sh`
  (web ↔ Android, 7 documents each way) and `crosscheck-web-crypto.sh` both pass. Drive and
  Calendar for Android: `:core:test :app:assembleDebug` pass on the changed library.
- `LiveServerTest` against a locally built server with migration 00143: register, key, create a
  `.docx`, save under the version guard and see a stale save refused, a picture uploaded to
  Attachments and embedded, a named version listed/read/restored, an offline edit drained by the
  sync engine, rename/duplicate/star/trash/restore, the device list.
- On the emulator (Pixel 7, API 34) against that server: registration (server field honoured),
  key setup, browser, create a document, typing rules (`#`, `-`, `1.`, `>`, `[]`), Heading 1 from
  the style bar, italic on a selection, fold all/unfold, find with match highlighting, version
  history and preview, inserting a photo (uploaded encrypted, drawn inline, survives reopen),
  search, Offline and Settings screens.

**Not verified:** a physical device; the hosted server; typing with a real on-screen keyboard (the
emulator runs fed key events — the composing-word reduction is reasoned, not observed); opening a
document written by the web or iOS through the app UI (the codec cross-check covers the format);
scanning the key QR code with the camera; app links (need `assetlinks.json` served); app lock with biometrics; release (R8)
build at run time.

## 5. Next session checklist

1. Merge/deploy `neutrino` `feature/android-docs-client` (migration 00143 + the web reader fix).
2. Physical device with Gboard: type `# `, `- `, `1. `, Return in a list, `(` — the typing rules
   under a composing keyboard.
3. Open a document written on the web and one written on iOS; edit; reopen on both.
4. Serve `deploy/assetlinks.json` with the release certificate; check `/open/doc/<id>` opens here.
5. `./gradlew :app:assembleRelease` with a signing config; run it (R8 + kotlinx serialization).
6. Port the search-text fix (§3) to iOS `DocumentSearchContent.text(of:)`.

## 6. Found while porting (not fixed here)

- `neutrino_shared_android`'s sign-in screen saved an edited server address only on **Sign in**,
  so **Create an account** registered on the default (hosted) server. Fixed in the shared library
  on this branch — it affected Drive and Calendar for Android too.
