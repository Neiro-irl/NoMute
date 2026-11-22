# NoMute Collection Artwork — Scope Specification

## Goal
Let the owner choose a private, local image for each playlist/collection, keep the existing first-track artwork as the fallback, and preserve custom collection artwork through backup and restore.

## Constraints
- Baseline is the exact local NoMute v1.0.39 source candidate; application ID remains `com.neiro.nomute` and visible branding remains `NoMute`.
- Artwork bytes stay in app-private storage. Persist only validated local paths; never retain picker URIs or upload images.
- Reuse the existing bounded image import/normalization behavior where practical; reject unsafe paths and malformed ownership references.
- Replacing/removing a cover may delete only the old file owned by that playlist, and only after the new library state is durably saved.
- New backups include playlist covers; existing version-1 library states and backup archives without them remain restorable.
- Deliver a local candidate for review. Do not publish, tag, or push without separate explicit approval of the exact artifacts.

## Required behavior
1. The playlist card and playlist detail screen show the chosen image when present; otherwise they keep the current first-track artwork/fallback behavior.
2. The owner can choose/change a playlist cover from visible collection UI using the existing system image picker.
3. A selected image is copied, validated, orientation-corrected, bounded, and normalized into app-private storage.
4. Deleting a playlist removes only that playlist's custom cover; deleting/reordering tracks does not remove it.
5. New backups round-trip playlist covers. Old backups decode as collections without custom covers.
6. Unsafe names, path traversal, symlinks, missing files, cross-playlist references, unexpected files, and file-size violations are rejected without partial restore or unrelated file deletion.

## Out of scope
Remote artwork, account/cloud sync, generated artwork, custom cover for favorites, editing original audio tags, and publication.

## Definition of done
Focused model/repository/image/archive/restore tests pass; old schema and old backup fixtures remain readable; full unit tests, lint, and local debug build pass; independent review has no unresolved Critical/Required finding; exact APK/source are delivered privately.
