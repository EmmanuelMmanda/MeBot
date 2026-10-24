#!/usr/bin/env python3
"""Generate MeBot's original, loopable alarm choices using only Python's stdlib.

Run without arguments to write the three WAVs. Run with --check to verify the
bundled files against freshly synthesized bytes without changing any files.
SPDX-License-Identifier: Apache-2.0
Copyright 2026 MeBot contributors
"""

from __future__ import annotations

import argparse
import hashlib
import io
import math
from pathlib import Path
import struct
import wave


SAMPLE_RATE = 22_050
PEAK_FRACTION = 0.86
PCM_MAX = 32_767
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
# Ratio to the fundamental, amplitude, decay rate per second.
Partial = tuple[float, float, float]


def add_note(
    track: list[float], start: float, duration: float, frequency: float,
    partials: tuple[Partial, ...], *, attack: float = 0.012,
    release: float = 0.05, velocity: float = 1.0,
) -> None:
    """Add an additive-synthesis note with a smooth, zero-ended envelope."""
    offset = round(start * SAMPLE_RATE)
    frames = round(duration * SAMPLE_RATE)
    assert offset >= 0 and offset + frames <= len(track)
    assert attack > 0 and release > 0 and attack + release < duration
    assert all(0 < frequency * ratio < SAMPLE_RATE / 2 for ratio, _, _ in partials)
    for index in range(frames):
        elapsed = index / SAMPLE_RATE
        remaining = (frames - 1 - index) / SAMPLE_RATE
        rise = math.sin(math.pi / 2 * min(elapsed / attack, 1.0)) ** 2
        fall = math.sin(math.pi / 2 * min(remaining / release, 1.0)) ** 2
        signal = sum(
            amplitude * math.exp(-decay * elapsed)
            * math.sin(math.tau * frequency * ratio * elapsed)
            for ratio, amplitude, decay in partials
        )
        track[offset + index] += signal * rise * fall * velocity


def pulse() -> list[float]:
    """Eight assertive, bright pulses with changing pitch; a 3.2-second loop."""
    track = [0.0] * round(3.2 * SAMPLE_RATE)
    partials = ((1.0, 0.82, 0.0), (2.0, 0.20, 0.0), (3.0, 0.08, 0.0))
    for index, frequency in enumerate((784.0, 988.0, 1176.0, 988.0) * 2):
        add_note(track, 0.035 + index * 0.4, 0.28, frequency, partials,
                 attack=0.012, release=0.035)
    return track


def chime() -> list[float]:
    """Two rising four-note chimes, with overlapping decays; a 4.8-second loop."""
    track = [0.0] * round(4.8 * SAMPLE_RATE)
    partials = ((1.0, 0.78, 3.4), (2.0, 0.22, 5.0),
                (2.76, 0.09, 7.0), (3.0, 0.07, 7.5))
    for phrase in (0.0, 2.4):
        for index, frequency in enumerate((523.25, 659.25, 783.99, 1046.50)):
            add_note(track, phrase + 0.04 + index * 0.4, 1.0, frequency,
                     partials, attack=0.008, release=0.08, velocity=0.90 + 0.03 * index)
    return track


def bell() -> list[float]:
    """Four evenly timed bell strikes with inharmonic partials; a 4-second loop."""
    track = [0.0] * (4 * SAMPLE_RATE)
    partials = ((1.0, 0.68, 3.4), (2.0, 0.28, 4.6),
                (2.76, 0.22, 7.5), (4.07, 0.10, 10.5))
    for index in range(4):
        add_note(track, 0.04 + index, 0.88, 659.25, partials,
                 attack=0.008, release=0.08)
    return track


GENERATORS = {"alarm_pulse.wav": pulse, "alarm_chime.wav": chime, "alarm_bell.wav": bell}


def encode(track: list[float]) -> bytes:
    peak = max(abs(value) for value in track)
    assert peak > 0, "Cannot normalize an empty signal"
    gain = PCM_MAX * PEAK_FRACTION / peak
    samples = [round(value * gain) for value in track]
    assert max(abs(value) for value in samples) < PCM_MAX, "Clipping is never allowed"
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(struct.pack(f"<{len(samples)}h", *samples))
    return output.getvalue()


def inspect(name: str, payload: bytes) -> str:
    with wave.open(io.BytesIO(payload), "rb") as wav:
        assert wav.getnchannels() == 1, f"{name}: expected mono"
        assert wav.getsampwidth() == 2, f"{name}: expected PCM16"
        assert wav.getframerate() == SAMPLE_RATE, f"{name}: incorrect sample rate"
        assert wav.getcomptype() == "NONE", f"{name}: expected uncompressed PCM"
        frames = wav.getnframes()
        raw = wav.readframes(frames)
    assert len(raw) == frames * 2, f"{name}: truncated samples"
    assert len(payload) == 44 + len(raw), f"{name}: unexpected WAV header or trailing data"
    assert 2 * SAMPLE_RATE <= frames <= 6 * SAMPLE_RATE, f"{name}: unexpected loop duration"
    assert len(payload) < 256_000, f"{name}: asset is larger than expected"
    samples = struct.unpack(f"<{frames}h", raw)
    peak = max(abs(value) for value in samples)
    rms = math.sqrt(sum(value * value for value in samples) / frames) / PCM_MAX
    clipped = sum(abs(value) >= PCM_MAX for value in samples)
    dc = sum(samples) / frames / PCM_MAX
    guard = round(0.01 * SAMPLE_RATE)
    assert all(value == 0 for value in samples[:guard] + samples[-guard:]), f"{name}: nonzero loop seam"
    assert clipped == 0, f"{name}: clipped samples"
    assert abs(peak / PCM_MAX - PEAK_FRACTION) <= 1 / PCM_MAX, f"{name}: incorrect normalized peak"
    assert 0.08 < rms < PEAK_FRACTION, f"{name}: silent or implausible signal"
    assert abs(dc) < 0.001, f"{name}: excessive DC offset"
    digest = hashlib.sha256(payload).hexdigest()
    return (f"{name}: {frames / SAMPLE_RATE:.1f}s, {len(payload)} bytes, "
            f"peak={20 * math.log10(peak / PCM_MAX):.2f}dBFS, "
            f"RMS={20 * math.log10(rms):.2f}dBFS, clipped={clipped}, "
            f"seam=0, sha256={digest}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify existing files without writing them")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    arguments = parser.parse_args()
    if not arguments.check:
        arguments.output_dir.mkdir(parents=True, exist_ok=True)
    for name, generator in GENERATORS.items():
        expected = encode(generator())
        path = arguments.output_dir / name
        if arguments.check:
            actual = path.read_bytes()
            assert actual == expected, f"{name}: bundled bytes differ; regenerate the tones"
        else:
            path.write_bytes(expected)
        print(inspect(name, expected))


if __name__ == "__main__":
    main()
