#!/usr/bin/env python3
"""Builds the bundled electric-guitar sample bank (RWSB format) from Karoryfer Emilyguitar (CC0-1.0).

Source: https://github.com/sfzinstruments/karoryfer.emilyguitar (Epiphone, recorded direct, both pickups,
d'Addario flatwounds; notes every minor third Db2..D6, 4 velocity layers x 3 round robins).
Usage: python3 -I tools/make_guitar_bank.py <emilyguitar checkout> <output.rwsb>

What the script does (deterministic, standard library only):
- keeps velocity layers mp/mf/f and all 3 round robins, converts 24-bit to 16-bit,
- trims every note to KEEP seconds and prepares a loop over its last part, crossfaded so it is seamless,
- measures the natural decay (dB/s) so the engine can keep decaying while it loops,
- adds the release (string-damping) and muted (dead-note) samples.

RWSB v1, little endian: "RWSB" u16 version u32 sampleRate u32 count, then per region:
u8 kind (0 note, 1 release, 2 dead), u8 rootMidi, u8 loKey, u8 hiKey, u8 layer, u8 rr, f32 decayDbPerSec,
u32 loopStart, u32 loopEnd (0 = no loop), u32 frames, frames * i16.
"""
import math
import struct
import sys
import wave
from pathlib import Path

KEEP = 2.0
LOOP_SPAN = 0.35
XFADE = 0.04
LAYERS = ['mp', 'mf', 'f']
NAMES = {'c': 0, 'db': 1, 'd': 2, 'eb': 3, 'e': 4, 'f': 5, 'gb': 6, 'g': 7, 'ab': 8, 'a': 9, 'bb': 10, 'b': 11}


def midi_of(name):
    i = len(name) - 1
    while name[i].isdigit():
        i -= 1
    return 12 * (int(name[i + 1:]) + 1) + NAMES[name[:i + 1]]


def read24(path):
    with wave.open(str(path)) as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 3, path
        rate = w.getframerate()
        raw = w.readframes(w.getnframes())
    out = []
    for k in range(0, len(raw) - 2, 3):
        v = raw[k] | (raw[k + 1] << 8) | (raw[k + 2] << 16)
        if v & 0x800000:
            v -= 1 << 24
        out.append(v / 8388608.0)
    return rate, out


def rms(xs):
    return math.sqrt(sum(x * x for x in xs) / max(1, len(xs))) + 1e-12


def note_region(samples, rate, root):
    keep = min(len(samples), int(KEEP * rate))
    x = samples[:keep]
    # Decay from the RMS of two windows after the attack.
    a, b = int(0.4 * rate), int((KEEP - 0.25) * rate)
    w = int(0.1 * rate)
    decay = 20 * math.log10(rms(x[a:a + w]) / rms(x[b:b + w])) / ((b - a) / rate)
    decay = max(0.5, min(decay, 30.0))
    # Loop: whole number of periods over the last LOOP_SPAN seconds, crossfaded into the loop start.
    period = rate / (440.0 * 2 ** ((root - 69) / 12))
    loop_end = keep - int(0.01 * rate)
    periods = max(1, round(LOOP_SPAN * rate / period))
    loop_len = round(periods * period)
    loop_start = loop_end - loop_len
    xf = int(XFADE * rate)
    y = list(x)
    for i in range(xf):
        t = i / xf
        g_in, g_out = math.sin(t * math.pi / 2), math.cos(t * math.pi / 2)
        # The samples just before loop_end blend into those just before loop_start, so the jump is seamless.
        y[loop_end - xf + i] = x[loop_end - xf + i] * g_out + x[loop_start - xf + i] * g_in
    return y[:loop_end], decay, loop_start, loop_end


def pcm16(xs):
    return struct.pack('<%dh' % len(xs), *(max(-32768, min(32767, int(round(v * 32767)))) for v in xs))


def main(src, dst):
    src = Path(src)
    regions = []
    roots = sorted({midi_of(p.name.split('_')[0]) for p in (src / 'notes').glob('*_f_rr1.wav')})
    for idx, root in enumerate(roots):
        lo = 0 if idx == 0 else root - 1
        hi = 127 if idx == len(roots) - 1 else root + 1
        for layer, lname in enumerate(LAYERS):
            for rr in (1, 2, 3):
                name = next(p for p in (src / 'notes').glob(f'*_{lname}_rr{rr}.wav') if midi_of(p.name.split('_')[0]) == root)
                rate, x = read24(name)
                y, decay, ls, le = note_region(x, rate, root)
                regions.append((0, root, lo, hi, layer, rr - 1, decay, ls, le, y, rate))
    for idx, root in enumerate(roots):
        lo = 0 if idx == 0 else root - 1
        hi = 127 if idx == len(roots) - 1 else root + 1
        for rr in (1, 2):
            p = next((q for q in (src / 'release').glob(f'*_release_rr{rr}.wav') if midi_of(q.name.split('_')[0]) == root), None)
            if p is None:
                continue
            rate, x = read24(p)
            regions.append((1, root, lo, hi, 0, rr - 1, 0.0, 0, 0, x, rate))
    for k in range(1, 6):
        for rr in range(1, 6):
            p = src / 'noises' / f'muted{k}_rr{rr}.wav'
            if p.exists() and rr <= 2:
                rate, x = read24(p)
                regions.append((2, 52, 0, 127, k - 1, rr - 1, 0.0, 0, 0, x, rate))
    rate = regions[0][10]
    assert all(r[10] == rate for r in regions)
    with open(dst, 'wb') as f:
        f.write(b'RWSB' + struct.pack('<HII', 1, rate, len(regions)))
        for kind, root, lo, hi, layer, rr, decay, ls, le, y, _ in regions:
            f.write(struct.pack('<BBBBBBfIII', kind, root, lo, hi, layer, rr, decay, ls, le, len(y)))
            f.write(pcm16(y))
    print(f'{len(regions)} regions, {Path(dst).stat().st_size / 1e6:.1f} MB, {rate} Hz, roots {roots}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
