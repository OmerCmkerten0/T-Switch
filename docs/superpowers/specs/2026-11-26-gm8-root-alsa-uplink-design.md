# GM 8 Root ALSA Direct Uplink (`MultiMedia2`/`MultiMedia9`) & Clean Unity Downlink Design

## Problem Statement
1. **Uplink silence (`AudioTrack` trapped on `MultiMedia1`/`AFE-PROXY`)**:
   - On General Mobile GM 8 (`msm8952-snd-card-mtp` / `audio.primary.msm8937.so`), `/vendor/etc/mixer_paths_mtp.xml` routes digital modem TX injection (`incall_music`) strictly through:
     - `MultiMedia2` (ALSA card `0`, device `1` -> `/dev/snd/pcmC0D1p`, mixer `Incall_Music Audio Mixer MultiMedia2`) for `VoiceMMode1` (SIM 1).
     - `MultiMedia9` (ALSA card `0`, device `16` -> `/dev/snd/pcmC0D16p`, mixer `Incall_Music_2 Audio Mixer MultiMedia9`) for `VoiceMMode2` (SIM 2).
   - Stock ROM `audio_policy_configuration.xml` lacks an accessible `incall_music_uplink` output mixPort, so `AudioTrack` either lands on `MultiMedia1` (speaker) or `AFE-PROXY RX` (`pcm7p`, which is a DSP sink rather than a modem TX source).
   - Built-in `/system/bin/tinyplay` uses `fseek()` on WAV headers and cannot stream live 20 ms RTP frames from `stdin`.
   - `SELinux` is `Enforcing`, requiring root (`su`) context (`u:r:magisk:s0`) to open `/dev/snd/pcmC0D*p`.
2. **Incoming caller audio hiss & clipping (`VOICE_DOWNLINK@16k`)**:
   - `DeviceProfile.msm8937()` used `captureGain = 3`, `playbackGain = 2`, and `playbackLeaksIntoCapture = true`.
   - Live call measurements showed `VOICE_DOWNLINK@16k` already arrives at full digital scale (`rawCapRMS = 1987`). Multiplying by `3` hard-clipped speech peaks and boosted modem background comfort noise by `+9.5 dB`, while `playbackLeaksIntoCapture = true` gated/chopped caller speech.

## Verified Hardware Parameters
From `/vendor/lib64/hw/audio.primary.msm8937.so` (`pcm_config_incall_music`, `pcm_device_table`, `use_case_table`) and `/system/lib64/libtinyalsa.so` on GM 8 (`14d79a6b`):
- `pcm_device_table[45]` (`incall_music_uplink`): card `0`, device `1`
- `pcm_device_table[46]` (`incall_music_uplink2`): card `0`, device `16`
- `pcm_config_incall_music`:
  - `channels = 1` (mono)
  - `rate = 48000` Hz
  - `period_size = 240` frames (5 ms)
  - `period_count = 4` (20 ms buffer, fallback to `2`)
  - `format = 0` (`PCM_FORMAT_S16_LE`)
  - `start_threshold = 240`, `stop_threshold = 2147483647`, `avail_min = 240`
- `/system/lib64/libtinyalsa.so` & `/system/lib/libtinyalsa.so` export `pcm_open`, `pcm_is_ready`, `pcm_get_error`, `pcm_write`, `pcm_close`.
- No `libcsd-client.so` exists; `voice.playback.conc.disabled = false`.

## Selected Architecture
- **Bundled Native Root ALSA Streamer (`libalsaplay.so`)**:
  - Compiled via NDK/CMake as a zero-dependency PIE executable named `libalsaplay.so` (packaged via `useLegacyPackaging = true` and `android:extractNativeLibs="true"`).
  - Loaded via `dlopen` on system `libtinyalsa.so` so there is no kernel ABI mismatch.
  - Spawned under `su` during calls (`exec <bin> -D 0 -d <1|16> -r 48000 -c 1 -p 240 -n 4`), streaming raw 48 kHz mono S16_LE 20 ms frames (upsampled continuously from 16 kHz / 8 kHz via stateful linear interpolation in `AlsaPcmPlayback`) over `stdin` directly to `pcmC0D1p` or `pcmC0D16p`.
  - Falls back to standard `AudioTrack` automatically if opening `AlsaPcmPlayback` fails.
- **Speaker & Mic Isolation**:
  - `silenceLocalAudio = false` prevents Telecom/AudioManager mute APIs from asserting global `Voice Tx Mute`.
  - `PRI_MI2S_RX Audio Mixer MultiMedia2 = 0` and `MultiMedia9 = 0` plus `SPK/RX1/RX3/RDAC2 = ZERO` guarantee zero speaker leakage.
- **Clean Unity Downlink**:
  - Set `captureGain = 1`, `playbackGain = 1`, `playbackLeaksIntoCapture = false`, `noiseGateThreshold = 0` in `DeviceProfile.msm8937()`.
