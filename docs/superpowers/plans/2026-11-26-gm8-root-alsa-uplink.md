# GM 8 Root ALSA Direct Uplink (`MultiMedia2`/`MultiMedia9`) & Clean Unity Downlink Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route decoded SIP playback audio directly to GM 8's Qualcomm modem uplink (`pcmC0D1p`/`pcmC0D16p`) via a bundled root `libalsaplay.so` helper while fixing incoming `VOICE_DOWNLINK@16k` hiss/clipping with unity digital gain.

**Architecture:** Bypass Android `AudioTrack` / `audio_policy_configuration.xml` limitations on GM 8 (`msm8937`) using a PIE C helper (`libalsaplay.so`) that `dlopen`s system `libtinyalsa.so` under `su` and streams 48 kHz mono PCM frames read from `stdin`. Resample 8/16 kHz RTP frames continuously in Kotlin (`Pcm48kResampler`), keep physical speaker/mic disconnected via `tinymix`, and set `captureGain = 1`, `playbackGain = 1`, `playbackLeaksIntoCapture = false`.

**Tech Stack:** Android NDK/CMake (C PIE binary + `dlopen` `libtinyalsa.so`), Kotlin (`AlsaPcmPlayback`, `DeviceProfile`, `RtpSession`), JUnit4, ADB/GM 8 hardware verification.

**Spec:** `docs/superpowers/specs/2026-11-26-gm8-root-alsa-uplink-design.md`

## Global Constraints
- `minSdk = 26`, `compileSdk = 34`, `targetSdk = 34`.
- Only applies when `DeviceProfile.useRootAlsaPlayback == true` (`msm8937()`), leaving other profiles (`msm8930`, `sm6150`, `exynos9820`) untouched.
- If `AlsaPcmPlayback.open()` fails on device, gracefully fall back to `AudioTrack` rather than crashing or dropping the call.
- Maintain `PRI_MI2S_RX Audio Mixer MultiMedia2 = 0` and `MultiMedia9 = 0` so injected audio cannot leak to the GM 8 speaker.

---

### Task 1: Design Spec & Plan Documentation
- [ ] Write `docs/superpowers/specs/2026-11-26-gm8-root-alsa-uplink-design.md` and `docs/superpowers/plans/2026-11-26-gm8-root-alsa-uplink.md` and commit.

### Task 2: Bundled Native Root ALSA Helper (`libalsaplay.so`)
- [ ] Update `app/src/main/AndroidManifest.xml` (`android:extractNativeLibs="true"`).
- [ ] Update `app/build.gradle` (`ndk.abiFilters`, `externalNativeBuild.cmake`, `packagingOptions.jniLibs.useLegacyPackaging true`).
- [ ] Create `app/src/main/cpp/CMakeLists.txt` and `app/src/main/cpp/alsaplay.c`.

### Task 3: TDD `Pcm48kResampler` & `AlsaPcmPlayback`
- [ ] Write failing unit tests in `app/src/test/java/com/callagent/gateway/rtp/AlsaPcmPlaybackTest.kt`.
- [ ] Implement `Pcm48kResampler` and `AlsaPcmPlayback` in `app/src/main/java/com/callagent/gateway/rtp/AlsaPcmPlayback.kt`.
- [ ] Run unit tests to verify PASS.

### Task 4: Wire `DeviceProfile.msm8937()` & `RtpSession`
- [ ] Add `useRootAlsaPlayback` to `DeviceProfile`, enable on `msm8937()`, set `captureGain = 1`, `playbackGain = 1`, `playbackLeaksIntoCapture = false`, clean `AFE_PCM_TX` mixers, and cut `PRI_MI2S_RX` for `MultiMedia2`/`MultiMedia9`.
- [ ] Integrate `AlsaPcmPlayback` into `RtpSession.kt` (`initAudio`, `playbackLoop`, `stop` release path).

### Task 5: Full Verification, Live GM 8 Verification, Commit, & Google Drive Upload
- [ ] Run `.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`, confirm `0` failures/errors across all suites and verify `lib/arm64-v8a/libalsaplay.so` in APK.
- [ ] Install on connected GM 8 (`14d79a6b`), verify `libalsaplay.so --check` prints `OK tinyalsa` and `Registered with sip.linphone.org` in logcat.
- [ ] Commit implementation changes to `main` and upload APK to Google Drive folder `1NGxleOsEfmpYS7X2SnUDkyLqYSvnnaUv`.
