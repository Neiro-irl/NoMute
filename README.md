# NoMute

NoMute is a native Android music player for audio you keep on your device. The interface is designed for a phone, with a local library, search, favorites, playlists, offline playback, and a compact player.

## Download and verify

Download the latest APK from [GitHub Releases](https://github.com/Neiro-irl/project/releases/latest/download/Aftertone.apk), or choose a version from the [full Releases list](https://github.com/Neiro-irl/project/releases). Releases include `Aftertone.apk` and its `Aftertone.apk.sha256` checksum file.

After downloading both files into the same folder, verify the APK on Linux or macOS:

```bash
sha256sum --check NoMute.apk.sha256
```

On Windows, compare the SHA-256 value printed by this command with the value in the release checksum file:

```powershell
(Get-FileHash .\NoMute.apk -Algorithm SHA256).Hash
```

A matching hash confirms that the APK bytes match the checksum published with that release; it is not an independent security audit. The source and the GitHub Actions workflow are available in this repository so users can inspect how releases are built. Open source is not a guarantee that every build is free of vulnerabilities, and no “virus-free” claim is made here.

NoMute supports Android 8.0 (API 26) and later. Android may ask you to allow APK installation from the source you chose.

## What the app does

- Imports audio selected by you and stores a private copy in Aftertone, leaving the original file unchanged.
- Plays supported local audio offline and exposes playback controls through Android's media notification/session.
- Lets you search tracks, manage favorites and playlists, and remove Aftertone's own copy from the device.
- Offers five color palettes. Generated covers use the selected palette; you can also choose a custom cover for a track.
- Supports MP3, M4A, AAC, FLAC, WAV, OGG/OGA, Opus, AMR, and 3GP when the device's Android decoder supports the file.
- Can create and restore a library backup ZIP only when you explicitly choose the backup or restore action.

NoMute does not upload or stream your imported music or selected cover images to a project server. Music and covers stay in the app's private storage on your device. Listening does not require an account or network connection. The manual update action opens the public Releases page in your browser; it does not send your music or artwork. A backup ZIP is written only to the location you select and is not uploaded by NoMute.

The backup includes imported audio copies, artwork, track metadata, favorites, and playlists. Before you confirm, Restore inspects the ZIP and shows a summary of track, playlist, and favorite counts plus the combined media size; full audio and artwork integrity is checked during restoration. After confirmation, it extracts and validates files in private staging before switching the library. Cancellation is honored during preparation and before commit begins. If the commit fails, Aftertone attempts to restore the previous snapshot and retains transaction data for recovery if cleanup cannot finish.

Restore needs free space for the uncompressed audio and artwork assets plus a 32 MiB safety reserve; the app checks available space before extraction and again during extraction. For safety, a backup is limited to 16 GiB compressed and 16 GiB of total uncompressed ZIP entries (including the manifest), 40,001 ZIP entries total, 2 GiB per audio file, 2 MiB per cover image, and a 20 MiB manifest. Deflated asset entries with a decompression ratio above 200:1 are rejected. DRM-protected streams and playlist links are not downloaded or bypassed. Import only audio you own or are allowed to use.

## Open source and licenses

The NoMute application source in this repository is licensed under **GNU GPL version 3 only (GPL-3.0-only)**; see [`LICENSE`](LICENSE). Under **Settings → Open licenses**, the app provides the GPL license and warranty notice. Its **Third-party licenses** page includes the full Apache-2.0 notice for AndroidX ExifInterface 1.4.2, packaged at [`third_party_notices.txt`](app/src/main/res/raw/third_party_notices.txt). Third-party libraries retain their own licenses.

## Build and test from source

Requirements: JDK 21, Android SDK Platform 37, and Android Build Tools 37.0.0. From a clone of this repository, run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow in `.github/workflows/android-apk.yml` runs verification for pushes to `main`, pull requests targeting `main`, and manual `workflow_dispatch` runs. Pull requests run verification and upload a debug artifact only; they do not publish a GitHub Release. After verification passes, pushes to `main` and manual `workflow_dispatch` on `main` build the release APK and checksum and create a versioned GitHub Release marked latest; the workflow does not wait for a release to be created. Do not put signing keys or passwords in source control.

### APK signing and local data

If the release workflow is not configured with a persistent signing key, it signs with the runner's temporary Android debug key. That certificate can differ between builds; changing or rotating a signing key can also prevent Android from installing an APK as an in-place update. Removing Aftertone deletes its private audio copies, artwork, playlists, and other local app data. Before uninstalling, export a ZIP backup and verify that the archive opens and passes the app's restore check.
