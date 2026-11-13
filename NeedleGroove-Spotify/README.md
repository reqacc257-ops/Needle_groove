# Needle & Groove — Spotify-inspired refresh

This project contains the updated Android app module.

## Changes
- Keeps the existing animated vinyl record and its album-art label.
- Keeps the existing song-artwork glow on the vinyl.
- Adds a song-responsive, animated dark gradient behind the Now Playing screen.
- Adds a persistent mini-player while browsing other tabs.
- Adds a queue dialog with play and remove actions.
- Adds a dedicated Search destination using the existing local library search.
- Uses Spotify-inspired dark surfaces and green accents by default.
- Adds subtle button press animations.
- Retains the existing timed/plain lyrics support and lyrics import workflow.

## Build
Open this folder in Android Studio and sync Gradle. The project uses Android Gradle Plugin 8.13.0, Java 17, and compile SDK 36.
Build with **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

The original uploaded archive contained the app module only (no Gradle wrapper or root project files), so root Android Studio project configuration has been added here.

## Getting an installable APK

The debug build is signed with Android's debug key, so it installs directly on a phone
(allow "Install unknown apps" for whichever app opens the file).

**Option A: GitHub Actions (nothing to install)**
1. Create a new GitHub repository and upload the contents of this folder to it (keep the `.github` folder).
2. Open the repo's **Actions** tab, choose **Build APK**, and press **Run workflow** (it also runs on every push to `main`).
3. When the run finishes, open it and download **NeedleGroove-debug-apk** from *Artifacts*. Unzip it to get `app-debug.apk`.

**Option B: Android Studio**
Open this folder, let Gradle sync, then **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
The file lands in `app/build/outputs/apk/debug/app-debug.apk`.

**Option C: command line** (JDK 17 and the Android SDK installed)
`./gradlew assembleDebug`
