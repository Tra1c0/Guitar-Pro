#!/usr/bin/env python3
"""Builds the bundled electric-guitar sample bank (RWSB v2) from Karoryfer Emilyguitar (CC0-1.0).

Source: https://github.com/sfzinstruments/karoryfer.emilyguitar (Epiphone, recorded direct, both pickups,
d'Addario flatwounds; notes every minor third Db2..D6, 4 velocity layers x 3 round robins).
Usage: python3 -I tools/make_guitar_bank.py <emilyguitar checkout> <output.rwsb> [--mode rms|peak]

What the script does (deterministic, standard library only; NumPy accelerates the analysis when installed):
- keeps velocity layers mp/mf/f and all 3 round robins, converts 24-bit to 16-bit,
- trims every note to KEEP seconds and prepares a loop over its last part, crossfaded so it is seamless,
- measures the natural decay (dB/s) so the engine can keep decaying while it loops,
- adds the release (string-damping) and muted (dead-note) samples,
- measures every region with tools/bank_analysis.py: the fine tune of the sustained body (cents re the root) and the
  level normalisation gain (--mode rms, the default: first 400 ms at -23 dBFS RMS with a -3 dBFS peak cap; --mode peak:
  notes -6 dBFS peak), and prints the per-region table.

RWSB v2, little endian: "RWSB" u16 version(2) u32 sampleRate u32 count, then per region:
u8 kind (0 note, 1 release, 2 dead), u8 rootMidi, u8 loKey, u8 hiKey, u8 layer, u8 rr, f32 decayDbPerSec,
u32 loopStart, u32 loopEnd (0 = no loop), u32 frames, f32 fineTuneCents, f32 gain, frames * i16.
An existing bank is re-measured and upgraded with tools/tune_guitar_bank.py.
"""
import math
import sys
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bank_analysis as ba  # noqa: E402

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


def main(src, dst, mode='rms'):
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
    bank = [ba.Region(kind, root, lo, hi, layer, rr, decay, ls, le, ba.pcm16(y)) for kind, root, lo, hi, layer, rr, decay, ls, le, y, _ in regions]
    rows, _ = ba.analyse(bank, rate, mode)
    print(ba.format_rows(bank, rows))
    ba.apply(bank, rows)
    ba.write_bank(dst, rate, bank, version=2)
    print(f'{len(bank)} regions, {Path(dst).stat().st_size / 1e6:.1f} MB, {rate} Hz, roots {roots}, RWSB v2 (mode {mode})')


if __name__ == '__main__':
    argv = sys.argv[1:]
    chosen = argv[argv.index('--mode') + 1] if '--mode' in argv else 'rms'
    positional = [a for i, a in enumerate(argv) if not a.startswith('--') and (i == 0 or argv[i - 1] != '--mode')]
    if chosen not in ('rms', 'peak') or len(positional) != 2:
        print(__doc__); sys.exit(2)
    main(positional[0], positional[1], chosen)
