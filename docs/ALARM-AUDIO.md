# Bundled alarm audio

The selectable **Pulse**, **Rising chime** and **Steady bell** sounds are original synthesized waveforms
created for MeBot. They contain no downloaded recordings, sampled instruments,
third-party music, speech or external sound effects. The existing
`app/src/main/res/raw/alarm_tone.wav` remains unchanged.

The generator and its three generated recordings are distributed under the
project's Apache License, Version 2.0. Copyright 2026 MeBot contributors.
See `LICENSE` and `NOTICE` at the project root. No external attribution is needed.

| Android resource | Pattern | Loop duration | WAV size |
| --- | --- | --- | --- |
| `R.raw.alarm_pulse` | Eight bright pulses with alternating pitch; the most sustained option | 3.2 seconds | 141,164 bytes |
| `R.raw.alarm_chime` | Two rising groups of four notes with overlapping decays | 4.8 seconds | 211,724 bytes |
| `R.raw.alarm_bell` | Four evenly spaced bell strikes with decaying partials | 4.0 seconds | 176,444 bytes |

All files use uncompressed, little-endian PCM16, mono, at 22,050 Hz. Together they
add 529,332 bytes before APK compression. They share a normalized sample peak of
86% of full scale, about −1.31 dBFS, retaining headroom without clipping. Their
different timing and decay patterns intentionally produce different perceived
loudness; Pulse has the most sustained energy. Playback volume remains controlled
by the user's alarm setting and the device's alarm audio path.

Each note has a smooth attack and release. Every file includes zero-valued samples
at both loop boundaries, including a 10 ms silent guard on each side, so repeating
the PCM data creates no discontinuity. Pitch components stay below Nyquist.
Actual speaker loudness and overnight alarm behavior still require device testing.

## Use in the app

Open an alarm's **Sound & snooze** section to select these sounds or **Classic**,
the original tone. **Phone alarm sounds** and **Choose audio file** use Android's
pickers. MeBot saves the selected file's URI and display name, not a copy of its
audio bytes. An unavailable selection falls back to bundled audio.

**Preview sound** plays for up to 12 seconds at the phone's current alarm volume;
it does not change that volume or apply the plan's saved volume and ramp. It stops
when you leave the editor, select another sound, save, start a test, or an active
alarm takes over. A scheduled alarm uses its saved volume and gradual ramp.
Optional **Spoken wake-up message** uses an installed offline voice and starts
after the ramp, with a minimum delay of 1.5 seconds. The preview demonstrates the
message after about one second instead. Speech supplements the tone and is not
part of these WAV files.

## Reproduce or verify

Python 3.9 or later is sufficient. The script uses only the standard library,
with fixed synthesis parameters and no random seed or external inputs.

```sh
python3 scripts/generate-alarm-tones.py
python3 scripts/generate-alarm-tones.py --check
```

Generation writes only `alarm_pulse.wav`, `alarm_chime.wav` and `alarm_bell.wav`.
It never changes the original `alarm_tone.wav`. An optional `--output-dir` writes
or checks another directory.

The check regenerates expected bytes and compares them with each bundled file.
It also checks WAV headers, duration, file size, nonzero signal energy, normalized
peak, absence of clipped samples, low DC offset and silent loop seams. Its report
includes RMS level and SHA-256 for each asset.
