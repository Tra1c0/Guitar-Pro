#!/usr/bin/env python3
"""Measurement and normalisation of RiffWorkbench sample banks (RWSB), shared by make_guitar_bank.py and tune_guitar_bank.py.

Standard library only. NumPy is used as an optional accelerator when it is installed (the same quantities are computed,
the results agree to far below the stored resolution); set BANK_ANALYSIS_NO_NUMPY=1 to force the pure-Python path.
Every function is deterministic: the same bank always yields the same table and the same output file.

RWSB format, little endian. Header: "RWSB", u16 version, u32 sampleRate, u32 regionCount. Per region:
  v1: u8 kind (0 note, 1 release, 2 dead), u8 rootMidi, u8 loKey, u8 hiKey, u8 layer, u8 rr, f32 decayDbPerSec,
      u32 loopStart, u32 loopEnd (0 = no loop), u32 frames, then frames * i16 PCM.
  v2: the v1 fields in the same order, then f32 fineTuneCents, f32 gain, then frames * i16 PCM.
      fineTuneCents: measured pitch of the sustained body (0.25-0.9 s) relative to rootMidi; the engine plays the region
        at ratio 2^((pitch - root - fineTuneCents/100)/12), so a region recorded 7 cents sharp is pulled down by 7 cents.
        0 for release and dead regions.
      gain: linear factor applied to the PCM when the region is played (level normalisation, see gains_peak/gains_rms).

What is measured per note region (see analyse()):
  - pitch of the early part (0.05-0.25 s), the sustained body (0.25-0.9 s) and the loop section, each by multi-period
    autocorrelation with parabolic interpolation (<= 8 periods, +-6 % search window, same estimator as the acceptance test),
  - the body pitch again by a windowed-DFT (Goertzel) sweep in 1-cent steps within +-40 cents of the root with parabolic
    refinement, as an independent cross-check (reported, with the disagreement),
  - peak, RMS of the first 400 ms (the normalisation window: attack and early sustain of a typical note), RMS of the first
    100 ms, RMS 0.02-0.4 s and RMS of the loop section.
The stored fine tune is the body autocorrelation value (what sustains); the loop pitch is reported only.
"""
import array
import cmath
import itertools
import math
import operator
import os
import struct

try:  # optional accelerator
    import numpy as _np
except Exception:  # pragma: no cover - numpy missing is the documented fallback
    _np = None
if os.environ.get('BANK_ANALYSIS_NO_NUMPY'):
    _np = None

KIND_NOTE, KIND_RELEASE, KIND_DEAD = 0, 1, 2
KIND_NAMES = {KIND_NOTE: 'note', KIND_RELEASE: 'release', KIND_DEAD: 'dead'}
HEADER = struct.Struct('<4sHII')
META_V1 = struct.Struct('<BBBBBBfIII')
META_V2 = struct.Struct('<BBBBBBfIIIff')
MAX_BYTES = 256 * 1024 * 1024

# Level targets (dBFS). Peak normalisation: notes -6, dead notes -9, release noises -18 (their natural level in the
# source, so note-to-release balance is unchanged for the mf layer). RMS normalisation (the shipped variant, mirrored by
# SampleBank.Region.normalisation): RMS of the first 400 ms of a note at NOTE_RMS_DBFS, capped so that no region peaks
# above RMS_PEAK_CAP_DBFS after gain. Measured in QA cycle 18 against the 100 ms, 0.02-0.4 s and peak alternatives: the
# 400 ms window keeps both the attack (2.4 dB) and the sustained level (1.4 dB) of the mf layer within 3 dB across the
# roots and the loudness steps at the velocity-layer switches within 1.1 dB.
NOTE_PEAK_DBFS = -6.0
DEAD_PEAK_DBFS = -9.0
RELEASE_PEAK_DBFS = -18.0
NOTE_RMS_DBFS = -23.0
RMS_PEAK_CAP_DBFS = -3.0

# Analysis windows (seconds).
EARLY = (0.05, 0.25)
BODY = (0.25, 0.9)
LEVEL_WINDOW = (0.02, 0.4)
RMS100 = (0.0, 0.1)
RMS_WINDOW = (0.0, 0.4)
SWEEP_CENTS = 40
SWEEP_STEP = 1


class Region:
    """One RWSB region; pcm is an array('h') of 16-bit samples."""
    __slots__ = ('kind', 'root', 'lo', 'hi', 'layer', 'rr', 'decay', 'loop_start', 'loop_end', 'pcm', 'fine_tune', 'gain')

    def __init__(self, kind, root, lo, hi, layer, rr, decay, loop_start, loop_end, pcm, fine_tune=0.0, gain=1.0):
        self.kind, self.root, self.lo, self.hi, self.layer, self.rr = kind, root, lo, hi, layer, rr
        self.decay, self.loop_start, self.loop_end, self.pcm = decay, loop_start, loop_end, pcm
        self.fine_tune, self.gain = fine_tune, gain

    @property
    def frames(self):
        return len(self.pcm)

    def label(self):
        return f'{KIND_NAMES[self.kind]:7s} root={self.root:3d} L{self.layer} RR{self.rr}'


# ------------------------------------------------------------------------------------------------------------ file I/O

def read_bank(path):
    """Returns (version, sampleRate, [Region]) of a v1 or v2 bank, validating the same limits as SampleBank.kt."""
    with open(path, 'rb') as f:
        magic, version, rate, count = HEADER.unpack(f.read(HEADER.size))
        if magic != b'RWSB':
            raise ValueError('not a RiffWorkbench sample bank')
        if version not in (1, 2):
            raise ValueError(f'unknown RWSB version {version}')
        if not (8000 <= rate <= 192000 and 1 <= count <= 10000):
            raise ValueError('invalid RWSB header')
        meta = META_V1 if version == 1 else META_V2
        regions, total = [], 0
        for _ in range(count):
            fields = meta.unpack(f.read(meta.size))
            kind, root, lo, hi, layer, rr, decay, ls, le, frames = fields[:10]
            fine_tune, gain = (fields[10], fields[11]) if version == 2 else (0.0, 1.0)
            total += frames * 2
            if not (1 <= frames <= 60 * rate) or total > MAX_BYTES:
                raise ValueError('RWSB too large')
            if not (le == 0 or (0 <= ls and le - ls >= 4 and le <= frames)):
                raise ValueError('invalid loop points')
            pcm = array.array('h')
            pcm.frombytes(f.read(frames * 2))
            if len(pcm) != frames:
                raise ValueError('RWSB truncated')
            regions.append(Region(kind, root, lo, hi, layer, rr, decay, ls, le, pcm, fine_tune, gain))
    return version, rate, regions


def write_bank(path, rate, regions, version=2):
    """Writes the regions as RWSB v2 (default) or v1 (fine tune and gain dropped)."""
    with open(path, 'wb') as f:
        f.write(HEADER.pack(b'RWSB', version, rate, len(regions)))
        for r in regions:
            if version == 2:
                f.write(META_V2.pack(r.kind, r.root, r.lo, r.hi, r.layer, r.rr, r.decay, r.loop_start, r.loop_end, r.frames, r.fine_tune, r.gain))
            else:
                f.write(META_V1.pack(r.kind, r.root, r.lo, r.hi, r.layer, r.rr, r.decay, r.loop_start, r.loop_end, r.frames))
            f.write(r.pcm.tobytes())


def pcm16(samples):
    """Floats in [-1, 1] -> array('h'), rounded and clamped like make_guitar_bank.py always did."""
    return array.array('h', (max(-32768, min(32767, int(round(v * 32767)))) for v in samples))


# -------------------------------------------------------------------------------------------------------------- maths

def midi_hz(midi):
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


def cents(hz, ref_hz):
    return 1200.0 * math.log(hz / ref_hz, 2.0)


def db(x):
    return -240.0 if x <= 1e-12 else 20.0 * math.log10(x)


def to_float(pcm):
    """int16 samples -> floats in [-1, 1) (numpy array when available)."""
    if _np is not None:
        return _np.frombuffer(pcm.tobytes(), dtype='<i2').astype('float64') / 32768.0
    return [s / 32768.0 for s in pcm]


def _window(x, rate, span):
    a = max(0, min(len(x), int(span[0] * rate)))
    b = max(a, min(len(x), int(span[1] * rate)))
    return a, b


def rms(x, rate, span):
    a, b = _window(x, rate, span)
    if b <= a:
        return 0.0
    if _np is not None:
        seg = x[a:b]
        return math.sqrt(float(_np.dot(seg, seg)) / (b - a))
    return math.sqrt(sum(map(operator.mul, x[a:b], x[a:b])) / (b - a))


def peak_int(pcm):
    """Largest |sample| of the 16-bit data (at least 1)."""
    if _np is not None:
        return max(1, int(_np.abs(_np.frombuffer(pcm.tobytes(), dtype='<i2').astype('int32')).max()))
    return max(1, max(abs(s) for s in pcm))


def autocorr_pitch(x, rate, span, guess_hz):
    """
    Multi-period autocorrelation pitch (Hz) in [span) around guess_hz (+-6 %): the lag is searched at K periods
    (K <= 8, K * period <= 1000 samples) with parabolic interpolation; the same estimator as the engine's acceptance test.
    """
    a, b = _window(x, rate, span)
    period = rate / guess_hz
    k = min(8, max(1, int(1000.0 / period)))
    lag0 = k * period
    lo = max(2, int(lag0 * 0.94))
    hi = min(int(lag0 * 1.06), (b - a) // 2)
    if hi <= lo:
        return guess_hz
    r = {}
    best, best_r = lo, -1.0
    for lag in range(lo, hi + 1):
        if _np is not None:
            p = x[a:b - lag]; q = x[a + lag:b]
            num = float(_np.dot(p, q)); d1 = float(_np.dot(p, p)); d2 = float(_np.dot(q, q))
        else:
            p = x[a:b - lag]; q = x[a + lag:b]
            num = sum(map(operator.mul, p, q)); d1 = sum(map(operator.mul, p, p)); d2 = sum(map(operator.mul, q, q))
        v = num / math.sqrt(d1 * d2) if d1 > 0 and d2 > 0 else -1.0
        r[lag] = v
        if v > best_r:
            best_r, best = v, lag
    lag = float(best)
    if lo < best < hi:
        y0, y1, y2 = r[best - 1], r[best], r[best + 1]
        den = y0 - 2 * y1 + y2
        if abs(den) > 1e-12:
            lag += max(-1.0, min(1.0, 0.5 * (y0 - y2) / den))
    return rate * k / lag


def dft_sweep_pitch(x, rate, span, guess_hz, span_cents=SWEEP_CENTS, step=SWEEP_STEP):
    """
    Hann-windowed DFT (Goertzel) magnitude sweep in `step`-cent steps within +-span_cents of guess_hz; the strongest bin,
    refined by parabolic interpolation of the magnitude, is returned in Hz.
    """
    a, b = _window(x, rate, span)
    n = b - a
    if n < 64:
        return guess_hz
    offsets = list(range(-span_cents, span_cents + 1, step))
    freqs = [guess_hz * 2.0 ** (c / 1200.0) for c in offsets]
    if _np is not None:
        t = _np.arange(n)
        w = 0.5 - 0.5 * _np.cos(2 * _np.pi * t / n)
        xw = x[a:b] * w
        omega = _np.array([2 * math.pi * f / rate for f in freqs])
        spectrum = _np.exp(-1j * _np.outer(omega, t)) @ xw
        mags = [float(v) for v in _np.abs(spectrum)]
    else:
        xw = [x[a + i] * (0.5 - 0.5 * math.cos(2 * math.pi * i / n)) for i in range(n)]
        mags = []
        for f in freqs:
            z = cmath.exp(-2j * math.pi * f / rate)
            # z^0, z^1, ... z^(n-1) by a running product (C speed), then the dot product with the windowed signal.
            powers = itertools.accumulate(itertools.repeat(z, n - 1), operator.mul, initial=1.0)
            mags.append(abs(sum(map(operator.mul, xw, powers))))
    best = max(range(len(mags)), key=lambda i: mags[i])
    c = float(offsets[best])
    if 0 < best < len(mags) - 1:
        y0, y1, y2 = mags[best - 1], mags[best], mags[best + 1]
        den = y0 - 2 * y1 + y2
        if abs(den) > 1e-18:
            c += step * max(-1.0, min(1.0, 0.5 * (y0 - y2) / den))
    return guess_hz * 2.0 ** (c / 1200.0)


# ---------------------------------------------------------------------------------------------------------- measuring

def measure(region, rate):
    """Pitch (cents re root; notes only) and level (dBFS) measurements of one region."""
    x = to_float(region.pcm)
    pk = peak_int(region.pcm) / 32768.0
    m = {'peak_db': db(pk), 'rms400_db': db(rms(x, rate, RMS_WINDOW)), 'rms100_db': db(rms(x, rate, RMS100)), 'level_db': db(rms(x, rate, LEVEL_WINDOW))}
    if region.kind == KIND_NOTE:
        ref = midi_hz(region.root)
        loop = (region.loop_start / rate, region.loop_end / rate) if region.loop_end > 0 else BODY
        m['early_c'] = cents(autocorr_pitch(x, rate, EARLY, ref), ref)
        m['body_c'] = cents(autocorr_pitch(x, rate, BODY, ref), ref)
        m['loop_c'] = cents(autocorr_pitch(x, rate, loop, ref), ref)
        m['sweep_c'] = cents(dft_sweep_pitch(x, rate, BODY, ref), ref)
        m['loop_level_db'] = db(rms(x, rate, loop))
    return m


def fine_tune_of(measurement):
    """Stored fine tune: the body autocorrelation value, rounded to 0.1 cent (0 for non-note regions)."""
    return round(measurement['body_c'], 1) if 'body_c' in measurement else 0.0


def gains_peak(regions, measurements):
    """Variant (i): peak normalisation. Notes -> NOTE_PEAK_DBFS, dead notes -> DEAD_PEAK_DBFS, releases -> RELEASE_PEAK_DBFS."""
    out = []
    for r, m in zip(regions, measurements):
        target = {KIND_NOTE: NOTE_PEAK_DBFS, KIND_DEAD: DEAD_PEAK_DBFS, KIND_RELEASE: RELEASE_PEAK_DBFS}[r.kind]
        out.append(10.0 ** ((target - m['peak_db']) / 20.0))
    return out


def gains_rms(regions, measurements):
    """Variant (ii), shipped: notes by RMS of the first 400 ms -> NOTE_RMS_DBFS with a RMS_PEAK_CAP_DBFS peak cap; dead/release as in (i)."""
    out = []
    for r, m, g_peak in zip(regions, measurements, gains_peak(regions, measurements)):
        if r.kind != KIND_NOTE:
            out.append(g_peak)
            continue
        g = 10.0 ** ((NOTE_RMS_DBFS - m['rms400_db']) / 20.0)
        cap = 10.0 ** ((RMS_PEAK_CAP_DBFS - m['peak_db']) / 20.0)
        out.append(min(g, cap))
    return out


def round_gain(g):
    """Stored gain resolution: 4 significant digits (0.001 dB), so both analysis paths yield the same file."""
    return float(f'{g:.4g}')


def analyse(regions, rate, mode='peak'):
    """
    Measures every region and returns (rows, gains) where rows[i] is the measurement dict of regions[i] extended with
    'fine_tune' and 'gain' (dB in 'gain_db') for the chosen normalisation mode ('peak' or 'rms').
    """
    measurements = [measure(r, rate) for r in regions]
    gains = (gains_peak if mode == 'peak' else gains_rms)(regions, measurements)
    rows = []
    for m, g in zip(measurements, gains):
        row = dict(m)
        row['fine_tune'] = fine_tune_of(m)
        row['gain'] = round_gain(g)
        row['gain_db'] = db(row['gain'])
        rows.append(row)
    return rows, measurements


def apply(regions, rows):
    """Stores fine tune and gain of each row on its region (in place)."""
    for r, row in zip(regions, rows):
        r.fine_tune = row['fine_tune']
        r.gain = row['gain']


# ------------------------------------------------------------------------------------------------------------ summary

def summary(regions, measurements, gains):
    """Bank-level metrics of a gain set: spread across roots per layer (RMS 0.02-0.4 s and first-100-ms RMS), layer steps, peaks."""
    notes = [(r, m, g) for r, m, g in zip(regions, measurements, gains) if r.kind == KIND_NOTE]
    by_root_layer = {}
    for r, m, g in notes:
        by_root_layer.setdefault((r.root, r.layer), []).append((m, g))
    layers = sorted({r.layer for r, _, _ in notes})
    out = {'max_peak_db': max(m['peak_db'] + db(g) for _, m, g in notes)}
    for metric in ('level_db', 'rms100_db', 'rms400_db'):
        for layer in layers:
            per_root = {root: sum(m[metric] + db(g) for m, g in v) / len(v) for (root, l), v in by_root_layer.items() if l == layer}
            out[f'{metric}_spread_L{layer}'] = max(per_root.values()) - min(per_root.values())
    for metric in ('peak_db', 'rms100_db', 'rms400_db'):
        steps = []
        for root in sorted({r.root for r, _, _ in notes}):
            levels = [sum(m[metric] + db(g) for m, g in by_root_layer[(root, l)]) / len(by_root_layer[(root, l)]) for l in layers]
            steps += [abs(levels[i + 1] - levels[i]) for i in range(len(levels) - 1)]
        out[f'{metric}_layer_step_max'] = max(steps) if steps else 0.0
        out[f'{metric}_layer_step_mean'] = sum(steps) / len(steps) if steps else 0.0
    return out


def format_rows(regions, rows):
    """Per-region table (one line per region)."""
    lines = [f"{'kind':7s} {'root':>4s} {'L':>2s} {'RR':>3s} {'early':>7s} {'body':>7s} {'loop':>7s} {'sweep':>7s} {'diff':>6s} {'peak':>7s} {'rms400':>7s} {'rms100':>7s} {'lvl':>7s} {'gain':>7s} {'fine':>6s}"]
    for r, row in zip(regions, rows):
        if r.kind == KIND_NOTE:
            tune = f"{row['early_c']:+7.1f} {row['body_c']:+7.1f} {row['loop_c']:+7.1f} {row['sweep_c']:+7.1f} {row['sweep_c'] - row['body_c']:+6.1f}"
        else:
            tune = f"{'-':>7s} {'-':>7s} {'-':>7s} {'-':>7s} {'-':>6s}"
        lines.append(f"{KIND_NAMES[r.kind]:7s} {r.root:4d} {r.layer:2d} {r.rr:3d} {tune} {row['peak_db']:7.1f} {row['rms400_db']:7.1f} {row['rms100_db']:7.1f} {row['level_db']:7.1f} {row['gain_db']:+7.2f} {row['fine_tune']:+6.1f}")
    return '\n'.join(lines)
