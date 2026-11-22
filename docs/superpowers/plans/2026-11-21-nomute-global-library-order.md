# NoMute Global Library Order Implementation Plan

> **For agentic workers:** Implement task by task with test-first changes and an independent read-only review before the local candidate is delivered.

**Goal:** Add a persistent personal order for all library tracks, independent of playlist order, with a discoverable edit mode and consistent full-library playback.

**Architecture:** Use `LibrarySnapshot.tracks` as the canonical order already serialized by `LibraryStateCodec` and consumed by the full-library playback queue. Add a tested move operation/repository persistence method, preserve incoming order during filtering, and expose an explicit drag-and-drop mode with accessible move-up/down controls.

**Tech Stack:** Kotlin, Android, Jetpack Compose, Media3, JUnit 4.

**Spec:** `../specs/2026-11-21-nomute-global-library-order.md`

## Global Constraints
- Application ID `com.neiro.nomute`; visible name `NoMute`.
- All state remains local; no new service or network dependency.
- Global order must remain distinct from `Playlist.trackIds` and session queue state.
- Preserve selected-track full-library playback across tabs; explicit collection playback stays scoped to that collection.
- Build a local APK candidate only; no publication, tag, or push without explicit approval of the exact artifacts.

## Review Focus
1. Invalid/missing IDs and out-of-range destinations must be no-ops and never drop or duplicate tracks.
2. Existing v1 state and restart persistence must retain the order exactly.
3. Reordering must not mutate any playlist membership/order, favorites, file paths, or cover references.
4. Empty search and filtered results must preserve the user's relative order, while a new playlist continues to use its own order.
5. Starting from any selected library track must play the full updated library in saved order; “Слушать подборку” remains collection-scoped.

---

### Task 1: Add a deterministic whole-library move operation

**Files:**
- Modify: `app/src/main/java/com/neiro/aftertone/model/LibraryModels.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/model/LibraryDomainTest.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/model/LibraryPersistenceTest.kt`

**Interfaces:** Add `LibrarySnapshot.moveTrack(trackId: String, destinationIndex: Int): LibrarySnapshot`. It moves exactly one existing track within `tracks`; missing ID, invalid destination, and same-position requests return the original snapshot. Playlist data and favorites remain equal.

- [ ] Add failing tests for a valid move, invalid/missing/no-op destinations, and unchanged playlist/favorite state.
- [ ] Add a v1 codec round-trip regression proving track-list order is retained without introducing a new schema field.
- [ ] Run focused model tests to observe the expected failures.
- [ ] Implement the minimal immutable move operation.
- [ ] Run focused model tests and `git diff --check`.
- [ ] Commit only this task's files.

### Task 2: Persist library reordering through the repository

**Files:**
- Modify: `app/src/main/java/com/neiro/aftertone/storage/LocalLibraryRepository.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/storage/LocalLibraryRepositoryImportTest.kt`

**Interfaces:** Add `LocalLibraryRepository.moveTrackInLibrary(trackId: String, destinationIndex: Int): LibrarySnapshot`, guarded by the repository's existing mutation lock and using its standard persist/readback path.

- [ ] Add failing repository tests that move a track, reload the repository, and confirm the order remains while a playlist's independent order is unchanged.
- [ ] Run focused repository tests to verify the missing operation is the reason for failure.
- [ ] Implement the repository action using `LibrarySnapshot.moveTrack`.
- [ ] Run focused repository and persistence tests.
- [ ] Commit only this task's files.

### Task 3: Preserve canonical order in filtering and library UI

**Files:**
- Modify: `app/src/main/java/com/neiro/aftertone/model/LocalTrackSearch.kt`
- Modify: `app/src/main/java/com/neiro/aftertone/ui/screens/LibraryScreen.kt`
- Modify: `app/src/main/java/com/neiro/aftertone/AftertoneApp.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/model/LocalTrackSearchTest.kt`
- Add or modify: a focused order-edit presentation/UI-state test under `app/src/test/java/com/neiro/aftertone/ui/`

**Interfaces:** `filterLocalTracks` preserves the input order for both blank and matching queries. Add a visible `isEditingOrder` mode in `LibraryScreen`, entered by “Изменить порядок” and exited by “Готово”; show drag handles, plus accessible up/down actions. Disable/restrict reordering while a nonblank query is active, with short explanatory text. Wire moves through `AftertoneApp` to `moveTrackInLibrary`.

- [ ] Add failing tests proving empty and matching queries preserve the relative order supplied by the caller.
- [ ] Add a focused test for edit-mode availability/visible action labels if the existing test setup supports the relevant UI state; otherwise test the extracted pure presentation state.
- [ ] Run focused search tests and verify the old title-sort expectation fails as intended.
- [ ] Implement order-preserving filtering and edit-mode controls, matching existing NoMute visual/accessible patterns.
- [ ] Run focused UI/presentation and model tests plus `:app:lintDebug`.
- [ ] Commit only this task's files.

### Task 4: Verify the player consumes saved order and retains collection scope

**Files:**
- Inspect/modify only if needed: `app/src/main/java/com/neiro/aftertone/playback/PlaybackQueueSnapshot.kt`
- Inspect/modify only if needed: `app/src/main/java/com/neiro/aftertone/AftertoneApp.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/playback/PlaybackQueueTest.kt`
- Modify: `app/src/test/java/com/neiro/aftertone/model/LibraryDomainTest.kt`

**Interfaces:** Full-library playback receives the saved `LibrarySnapshot.tracks` sequence and starts at the selected track; collection playback remains based on `Playlist.tracksInOrder` only.

- [ ] Add failing regression tests for queue order after reordering and for selected-track start behavior; retain the explicit collection-scoped queue assertion.
- [ ] Run the focused playback test to establish the regression.
- [ ] Change production playback code only if the new regression demonstrates that existing queue snapshot behavior does not already consume canonical order.
- [ ] Run all focused model/search/repository/playback tests.
- [ ] Commit only this task's files.

### Task 5: Verify and package local candidate

**Files:**
- Modify: `app/build.gradle.kts` only to set the next consistent candidate version if required by the build workflow.
- Deliver: exact APK, source ZIP, and scoped patch.

- [ ] Run `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- [ ] Independently review the precise feature diff and verify no Critical/Required issue remains.
- [ ] Verify APK package, version, signer, alignment, and SHA-256; confirm existing app data migration path.
- [ ] Deliver exact artifacts and state clearly that nothing has been published.
