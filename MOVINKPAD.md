# MovinkPad Pen Preview 1

Based on XNotes `26f42b2945df85087ab8d36a5344aa0e0c949707` (0.9.6), cloned from
https://github.com/shardulvs/xnotes-android on 2026-10-30. The upstream MIT license,
credits and vendored third-party notices are retained. This is a local experimental
build, not an official XNotes release.

## Added

- Independent Pro Pen 3 mappings: button 1 = `0x20`, button 2 = `0x40`, button 3 = `0x04`.
- Preferences → Input → Pro Pen 3 buttons. Each starts unbound. Map to pen,
  highlighter, eraser, pan, select, lasso, undo or redo.
- Tool bindings support HOLD (restore on release) and SELECT (keep selected).
  Commands run once per press. Actions during contact are deferred until pen-up;
  cancellation discards queued actions. Hover selection does not erase without contact.
- Both paged notebooks and the infinite canvas share the same input adapter.
- Checked atomic settings replacement; failed mapping writes report failure and
  retain the previous settings. No document format change.
- Black/white UI palette with neutral grey hierarchy (2026-11-03), without recolouring saved ink or source PDFs.

## Build and install

JDK 21, Gradle 8.13, AGP 8.12.3, Android SDK 36, NDK 27.0.12077973,
CMake 3.22.1. Configure `sdk.dir` in untracked `local.properties`.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.xnotes.movinkpad.test/com.xnotes.pen.PenTestRunner
```

ARM64 only. Debug app ID `com.xnotes.movinkpad` keeps the preview separate from
official XNotes. Do not pass `android.injected.build.abi` for the deliverable: it
marks the APK test-only. The Android runner must report `PASS`, not merely exit 0.

## Validation boundary

- 2386 unit tests passed, including recorded button-state replay and settings failure recovery.
- 6 Android MotionEvent tests passed on an API 32 ARM64 emulator.
- App launch, mapping UI, settings persistence and a saved stylus stroke reopened after force-stop/relaunch checked on the emulator.
- Full lint fails with the same 243 errors as the pristine upstream commit; no new errors in comparison.
- **MovinkPad / Android 15 / Pro Pen 3 hardware testing is still required.** Emulator results do not prove palm rejection, pen latency or physical side-button compatibility.
- Full OneNote Android parity, large scanned-PDF acceptance, and all-tool cursors remain outside this preview's completed scope.

The replay fixture `app/src/test/resources/pro-pen-3-motion.csv` derives sequence,
action and raw button masks from the user-provided 2026-10-30 MovinkPad capture
(source SHA-256 `eec4edd4647d43963c41996313f535c3500c5499617643ffa75ebc6ab023f79a`).
It contains 3907 stylus events and 12/11/6 rising edges for buttons 1/2/3.

## Night ink display (2026-10-31)

Black handwriting on black paper in dark mode is displayed as white in blank note
pages and the infinite canvas. Stored ink colours remain unchanged and PDF-backed
pages are excluded. See `docs/movinkpad/night-ink-2026-10-31.md` for scope and validation.

## Recorded button mappings (development branch, 2026-11-05)

The fixed Pro Pen 3 settings above describe the previous preview. This branch replaces them
with explicit button recording (MotionEvent and KeyEvent), named mappings and HOLD/SELECT
behavior. Old `pro_pen_bindings` are intentionally ignored; users must record again.
No APK is produced for this stage. See [implementation and validation](docs/movinkpad/recordable-pen-shortcuts-2026-11-05.md).
