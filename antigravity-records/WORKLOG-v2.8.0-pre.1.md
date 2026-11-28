# WORKLOG v2.8.0-pre.1

## Scope
1. Permanent personal release keystore (Scheme B): keystore kept at `E:\antigravity\keystore\ai-tts-release.jks` (outside repo, never committed). SHA-1 `99:00:20:37:85:BF:FC:1C:56:15:E2:54:2C:3C:49:8F:A9:E8:8D:DD` registered as Android OAuth client `AI-TTS Android Release` in Google Cloud project `gemini`; signing values stored in `local.properties` (gitignored) and GitHub Secrets. CI only uploads artifacts when all four secrets exist and no longer publishes releases (releases are published manually via `gh`).
2. Moon+ Reader (`.Moon+`) read-only inheritance: `MoonReaderBackupParser` (core-reader, zlib books.sync / .po position / .an highlights+bookmarks) and `MoonReaderDriveMigrator` (scan Books + `.Moon+` on Drive, import books, progress, notes, shelf group, title/added time, fallback cover).
3. Two-phase safe sync: `SyncPlanBuilder` + `LibrarySyncEngine` + `LibrarySyncSupport`; deletions only after explicit per-item approval (default unchecked), approved local delete requires id+name+size+mtime match, skipped deletions stay in the manifest, failures surface as errors.
4. Cloud file manager redesign: back navigation, breadcrumb/filter bar, multi-select, upload, move, batch delete, imported badges, sync options sheet, Moon+ dialog.

## Verification
- `scripts/check_architecture.py`: 0 violations; Detekt 0 issues; Android Lint passed.
- `gradlew qualityGate assembleR8Release`: BUILD SUCCESSFUL, 76/76 JVM unit tests (app 7, core-engine 44, core-model 6, core-reader 19).
- R8 APKs signed with release key SHA-1 `9900203785bffc1c5615e2542c3c498fa9e88ddd`: cloud 6,664,940 B (SHA-256 5CD50FA9F2CB5C29C9A36EA49EC9306EE04070BB82545A4E7897DC33936C88FF), full 30,355,946 B (SHA-256 85FDB3CD1C4CB2AB1AEE52E579A9129FBC0DC8B5236188D34210A8221092A70E).
- Qodo: 2 rounds. Round 1 findings in this diff fixed. Round 2 `action_required` items in this diff (upload deletes before read, CI `secrets` in step `if`) fixed afterwards plus sort-arrow, nested-folder path, manifest-write error, duplicate-title match. Not re-reviewed (round cap). Findings about unrelated pre-existing code (subtitles, providers, reader) were not touched. Open minor: Moon+ cache/cover listing or sidecar download failures are not counted as failures.
- Emulator UI check NOT completed: the `pixel_34` emulator process exited right after WHPX init in this environment; flash subagent hit 429 quota. UI is therefore unverified on a device.
