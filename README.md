# RocksmithToTab for Android

Convert your Rocksmith 2014 `.psarc` song files into Guitar Pro `.gpx` tabs, directly on your Android device — no PC required.

---

## Download & Install

> **Just here for the app?** Follow these four steps.

### Step 1 — Go to the Releases page

On this GitHub page, look on the right-hand side for a section called **Releases**. Click it. You'll see a list of versions; the one at the top is the latest.

### Step 2 — Download the APK

Under the latest release, expand **Assets** and tap the file ending in `.apk` (for example `RocksmithToTab-v1.2.0.apk`). It will download to your phone or tablet.

> **Downloading on a computer instead?** Transfer the `.apk` file to your Android device via USB, Google Drive, or any method you prefer, then open it there.

### Step 3 — Allow installation from unknown sources

Android will likely show a warning because this app isn't from the Google Play Store. This is normal for open-source apps distributed on GitHub.

- **Android 8 or newer:** When prompted, tap **Settings**, then enable **Allow from this source** for your browser or file manager. Go back and tap **Install**.
- **Android 7 or older:** Go to **Settings → Security → Unknown sources** and toggle it on, then try the installation again.

You can turn this setting back off after installing if you prefer.

### Step 4 — Open the app

Find **RocksmithToTab** in your app drawer. When you first open it, grant it permission to access your files — this is needed to read your `.psarc` files and save the converted tabs.

---

## How to use it

1. Make sure you have a Rocksmith 2014 `.psarc` file on your device. These are the DLC song files from the game. Songs you own are typically found in the game's `dlc` folder on PC or Mac; transfer the ones you want to your Android device.
2. Open RocksmithToTab and tap **Open file**.
3. Browse to your `.psarc` file and select it.
4. The app will convert it and save a `.gpx` file in the same folder.
5. Open the `.gpx` file in [Guitar Pro](https://www.guitar-pro.com/) or any compatible tab reader.

> **Supported files:** Rocksmith 2014 PC/Mac `.psarc` files. Console (PS3/Xbox 360) files are not supported.

---

## Frequently asked questions

**Will this work on my phone?**
The app requires Android 8.0 (Oreo) or later. It works on phones and tablets.

**Is this legal?**
This app only reads song files that you already own. It does not download, distribute, or circumvent any copy protection — it simply converts a format you already have access to. Use it responsibly and only with content you own.

**Will it damage my Rocksmith files?**
No. The app reads your `.psarc` files but never modifies them.

**The conversion finished but the tab looks wrong.**
Rhythm detection for complex songs is imperfect. If something looks odd in Guitar Pro, try manually correcting the note durations. Filing a bug report (see below) with the song name helps us improve things.

**I get an error saying "No manifest data found".**
This usually means the `.psarc` file is from a console version of the game (PS3 or Xbox 360), which is not currently supported. Only PC and Mac `.psarc` files work.

**Where does the converted file get saved?**
In the same folder as the original `.psarc` file, with the same name but a `.gpx` extension.

---

## Reporting a problem

If the app crashes or produces a bad result, please [open an issue](../../issues/new). Include:
- Your Android version (Settings → About phone → Android version)
- The name of the song that caused the problem
- What you expected to happen vs. what actually happened

You don't need a GitHub account to read issues, but you do need one (free) to submit them.

---

## For contributors

Thanks for your interest in improving RocksmithToTab for Android.

### What this project is

This is a Kotlin/Android port of [RocksmithToTab](https://github.com/fholger/RocksmithToTab) (C#), which converts Rocksmith 2014 `.psarc` archives into Guitar Pro `.gpx` files. The core decryption and parsing logic draws on [Rocksmith Custom Song Toolkit](https://github.com/rscustom/rocksmith-custom-song-toolkit).

### Repository layout

```
.
├── .github/
│   └── workflows/
│       ├── build-testing.yml        # Debug APK on every push (v0.0.0)
│       ├── build-major-release.yml  # Manual: bump vX.0.0, publish release
│       ├── build-minor-release.yml  # Manual: bump vX.Y.0, publish release
│       └── build-patch-release.yml  # Manual: bump vX.Y.Z, publish release
│
├── app/
│   ├── app_build.gradle             # Module-level build config (named to avoid
│   │                                #   confusion with root build.gradle)
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/rocksmithtab/
│       │   │   ├── RocksmithToTabApp.kt
│       │   │   ├── conversion/
│       │   │   │   ├── Converter.kt
│       │   │   │   └── RhythmDetector.kt
│       │   │   ├── data/
│       │   │   │   ├── model/Score.kt
│       │   │   │   ├── psarc/
│       │   │   │   │   ├── BigEndianReader.kt
│       │   │   │   │   ├── Manifest2014.kt
│       │   │   │   │   ├── PsarcBrowser.kt
│       │   │   │   │   ├── PsarcReader.kt
│       │   │   │   │   └── SngToScore.kt
│       │   │   │   └── sng/Sng2014Reader.kt
│       │   │   └── export/
│       │   │       ├── gpif/Gpif.kt
│       │   │       ├── GpxContainer.kt
│       │   │       └── GpxExporter.kt
│       │   └── res/
│       │       ├── drawable/        # Vector icons + launcher layers
│       │       ├── layout/          # activity_main.xml
│       │       ├── mipmap-anydpi-v26/  # Adaptive icon manifests
│       │       ├── values/          # strings.xml, colors.xml, themes.xml
│       │       └── xml/             # backup_rules.xml, data_extraction_rules.xml
│       ├── test/                    # JVM unit tests
│       └── androidTest/             # Instrumented device tests
│
├── gradle/
│   ├── libs.versions.toml           # Single version catalog for all dependencies
│   └── wrapper/
│       ├── gradle-wrapper.jar       # ⚠ NOT in repo — see gradle/wrapper/README.txt
│       ├── gradle-wrapper.properties
│       └── README.txt               # How to obtain gradle-wrapper.jar
│
├── build.gradle                     # Root build file (plugin declarations only)
├── settings.gradle                  # Project name + module list
├── gradlew                              # Wrapper launch script (Linux/macOS)
├── gradlew.bat                          # Wrapper launch script (Windows)
├── gradle.properties                # JVM args, AndroidX flags
├── .gitignore
├── LICENSE
└── README.md
```

### Prerequisites

- [Android Studio](https://developer.android.com/studio) (Hedgehog or later recommended)
- JDK 17 (bundled with Android Studio)
- Android SDK 34 (install via Android Studio's SDK Manager)

### Obtaining gradle-wrapper.jar

`gradle-wrapper.jar` is not committed to this repository (it's a binary file that can't be reviewed in pull requests). You need it before you can run any `./gradlew` command locally.

**Option A — Download from Gradle's server (simplest):**
```bash
curl -Lo gradle/wrapper/gradle-wrapper.jar \
  https://services.gradle.org/distributions/gradle-8.9-wrapper.jar
```
Verify SHA-256: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`

**Option B — Copy from Google's Android samples repo on GitHub:**
```bash
curl -Lo gradle/wrapper/gradle-wrapper.jar \
  https://github.com/android/architecture-samples/raw/main/gradle/wrapper/gradle-wrapper.jar
```

**Option C — Extract from the distribution ZIP:**
```bash
curl -Lo /tmp/gradle-8.9-bin.zip \
  https://services.gradle.org/distributions/gradle-8.9-bin.zip
unzip -j /tmp/gradle-8.9-bin.zip "gradle-8.9/lib/gradle-wrapper.jar" -d gradle/wrapper/
```

**Option D — Generate with a local Gradle install:** `gradle wrapper --gradle-version 8.9`

See `gradle/wrapper/README.txt` for full details and Windows commands.

> CI does **not** need this file — GitHub Actions bootstraps the wrapper automatically.

### Building locally

```bash
git clone https://github.com/YOUR_USERNAME/rocksmith-to-tab-android.git
cd rocksmith-to-tab-android

# One-time: get the wrapper JAR (see options above)
curl -Lo gradle/wrapper/gradle-wrapper.jar \
  https://services.gradle.org/distributions/gradle-8.9-wrapper.jar

# Build
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Running tests

```bash
./gradlew test
```

### Signing a release build

Release builds require a signing keystore. For local testing you can generate one:

```bash
keytool -genkey -v \
  -keystore my-release-key.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias my-key-alias
```

Then build:

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=/path/to/my-release-key.jks \
  -Pandroid.injected.signing.store.password=YOUR_STORE_PASSWORD \
  -Pandroid.injected.signing.key.alias=my-key-alias \
  -Pandroid.injected.signing.key.password=YOUR_KEY_PASSWORD
```

### Setting up CI signing secrets

For the release workflows to sign APKs, add these four secrets to your GitHub repository (**Settings → Secrets and variables → Actions → New repository secret**):

| Secret name | Value |
|---|---|
| `KEYSTORE_BASE64` | Your `.jks` keystore file, base64-encoded: `base64 -i my-release-key.jks` |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | The key alias |
| `KEY_PASSWORD` | The key password |

### Publishing a release

All three release workflows are **manual** (`workflow_dispatch`). To publish:

1. Go to the **Actions** tab on GitHub.
2. Select the appropriate workflow (Major / Minor / Patch).
3. Click **Run workflow** → **Run workflow**.

The workflow will automatically determine the next version number from existing tags, build a signed APK, create a Git tag, and publish a GitHub Release.

### Versioning

This project uses [Semantic Versioning](https://semver.org/):

- **Major** (`vX.0.0`) — breaking changes or substantial rewrites
- **Minor** (`vX.Y.0`) — new features, backward-compatible
- **Patch** (`vX.Y.Z`) — bug fixes only

### Contributing code

1. Fork the repository.
2. Create a branch: `git checkout -b fix/my-bug-fix`.
3. Make your changes and add tests where appropriate.
4. Ensure `./gradlew test` passes.
5. Open a pull request against `main`.

The testing workflow will automatically build a debug APK on your PR so reviewers can test it.

### Architecture notes

The conversion pipeline is intentionally separated into three stages so each can be tested and replaced independently:

1. **Parse** — `PsarcReader` + `Sng2014Reader` produce raw typed structs with no interpretation.
2. **Model** — `SngToScore` maps the raw structs into the intermediate `Score`/`Track`/`Bar`/`Chord`/`Note` model, which is format-agnostic.
3. **Export** — `GpxExporter` + `GpxContainer` serialise the model to GPX. Replacing this stage with a different exporter (e.g. MusicXML) requires no changes to stages 1 or 2.

The most cryptographically sensitive code is in `PsarcReader` (AES-CFB PSARC TOC decryption) and `Sng2014Reader` (AES-CFB SNG decryption with counter-incremented IV). Both use only standard `javax.crypto` APIs.

---

## License

This project is licensed under the [BSD 2-Clause License](LICENSE) ("FreeBSD License").

Portions of this project are derived from:
- **RocksmithToTab** — BSD 2-Clause License
- **Rocksmith Custom Song Toolkit** — GNU LGPL v2.1
- **MiscUtil** — Apache License 2.0

Rocksmith® is a registered trademark of Ubisoft Entertainment. This project is not affiliated with or endorsed by Ubisoft.
