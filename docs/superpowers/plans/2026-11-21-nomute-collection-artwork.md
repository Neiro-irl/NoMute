# NoMute Collection Artwork Implementation Plan

> **For agentic workers:** Implement task by task with test-first changes and an independent read-only review before the local candidate is delivered.

**Goal:** Add private custom artwork to playlists/collections and preserve it across old/new library state and backup/restore.

**Architecture:** Add an optional playlist artwork path in the local model, with an explicit v1→v2 library-state compatibility path. Reuse the existing bounded image normalization and private-storage rules; extend the portable backup manifest and transactional restore to carry typed playlist-cover assets while continuing to read v1 archives.

**Tech Stack:** Kotlin, Android, Jetpack Compose, Android Photo Picker/content resolver, JUnit 4, ZIP backup/restore.

**Spec:** `../specs/2026-11-21-nomute-collection-artwork.md`

## Global Constraints
- Application ID `com.neiro.nomute`; visible name `NoMute`.
- Artwork remains local and app-private; no URI persistence or network transfer.
- Keep image size/dimension bounds and ownership/path guards.
- Preserve old version-1 state and backup readability.
- Build a local APK candidate only; no publication, tag, or push without explicit approval of the exact artifacts.

## Review Focus
1. Image-picker URI or file content is untrusted: malformed, empty, oversized, wrong-format, and extreme-dimension inputs must fail without changing the old cover.
2. A library state or archive may try to associate another playlist's image: ownership and type/path mismatches must be rejected.
3. A replacement or playlist deletion must not delete a track cover, another playlist's cover, audio, or unrelated file.
4. An older state/archive has no collection-art field/assets: restore must still succeed and use the existing first-track fallback.
5. A malformed or duplicate archive asset path must fail before transactional replacement and leave the current library intact.

---

### Task 1: Add playlist artwork model and backward-compatible state codec

**Files:**
- Modify: `app/src/main/java/com/neiro/aftertone/model/LibraryModels.kt`
- Modify: `app/src/main/java/com/neiro/aftertone/model/LibraryStateCodec.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/model/LibraryPersistenceTest.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/model/LibraryDomainTest.kt` or add `PlaylistArtworkFileNameTest.kt`

**Interfaces:** Add `Playlist.artworkPath: String? = null` and a typed safe filename helper for collection covers. Encoding uses state version 2; decoding accepts v1 with `artworkPath=null` and v2 with the optional field.

- [ ] Add tests for reading a literal legacy v1 state with no cover and for v2 encode/decode preserving the playlist artwork path.
- [ ] Add filename-policy tests for valid and invalid playlist IDs/cover UUIDs and track-vs-playlist namespace distinction.
- [ ] Run focused persistence/domain tests and verify the legacy test fails only because the decoder does not yet accept the new design.
- [ ] Implement the optional field and explicit v1/v2 decode; keep all prior v1 field validation.
- [ ] Run focused tests and `git diff --check`.
- [ ] Commit only this task's files.

### Task 2: Import, replace, and delete playlist covers safely

**Files:**
- Modify: `app/src/main/java/com/neiro/aftertone/storage/LocalArtworkImporter.kt` only as needed to share bounded normalization safely
- Modify: `app/src/main/java/com/neiro/aftertone/storage/LocalLibraryRepository.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/storage/LocalArtworkImporterTest.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/storage/LocalLibraryRepositoryImportTest.kt`

**Interfaces:** Add `LocalLibraryRepository.setPlaylistArtwork(playlistId: String, input: InputStream): LibrarySnapshot`; replacement publishes a new unique playlist-owned local file, persists/readbacks the state, then removes the previous file only if it is still provably owned. `removePlaylist` cleans only its owned cover after durable state removal.

- [ ] Add failing repository tests for setting a cover, failed import preserving the existing one, successful replacement cleaning only the previous owned cover, and playlist deletion leaving track artwork/audio untouched.
- [ ] Add importer tests for a malformed image, oversize source, symlink/unowned path rejection, and unique collection-owned filename.
- [ ] Run focused tests to observe the expected failures.
- [ ] Implement import and repository ownership/cleanup using existing direct-child, no-follow, and bounded image-processing patterns.
- [ ] Run focused tests plus existing track-artwork regressions.
- [ ] Commit only this task's files.

### Task 3: Expose cover selection and rendering in collection UI

**Files:**
- Modify: `app/src/main/java/com/neiro/aftertone/AftertoneApp.kt`
- Modify: `app/src/main/java/com/neiro/aftertone/ui/screens/CollectionsScreen.kt`
- Modify: `app/src/main/java/com/neiro/aftertone/ui/screens/CollectionDetailScreen.kt`
- Add or modify: a focused collection-artwork UI state/presentation test under `app/src/test/java/com/neiro/aftertone/ui/`

**Interfaces:** Keep the existing picker flow local; pass a playlist ID into the cover-selection callback. Both collection card and detail hero prefer the playlist's own artwork and retain the current first-track artwork when absent. Add an explicit “Изменить обложку” action with accessible description.

- [ ] Add a failing presentation test for custom-cover precedence and first-track fallback.
- [ ] Implement the picker callback and collection-card/detail affordances, following the existing NoMute color, spacing, and screen patterns.
- [ ] Run focused UI/presentation tests and lint.
- [ ] Commit only this task's files.

### Task 4: Extend backup/restore compatibly for playlist art

**Files:**
- Modify: `app/src/main/java/com/neiro/aftertone/storage/LibraryBackupManifestCodec.kt`
- Modify: `app/src/main/java/com/neiro/aftertone/storage/LibraryBackupArchive.kt`
- Modify: `app/src/main/java/com/neiro/aftertone/storage/LibraryRestoreTransaction.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/storage/LibraryBackupArchiveTest.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/storage/LibraryBackupTenTrackRoundTripTest.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/storage/LibraryRestoreTransactionTest.kt`

**Interfaces:** New exports use manifest version 2 with an exact playlist-art asset reference and bounded size; decoder continues to accept manifest v1 and legacy state v1. Restore stages/validates type-specific names and ownership, reconstructs playlist artwork under private storage, and includes it in rollback/cleanup accounting.

- [ ] Add failing tests for a v1 archive without playlist art, v2 round-trip of playlist art, duplicate/path-traversal/cross-playlist references, and rollback leaving existing library/artwork unchanged.
- [ ] Run focused archive/restore tests and observe the intended failures.
- [ ] Extend manifest version compatibility, safe asset allowlist, export, staging, restore validation, and cleanup as one transaction.
- [ ] Run focused archive/restore tests and existing old-archive fixtures.
- [ ] Commit only this task's files.

### Task 5: Verify and package local candidate

**Files:**
- Modify: `app/build.gradle.kts` only to set the next consistent candidate version if required by the build workflow.
- Deliver: exact APK, source ZIP, and scoped patch.

- [ ] Run `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- [ ] Independently review the precise feature diff and verify no Critical/Required issue remains.
- [ ] Verify APK package, version, signer, alignment, and SHA-256; confirm existing app data migration path.
- [ ] Deliver exact artifacts and state clearly that nothing has been published.
