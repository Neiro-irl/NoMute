# NoMute Global Library Order — Scope Specification

## Goal
Let the owner arrange all library tracks in a persistent personal order outside any playlist, and make that order visible and meaningful for whole-library playback.

## Constraints
- Baseline is the exact local NoMute v1.0.39 source candidate; application ID remains `com.neiro.nomute` and visible branding remains `NoMute`.
- Keep all state local; add no server, account, sync, subscription, telemetry, or network dependency.
- Keep the full-library order separate from each playlist's `trackIds` order.
- Use a visible “Изменить порядок” mode with drag handles, not a hidden gesture. Provide accessible move-up/move-down actions in the mode.
- Deliver a local candidate for review. Do not publish, tag, or push without separate explicit approval of the exact artifacts.

## Required behavior
1. The canonical order is `LibrarySnapshot.tracks`; moving one track changes only that list's order and does not change playlist membership/order, favorites, or audio files.
2. The normal library list displays the saved order. Search results preserve the relative saved order of matching tracks.
3. In edit mode, the owner can reorder tracks; the mode has an explicit exit action and clear move affordances. Reordering persists across app restart.
4. Starting playback at a selected track continues through the full library in the stored order, including tracks from other tabs/collections, while the explicit “Слушать подборку” action remains collection-scoped.
5. Existing version-1 library state remains readable; because the order is already serialized as the track list, no independent global-order field or migration is needed.

## Out of scope
A separate sort preference, per-track order metadata, changing playlist order, queue persistence, and publication.

## Definition of done
Focused model/repository/search/playback regressions pass; playlist order remains untouched; full unit tests, lint, and local debug build pass; independent review has no unresolved Critical/Required finding; exact APK/source are delivered privately.
