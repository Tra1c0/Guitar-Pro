#!/usr/bin/env python3
"""Measures an existing RWSB sample bank and writes it as RWSB v2 with per-region fine tune and gain.

Usage: python3 -I tools/tune_guitar_bank.py <input.rwsb> <output.rwsb> [--mode rms|peak] [--quiet]
       python3 -I tools/tune_guitar_bank.py <input.rwsb> --report-only [--mode rms|peak]

Reads a v1 or v2 bank (the PCM is kept bit-exactly), measures every region with tools/bank_analysis.py and stores:
- fineTuneCents: pitch of the sustained body (0.25-0.9 s) relative to the region's root, by multi-period
  autocorrelation; the engine plays the region that much lower, so the note lands on its nominal pitch.
  The early part, the loop section and an independent windowed-DFT (Goertzel) sweep are measured for the report.
  Release and dead regions get 0.
- gain: level normalisation. --mode rms (default, what SampleBank.kt computes for v1 banks at load time): the first 400 ms
  of a note at -23 dBFS RMS, capped so no region peaks above -3 dBFS; dead notes -9 dBFS peak, release noises -18 dBFS
  peak. --mode peak: notes -6 dBFS peak, dead -9, releases -18.
The per-region table and a bank summary (level spread across roots per layer, layer steps, peaks) are printed.
Standard library only; NumPy accelerates the analysis when installed (BANK_ANALYSIS_NO_NUMPY=1 disables it).
Deterministic: the same input always produces the same output file.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bank_analysis as ba  # noqa: E402


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    mode = 'rms'
    for i, a in enumerate(argv):
        if a == '--mode':
            mode = argv[i + 1]; args.remove(argv[i + 1])
    report_only = '--report-only' in argv
    quiet = '--quiet' in argv
    if mode not in ('rms', 'peak') or len(args) != (1 if report_only else 2):
        print(__doc__); return 2
    version, rate, regions = ba.read_bank(args[0])
    rows, measurements = ba.analyse(regions, rate, mode)
    if not quiet:
        print(f'{args[0]}: RWSB v{version}, {rate} Hz, {len(regions)} regions, mode={mode}, numpy={"yes" if ba._np is not None else "no"}')
        print(ba.format_rows(regions, rows))
    notes = [(r, row) for r, row in zip(regions, rows) if r.kind == ba.KIND_NOTE]
    roots = sorted({r.root for r, _ in notes})
    print('fine tune (body, cents) mean per root: ' + ' '.join(f'{root}={sum(row["fine_tune"] for r, row in notes if r.root == root) / 9:+.1f}' for root in roots))
    print(f'regions whose body pitch is > 5 cents off the root: {sum(1 for _, row in notes if abs(row["fine_tune"]) > 5)} of {len(notes)}; '
          f'sweep/autocorrelation disagree by > 3 cents: {sum(1 for _, row in notes if abs(row["sweep_c"] - row["body_c"]) > 3)}')
    for name, gains in (('unity (as recorded)', [1.0] * len(regions)), (f'chosen mode "{mode}"', [row['gain'] for row in rows])):
        s = ba.summary(regions, measurements, gains)
        print(f'levels {name}: ' + ', '.join(f'{k}={v:.1f}' for k, v in s.items()))
    if report_only:
        return 0
    ba.apply(regions, rows)
    ba.write_bank(args[1], rate, regions, version=2)
    print(f'wrote {args[1]}: RWSB v2, {Path(args[1]).stat().st_size / 1e6:.1f} MB')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
