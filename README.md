# Story Saver (Android)

Save WhatsApp statuses you've viewed and Instagram media from direct media links — using only
methods Android and the platforms actually support. Kotlin · Jetpack Compose · Material 3 · MVVM.

- Min Android: 8.0 (API 26) · Target/compile: API 35
- No accounts, no logins, no analytics, no ads. Files never leave your phone.

---

## Build & install

### Option A — Android Studio (easiest)
1. Install **Android Studio** (Ladybug 2024.2 or newer) with the Android SDK Platform 35.
2. **File → Open** → select the `StorySaver` folder. Let Gradle sync (first sync downloads dependencies).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — command line
Requires JDK 17+ and the Android SDK (`ANDROID_HOME` set, or a `local.properties` with `sdk.dir=...`).
```bash
cd StorySaver
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # run unit tests
```

### Install on your phone
- **USB:** enable Developer options → USB debugging, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (or just press ▶ Run in Android Studio with the phone connected).
- **Manually:** copy `app-debug.apk` to the phone, open it in Files, and allow "Install unknown apps" for that app when asked.

The debug APK is signed with Android Studio's debug key, which is fine for personal use.

---

## How to use

1. **Home:** choose **Image** or **Video**, then **WhatsApp Status** or **Instagram Story**, then **Continue**.
2. **WhatsApp Status**
   - View the statuses in WhatsApp first (WhatsApp only stores statuses you've opened, for ~24 h).
   - Tap **Grant folder access**. The system picker opens at
     `Android/media/com.whatsapp/WhatsApp/Media/.Statuses` → tap **Use this folder** → **Allow**. You only do this once.
   - Tap a status → preview → **Save**. Then **Open** or **Share** it.
   - Can't reach the folder? Use **Pick a file** (system file picker) instead. WhatsApp Business has its own button.
3. **Instagram**
   - Paste a link (**Paste** button reads the clipboard only when you tap it) → **Download** → preview → **Save**.
   - Works for **direct media links** (on `cdninstagram.com` / `fbcdn.net`) that open without logging in.
   - Normal **Story / post / reel links** are recognised and you get a clear explanation of why they can't be downloaded (see limitations).
4. **Share → Story Saver** from any app that shares an actual image/video file saves it directly. Sharing a link opens the Instagram screen with it filled in.
5. **Downloads** tab: thumbnails, date, size; Open, Share, Delete. **Settings**: choose Gallery (default) or a custom folder, manage the status-folder grant, privacy info.

Files are saved to **Pictures/StorySaver** (images) and **Movies/StorySaver** (videos) and appear in your gallery, or to the folder you pick in Settings.

---

## Implemented features

- Home flow with selectable Image/Video and WhatsApp/Instagram cards, clear selection state, Continue button
- WhatsApp: SAF folder grant (persisted), picker pre-pointed at `.Statuses` (regular + Business, new + legacy paths), automatic descent into `.Statuses` if a parent folder is picked, image/video grid with thumbnails, single-file picker fallback, explanation when a link is pasted
- Instagram: URL validation and classification (story / post / reel / profile / CDN media / non-Instagram / look-alike hosts), direct HTTPS download with progress and cancel, content-type check (rejects HTML/login pages and type mismatches), honest HTTP error messages (403/404/429/5xx)
- Saving: MediaStore (Android 10+, no permission), legacy public folders + media scan (Android 8–9, permission requested only there), or a user-chosen SAF folder; unique names (`StorySaver_WA_20261022_132501_ab12.jpg`); correct MIME/extension; free-space check; cleanup of partial files; **size verification before reporting success**
- Download history with thumbnails, Open/Share/Delete (with Android 10+/11+ system delete-consent flow), auto-pruning of files deleted elsewhere
- Share-sheet intake for images, videos and links
- Settings: destination, status folder, privacy, about
- Light/dark theme, rounded cards, 48dp+ touch targets
- Unit tests: URL validation, file naming, MIME helpers, progress math, stream copy & cancellation

## Known limitations (by design)

- **Instagram Story/post/reel URLs are not downloadable.** They identify a page in the Instagram app, not a media file. Getting the file would require logging in or scraping Instagram, which this app deliberately does not do (no passwords, no bypassing private accounts, rate limits or CAPTCHAs). `InstagramViewModel.fetch()` is the single place where a future officially supported integration could plug in for `UrlCheck.StoryPage`/`PostPage`.
- **CDN links expire.** Direct Instagram media links are signed and stop working after a while; you'll see an "expired / refused access" message.
- **WhatsApp statuses** are only available after you view them in WhatsApp, and only while WhatsApp keeps them. Folder location varies by phone and WhatsApp version; some file pickers hide dot-folders (use the picker's "show hidden files" option, or Pick a file). The app never reads WhatsApp's private app storage.
- A pasted WhatsApp link can't fetch a status — statuses have no public URLs.
- On Android 10+, after reinstalling the app, deleting older gallery items asks for system confirmation (Android no longer considers the app their owner).
- Videos are previewed as a thumbnail; tap **Open** to play them in your video player.

## Project structure

```
app/src/main/java/com/storysaver/app/
  MainActivity.kt, StorySaverApp.kt        entry point, share intents, DI container, Coil video frames
  model/Models.kt                          MediaType, Platform, SavedMedia
  util/  UrlValidator, FileNaming, MimeUtils, StreamCopier   pure Kotlin (unit-tested)
  data/  MediaSaver        MediaStore / legacy / SAF saving, verification, delete
         StatusRepository  SAF listing of WhatsApp .Statuses
         DirectMediaDownloader  OkHttp direct-media fetch into cache
         HistoryRepository, SettingsRepository, ShareInbox, UriInfo
  ui/    home/, download/ (Instagram, WhatsApp, Shared + ViewModels), downloads/, settings/,
         navigation/, components/, theme/
app/src/test/...                           JUnit tests
```

Dependencies (pinned, mutually compatible): AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.0, Compose BOM 2024.12.01,
Navigation 2.8.5, Lifecycle 2.8.7, OkHttp 4.12.0, Coil 2.7.0. Android Studio may offer newer versions — they're optional.

## Manual test checklist

- [ ] Navigation: Home → each source and back; bottom tabs keep state
- [ ] Invalid input (`hello`, `ftp://…`, `example.com/x.jpg`) shows an error; Story/post links show the "not supported" card
- [ ] A live direct CDN image/video link downloads, previews, saves, and appears in Gallery
- [ ] Choosing Video for an image link shows the mismatch message
- [ ] WhatsApp: grant `.Statuses`, images and videos list, save works; Pick a file works
- [ ] Android 8/9 device: storage permission prompt appears only on first save
- [ ] Custom folder in Settings: saves land there
- [ ] Downloads: Open, Share, Delete; deleting from Gallery outside the app removes the entry on return
- [ ] Airplane mode during an Instagram download → clear network error, no file left behind

## App signing (keep the key private)

Release APKs are signed by GitHub Actions with a key stored in **encrypted repository secrets** —
the key is never committed. Required secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the keystore file, base64-encoded (one line) |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

The build refuses to publish an APK that isn't signed with the expected certificate
(SHA-256 fingerprint checked in `.github/workflows/build-apk.yml`). Keep a private backup of the
keystore: without it, new versions can't be installed as updates over old ones.
