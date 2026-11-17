# myDeepSeek

> A self-hosted-flavoured Android client for DeepSeek. Native Jetpack Compose, ~4 MB release APK after R8.

**[中文](README.md) | English**

![Version](https://img.shields.io/badge/version-1.2.3-blue)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

## What this is

myDeepSeek is a personal fork of [SimpleChat](https://github.com/Pokanshuu/Simple-Chat).
The foundation is unchanged — light, fast, offline-first, no account system — with three
targeted changes and a few real bug fixes (see [CODE_REVIEW.md](CODE_REVIEW.md)):

| Change | Detail |
|---|---|
| **Images above the bubble** | Sent images render as fixed 104dp square previews **above** the message bubble (up to three per row, `Crop`), tap to view full screen, long-press for the message menu. Image-only messages no longer draw an empty bubble. |
| **History grouped by month** | The drawer goes from Today / Yesterday / Past 7 days / Earlier to **Pinned + one group per month** (`November 2026`). The old buckets ended up stuffing ~90% of a long history into "Earlier". |
| **WebDAV sync (incremental)** | Settings → Cloud sync. The remote side is **one file per chat** plus images keyed by content hash: only changed chats are uploaded, each PUT is a few tens of KB, and an interrupted sync resumes where it stopped. Peak memory tracks the largest single chat. Merging is per conversation by `updatedAt` (last-write-wins). Optional sync on launch, manual sync, connection test. |
| **Fix: sent images were never written to disk** | Picked photos were inlined as base64 into `messages.attachments`; one 3 MB photo made a 4 MB row, past Android's 2 MB `CursorWindow` limit, so that conversation became unreadable and the image was silently dropped by the fallback path. Now the send path offloads like the import path always did. |
| **Fix: deleting a message leaked image files** | Files exclusively owned by deleted messages (and their refs) are now reclaimed. |
| **Fix: Keystore decryption on the main thread** | The settings flow now reads on IO. |
| **Fix: wrong pager start page for stored images** | Stored attachments carry an empty `data`, so two same-named images matched each other; `path` is now part of the comparison. |

## Features kept from upstream

- Any OpenAI-compatible endpoint; the official DeepSeek endpoint can use the **Anthropic-compatible** channel (web search, link reading, inline citations, server-side titles).
- SSE streaming with live Markdown, collapsible reasoning, "continue generation".
- Native math rendering and offline Mermaid diagrams.
- Message **tree** with branches and multiple variants; per-conversation fork.
- CJK-weighted token estimate, context compaction, attachments (camera / gallery / file, no extra permissions).
- JSON backup, ZIP archive with per-chat Markdown, Markdown export; imports from Chatbox and DeepSeek exports.
- Dark mode, accent colours, font scale, Chinese/English UI.

### Merge status: up to v1.1.5

The fork started from v1.1.3 and now carries everything upstream shipped after it (v1.1.4 + v1.1.5):
API profiles (several endpoint/model/key sets, one tap to switch), global search with paging and
jump-to-message, text selection, background generation, check-for-updates, and the open-source
license list.

Both sides were kept — conflicts were resolved semantically rather than by picking a winner.
Upstream also rewrote the user bubble and also fixed the TTS crash on Huawei ROMs; those
implementations were taken, then the square image preview was layered back on top.
See `CODE_REVIEW.md` for the per-file decisions.

## Build

Requirements: **JDK 21** (Android Studio's bundled JBR 21 works), Android SDK platform 37 / build-tools 37.0.0.
JDK 17 can compile but cannot run the unit tests — the `highlights` dependency ships Java 21 bytecode.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
./gradlew assembleRelease    # needs signing config, see below
```

## GitHub Actions

[`.github/workflows/android.yml`](.github/workflows/android.yml) runs on push to `main`, pull
requests, `v*` tags and manual dispatch: JDK 17 + Android SDK → restore signing key → unit tests →
`assembleRelease` → upload the APK. Pushing a tag creates a GitHub Release with the APK attached.

## Release signing

Android only installs an update when package name **and** signing key match. Using a debug key for
your first public build means users must uninstall (and lose all data) when you switch. Decide the
key once and back it up.

```bash
keytool -genkeypair -v -keystore mydeepseek-release.jks \
  -alias mydeepseek -keyalg RSA -keysize 4096 -validity 10000
```

Base64-encode it (`base64 -i … | tr -d '\n'` on macOS, `[Convert]::ToBase64String([IO.File]::ReadAllBytes("…"))`
in PowerShell). Keep the `.jks` outside the repo and back it up; the generated `.b64` is the same key in
text form, so never commit it and delete it locally once it is in the secret. Then add four repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | full base64 of the `.jks`, single line |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `mydeepseek` |
| `KEY_PASSWORD` | key password |

Without those secrets the workflow still succeeds and produces a debug-signed release APK (with a
warning), so forks build out of the box. For local release builds, create `keystore.properties`
(git-ignored):

```properties
storeFile=keystore/mydeepseek-release.jks
storePassword=…
keyAlias=mydeepseek
keyPassword=…
```

Verify with `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

## Cloud sync (WebDAV)

Settings → Cloud sync. Four fields: server URL, account, app password, remote file
(default `myDeepSeek/backup.json`; missing parent folders are created).

- **Nutstore**: server `https://dav.jianguoyun.com/dav/`, account = your email, password = a
  *third-party app password* (never your login password).
- **Nextcloud**: `https://<host>/remote.php/dav/files/<user>/`
- Only `PUT` / `GET` / `MKCOL` / `PROPFIND` with Basic auth are used.
- **HTTPS only**: cleartext HTTP is blocked since Android 9 and `usesCleartextTraffic` is not
  enabled. For a LAN server (Synology, Raspberry Pi) put a TLS certificate or a reverse proxy in front.

On the drive:

```
myDeepSeek/
├─ meta.json                   format / version / presets (tiny)
├─ conv/<id>.<updatedAt>.json  one file per chat, the file name carries the version
└─ media/<sha256>.jpg          images, content-addressed
```

**Images are not base64 in the JSON.** A 3 MB photo becomes 4 MB of base64, which made a single
line megabytes long and forced whole-file reads — that was the OOM. Now the JSON only carries the
file name; the bytes travel as their own file, and because the name is the content hash, "same name"
means "same content" (free dedup).

One file per chat, with `updatedAt` in the name, buys three things: a single PUT stays small (no more
mid-transfer 503s), **unchanged chats are never re-sent** (a directory listing tells us what the remote
already has), and an interrupted sync simply resumes next time.

How it works: `GET meta.json` + `PROPFIND conv/` → download only chats newer than local (delete the
superseded local copy, keep drafts, import) → upload only chats the remote doesn't have this version
of, images first → write `meta.json` last and drop the superseded remote files. Peak memory tracks the
**largest single chat**, not the library size.

### First sync got rate-limited? Seed the folder from your PC

Nutstore and friends throttle by **requests per unit time**. A first sync pushes the whole
library — hundreds of chats, one request per image — which looks like abuse and gets a `503`.
The app already paces itself (120 ms between requests) and backs off on 429/503/509, but with a
very large history the easy way out is to let your PC do the upload.

The remote folder is plain static files, so dragging it up with the drive's desktop client
**bypasses the API and its throttling**:

1. On the phone: Settings → Data → Export all data, copy the backup to your PC
2. Convert it (stdlib only):

```bash
python tools/seed_webdav.py backup.zip -o myDeepSeek
```

3. Upload that whole folder to the remote path configured in the app
4. Tap "Sync now" — the remote is already current, so it costs a handful of requests

> The script and the app share the same naming rules (folder name = `id.updatedAt`, image name =
> content hash), so "is the remote already this version" always agrees. A test in
> `SyncPayloadTest` pins that contract.

Known limits:

- **Deletions do not propagate.** A conversation deleted on device A comes back from B's next sync.
- **Deleted media is not reclaimed.** Images live on as orphan files under `media/`; that keeps other
  chats' images safe, at the cost of some leftovers you can prune by hand.
- **Attachments whose local file is gone are synced without the image** rather than uploading garbage.
- **The drive copy is plaintext.** `sync.jsonl` is readable JSON and images are originals — use a drive
  you trust, or leave auto-sync off.

## Privacy

API keys and the WebDAV password are AES-256-GCM encrypted with keys inside the Android Keystore and
never leave the device; requests go straight to the endpoint you configure; sync happens only between
your device and your drive. Schema changes use explicit Room migrations — no destructive fallbacks.

## License

MIT. Forked from [Pokanshuu/Simple-Chat](https://github.com/Pokanshuu/Simple-Chat); this is a
personal branch, not an official release of the upstream project.
