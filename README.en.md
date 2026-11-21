# myDeepSeek

> An Android DeepSeek client that keeps the conversation on your device. Native Jetpack Compose, ~4 MB release APK after R8.

**[中文](README.md) | English**

![Version](https://img.shields.io/badge/version-1.2.1-blue)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B%20%C2%B7%20API%2026-3ddc84)

**Repository**: <https://github.com/ZhuNemo/mydeepseek>

## What it is

myDeepSeek is a standalone Android chat client. Point it at any OpenAI-compatible endpoint
(DeepSeek's own included) and your messages, images and speech stay on the device — no account
system, no relay server. To move between devices, sync your history to a drive you own.

Three principles:

| Principle | What it looks like |
|---|---|
| **Your data stays yours** | The local database and private files are the only source of truth. No cloud account, export always works, uninstall really deletes. |
| **Long chats have to work** | Hundreds of messages or hundreds of thousands of characters must open, scroll and branch without stutter — and hit-testing on a giant bubble must not break. |
| **Details match the official app** | Images sit in their own block instead of deforming the bubble, history is grouped by month, speech can use the official voices. |

Where it differs from "a shell around an API key":

- **Sync to your own drive** — no third-party account. The remote side is one file per chat plus
  images keyed by content hash, so only changed chats move and an interrupted sync resumes.
- **Images actually hit the disk** — photos are written to private storage before the row is
  inserted, so a 4 MB base64 row can never blow past Android's 2 MB `CursorWindow` and make a
  whole conversation unreadable (a data-losing bug in the original implementation).
- **History grouped by month** — after a year of use, 90% of chats no longer collapse into "Earlier".
- **Delete means reclaim** — image files owned solely by deleted messages are removed with them.

## Features

- Any OpenAI-compatible endpoint; the official DeepSeek endpoint can use the **Anthropic-compatible** channel (web search, link reading, inline citations, server-side titles).
- SSE streaming with live Markdown, collapsible reasoning, "continue generation".
- Native math rendering and offline Mermaid diagrams.
- Message **tree** with branches and multiple variants; per-conversation fork.
- CJK-weighted token estimate, context compaction, attachments (camera / gallery / file, no extra permissions).
- JSON backup, ZIP archive with per-chat Markdown, Markdown export; imports from Chatbox and DeepSeek exports.
- Dark mode, accent colours, font scale, Chinese/English UI.

## Build

The APK ships as `com.zhunemo.mydeepseek` — a different application id from upstream Simple Chat
(`com.simplechat.app`), so both can live on one device with separate data.

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

## Credits and license

Released under the MIT license (see [LICENSE](LICENSE)), keeping the original copyright notice.

The UI skeleton, message tree, Markdown rendering and protocol implementation come from
**Simple Chat**:

- Reference implementation: <https://github.com/Pokanshuu/Simple-Chat>

The conversation UI, sync, icon and this document are what this repository changed; each item is
recorded in [`CODE_REVIEW.md`](CODE_REVIEW.md). This repository is maintained independently and is
not an official release of the upstream project — please file issues
[here](https://github.com/ZhuNemo/mydeepseek/issues).
