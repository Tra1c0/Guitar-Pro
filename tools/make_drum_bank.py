#!/usr/bin/env python3
"""Builds the bundled drum kit (RWDK v1) "ArtiFret Pearl Kit" from the AVL Black Pearl 4 SoundFont.

Source: Black_Pearl_4_LV2.sf2 from https://github.com/x42/avldrums.lv2 (commit 9389f16f139410d9a55f9006976bd54cbd8b7806,
SHA-256 e0542ec19f2816ef4e0d00aeb087e7aa311e888d6f4805f971688bb267c6d405), AVL Drumkits by Glen MacArthur
(http://www.bandshed.net/avldrumkits/), CC BY-SA 3.0 with the exception stated in sf2/README (shipped unchanged as
app/src/main/assets/licenses/avl-drumkits-README.txt). The converted kit is a modified sample library and therefore
carries its own name and stays under CC BY-SA 3.0 (see docs/DEPENDENCIES.md).

Usage: python3 -I tools/make_drum_bank.py <Black_Pearl_4_LV2.sf2> <output.rwdk>

What the script does (deterministic, standard library only):
- reads the SoundFont 2 RIFF (sdta/smpl 16-bit PCM; pdta phdr/pbag/pgen/inst/ibag/igen/shdr) and resolves preset 0 ->
  instrument -> zones with keyRange, velRange, sampleID, exclusiveClass, pan, initialAttenuation, overridingRootKey,
  coarse/fine tune, scaleTuning and sampleModes (global zones and additive preset generators included); stereo pairs
  (sampleLink) would be summed to mono (this kit has none),
- maps General MIDI drum notes to the kit's keys (table GM below): the 4-piece kit has one rack tom and one floor tom,
  the other GM toms play them transposed; rimshots on snare/toms play the recorded edge hits; China plays the third crash,
- zones with the same velocity range on one key become round robins (this kit has none: 5 velocity layers per key),
- trims every sample: leading near-silence up to 1 frame before -46 dB re its peak (the stick contact lands on the
  scheduled frame), the tail after the last 10-ms window above -66 dBFS plus a fade (heads 20 ms, cymbals 120 ms),
  cymbals at most CYMBAL_MAX seconds (then a 400-ms fade); the sample rate (44.1 kHz) and 16 bit stay,
- measures each sample's level (RMS of its first 50 ms and peak, dBFS) for the engine's velocity curve.

RWDK v1, little endian: "RWDK" u16 version(1) u32 sampleRate u16 sampleCount u16 noteCount u16 infoLength + UTF-8 info,
then per sample: u32 frames, f32 levelDb, f32 peakDb, frames * i16;
then per note: u8 midi, u8 exclusiveClass, u8 flags (bit 0: cymbal/hi-hat, keeps ringing when struck again),
u8 articulationCount, i16 panPermille (-1000 left .. 1000 right), f32 gainDb; per articulation: u8 kind (0 normal,
1 rimshot), f32 pitchCents, u8 layerCount; per layer: u8 velLo, u8 velHi, u8 rrCount, rrCount * u16 sampleIndex.
"""
import array
import math
import struct
import sys

INFO = ("ArtiFret Pearl Kit 1 - abgeleitet von AVL Black Pearl 4 (AVL Drumkits, Glen MacArthur, http://www.bandshed.net/avldrumkits/), "
        "CC BY-SA 3.0; umgewandelt aus x42/avldrums.lv2 9389f16 mit tools/make_drum_bank.py")
CYMBAL_MAX = 6.0
LEAD_DB = -46.0
TAIL_DBFS = -66.0

# GM note -> (source key, transpose cents, rimshot source key or None, extra trim dB, name). Source keys are the AVL layout.
GM = [
    (35, 36, 0, None, 0.0, "Acoustic Bass Drum -> Kick"),
    (36, 36, 0, None, 0.0, "Kick"),
    (37, 37, 0, None, 0.0, "Side Stick"),
    (38, 38, 0, 40, 0.0, "Snare (rimshot: snare edge)"),
    (39, 39, 0, None, 0.0, "Hand Clap"),
    (40, 40, 0, None, 0.0, "Electric Snare -> snare edge"),
    (41, 41, 0, 43, 0.0, "Low Floor Tom"),
    (42, 42, 0, None, 0.0, "Closed Hi-Hat"),
    (43, 41, 300, 43, 0.0, "High Floor Tom -> floor tom +3 st"),
    (44, 44, 0, None, 0.0, "Pedal Hi-Hat"),
    (45, 45, 0, 47, 0.0, "Low Tom -> rack tom"),
    (46, 46, 0, None, 0.0, "Open Hi-Hat -> semi-open"),
    (47, 45, 200, 47, 0.0, "Low-Mid Tom -> rack tom +2 st"),
    (48, 45, 400, 47, 0.0, "Hi-Mid Tom -> rack tom +4 st"),
    (49, 49, 0, None, 0.0, "Crash 1"),
    (50, 45, 600, 47, 0.0, "High Tom -> rack tom +6 st"),
    (51, 51, 0, None, 0.0, "Ride (tip)"),
    (52, 60, 0, None, 0.0, "Chinese Cymbal -> crash 3 (no China in the kit)"),
    (53, 53, 0, None, 0.0, "Ride Bell"),
    (54, 54, 0, None, 0.0, "Tambourine"),
    (55, 55, 0, None, 0.0, "Splash"),
    (56, 56, 0, None, 0.0, "Cowbell"),
    (57, 57, 0, None, 0.0, "Crash 2"),
    (59, 59, 0, None, 0.0, "Ride 2 (shank)"),
    (70, 61, 0, None, 0.0, "Maracas"),
]
CYMBALS = {42, 44, 46, 49, 51, 52, 53, 55, 57, 59}

GEN_KEY, GEN_VEL, GEN_INST, GEN_SAMPLE = 43, 44, 41, 53
GEN_PAN, GEN_ATT, GEN_COARSE, GEN_FINE, GEN_MODES, GEN_SCALE, GEN_EXCL, GEN_ROOT = 17, 48, 51, 52, 54, 56, 57, 58


def chunks(b, off, end):
    while off + 8 <= end:
        cid = b[off:off + 4]
        size = struct.unpack_from('<I', b, off + 4)[0]
        yield cid, off + 8, size
        off += 8 + size + (size & 1)


def signed(v):
    return v - 65536 if v >= 32768 else v


class SoundFont:
    def __init__(self, data):
        assert data[:4] == b'RIFF' and data[8:12] == b'sfbk', "keine SoundFont-2-Datei"
        self.lists = {}
        for cid, o, s in chunks(data, 12, len(data)):
            if cid == b'LIST':
                for c2, o2, s2 in chunks(data, o + 4, o + s):
                    self.lists[c2.decode('ascii')] = (o2, s2)
        o, s = self.lists['smpl']
        self.pcm = array.array('h')
        self.pcm.frombytes(data[o:o + s])
        if sys.byteorder != 'little':
            self.pcm.byteswap()

        def recs(name, size, fmt):
            o, s = self.lists[name]
            return [struct.unpack_from(fmt, data, o + i * size) for i in range(s // size)]
        self.phdr = recs('phdr', 38, '<20sHHHIII')
        self.pbag = recs('pbag', 4, '<HH')
        self.pgen = recs('pgen', 4, '<HH')
        self.inst = recs('inst', 22, '<20sH')
        self.ibag = recs('ibag', 4, '<HH')
        self.igen = recs('igen', 4, '<HH')
        self.shdr = recs('shdr', 46, '<20sIIIIIBbHH')

    @staticmethod
    def zones(bags, gens, first, last):
        out = []
        for b in range(first, last):
            out.append([(g, v) for g, v in gens[bags[b][0]:bags[b + 1][0]]])
        return out

    def regions(self, preset_index=0):
        """Resolved playable regions of one preset: dicts with key/vel ranges, sample and generator values."""
        p = self.phdr[preset_index]
        pzones = self.zones(self.pbag, self.pgen, p[3], self.phdr[preset_index + 1][3])
        pglobal = {}
        if pzones and not any(g == GEN_INST for g, _ in pzones[0]):
            pglobal = dict(pzones[0])
            pzones = pzones[1:]
        out = []
        for pz in pzones:
            pg = dict(pglobal)
            pg.update(dict(pz))
            if GEN_INST not in pg:
                continue
            ii = pg[GEN_INST]
            izones = self.zones(self.ibag, self.igen, self.inst[ii][1], self.inst[ii + 1][1])
            iglobal = {}
            if izones and not any(g == GEN_SAMPLE for g, _ in izones[0]):
                iglobal = dict(izones[0])
                izones = izones[1:]
            for iz in izones:
                ig = dict(iglobal)
                ig.update(dict(iz))
                if GEN_SAMPLE not in ig:
                    continue
                def rng(gen, d):
                    v = d.get(gen, 0x7F00)
                    return v & 0xFF, (v >> 8) & 0xFF
                kl, kh = rng(GEN_KEY, ig); pkl, pkh = rng(GEN_KEY, pg)
                vl, vh = rng(GEN_VEL, ig); pvl, pvh = rng(GEN_VEL, pg)
                lo, hi = max(kl, pkl), min(kh, pkh)
                vlo, vhi = max(vl, pvl), min(vh, pvh)
                if lo > hi or vlo > vhi:
                    continue
                sid = ig[GEN_SAMPLE]
                sh = self.shdr[sid]

                def add(gen, default=0):  # instrument value plus the preset's additive offset
                    return signed(ig.get(gen, default)) + signed(pg.get(gen, 0))
                root = ig.get(GEN_ROOT, 0xFFFF)
                root = sh[6] if root in (0xFFFF, 65535) or signed(root) < 0 else root
                out.append(dict(
                    keys=(lo, hi), vel=(vlo, vhi), sample=sid, name=sh[0].rstrip(b'\0').decode('latin-1'),
                    pan=max(-500, min(500, add(GEN_PAN))), att=max(0, add(GEN_ATT)), excl=ig.get(GEN_EXCL, 0),
                    root=root, scale=signed(ig.get(GEN_SCALE, 100)), tune=add(GEN_COARSE) * 100 + add(GEN_FINE) + sh[7],
                    modes=ig.get(GEN_MODES, 0) & 3, rate=sh[5], kind=sh[9], link=sh[8]))
        return out

    def sample(self, sid):
        """Mono PCM of a sample; a linked stereo pair is summed to mono."""
        sh = self.shdr[sid]
        x = list(self.pcm[sh[1]:sh[2]])
        if sh[9] in (2, 4) and sh[8] < len(self.shdr) - 1:
            o = self.shdr[sh[8]]
            y = self.pcm[o[1]:o[2]]
            x = [int(round((x[i] + (y[i] if i < len(y) else 0)) / 2)) for i in range(len(x))]
        return x


def db(v):
    return 20 * math.log10(max(v, 1e-9) / 32768.0)


def prepare(x, rate, cymbal):
    """Trim, fade and measure one sample; returns (pcm, levelDb, peakDb, info)."""
    peak = max(1, max(abs(v) for v in x))
    lead_th = peak * 10 ** (LEAD_DB / 20)
    start = next(i for i, v in enumerate(x) if abs(v) >= lead_th)
    start = max(0, start - 1)
    w = rate // 100
    last = start + w
    for i in range(start, len(x) - w + 1, w):
        seg = x[i:i + w]
        if db(math.sqrt(sum(v * v for v in seg) / w)) > TAIL_DBFS:
            last = i + w
    fade = int((0.12 if cymbal else 0.02) * rate)
    end = min(len(x), last + fade)
    cap = int(CYMBAL_MAX * rate) + start
    if cymbal and end > cap:
        end = cap
        fade = int(0.4 * rate)
    y = x[start:end]
    n = len(y)
    fade = min(fade, n)
    for k in range(fade):  # cosine fade to exactly 0 at the end
        g = 0.5 * (1 + math.cos(math.pi * (k + 1) / fade))
        y[n - fade + k] = int(round(y[n - fade + k] * g))
    for k in range(min(4, n)):  # 4-frame fade-in against a step from silence
        y[k] = int(round(y[k] * (k + 1) / 5))
    body = y[:min(n, int(0.05 * rate))]
    level = db(math.sqrt(sum(v * v for v in body) / max(1, len(body))))
    pk = db(max(abs(v) for v in y))
    return y, level, pk, (start, len(x), n)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sf = SoundFont(open(sys.argv[1], 'rb').read())
    regions = sf.regions(0)
    rates = {r['rate'] for r in regions}
    assert len(rates) == 1, f"gemischte Sampleraten {rates}"
    rate = rates.pop()
    by_key = {}
    for r in regions:
        for k in range(r['keys'][0], r['keys'][1] + 1):
            by_key.setdefault(k, []).append(r)

    samples = []          # (pcm, level, peak)
    sample_index = {}     # (sid, cymbal) -> index
    rows = []

    def layers_of(key, cymbal):
        zs = sorted(by_key[key], key=lambda r: (r['vel'], r['sample']))
        for r in zs:
            assert r['root'] == key and r['scale'] == 100 and r['tune'] == 0 and r['modes'] == 0, f"Zone {r['name']}: unerwartete Stimmung/Loop"
        groups = []
        for r in zs:
            if groups and groups[-1][0] == r['vel']:
                groups[-1][1].append(r)
            else:
                groups.append((r['vel'], [r]))
        out = []
        for vel, rs in groups:
            idx = []
            for r in rs:
                k = (r['sample'], cymbal)
                if k not in sample_index:
                    pcm, level, pk, info = prepare(sf.sample(r['sample']), rate, cymbal)
                    sample_index[k] = len(samples)
                    samples.append((pcm, level, pk))
                    rows.append(f"  {r['name']:18s} {info[1] / rate:5.2f} s -> {info[2] / rate:5.2f} s (Start +{info[0]}), RMS 0-50 ms {level:6.1f} dBFS, Spitze {pk:6.1f} dBFS")
                idx.append(sample_index[k])
            out.append((vel, idx))
        return out, zs

    notes = []
    for midi, src, cents, rim, trim, name in GM:
        cymbal = midi in CYMBALS
        layers, zs = layers_of(src, cymbal)
        excl = zs[0]['excl']
        pan = zs[0]['pan'] * 2  # SF2 0.1 % units (-500..500) -> permille of the full left..right range
        att = zs[0]['att']
        assert all(z['excl'] == excl and z['pan'] * 2 == pan and z['att'] == att for z in zs), f"{name}: Zonen uneinheitlich"
        arts = [(0, cents, layers)]
        if rim is not None:
            rl, _ = layers_of(rim, cymbal)
            arts.append((1, cents, rl))
        notes.append((midi, excl, 1 if cymbal else 0, pan, -att / 10.0 + trim, arts, name))

    out = bytearray()
    info = INFO.encode('utf-8')
    out += b'RWDK' + struct.pack('<HIHHH', 1, rate, len(samples), len(notes), len(info)) + info
    for pcm, level, pk in samples:
        out += struct.pack('<Iff', len(pcm), level, pk)
        a = array.array('h', pcm)
        if sys.byteorder != 'little':
            a.byteswap()
        out += a.tobytes()
    for midi, excl, flags, pan, gain, arts, name in notes:
        out += struct.pack('<BBBBhf', midi, excl, flags, len(arts), pan, gain)
        for kind, cents, layers in arts:
            out += struct.pack('<BfB', kind, float(cents), len(layers))
            for (lo, hi), idx in layers:
                out += struct.pack('<BBB', lo, hi, len(idx)) + b''.join(struct.pack('<H', i) for i in idx)
    open(sys.argv[2], 'wb').write(out)
    frames = sum(len(s[0]) for s in samples)
    print(f"RWDK v1: {len(samples)} Samples, {len(notes)} GM-Noten, {rate} Hz mono 16 Bit, {frames / rate:.1f} s PCM, Datei {len(out)} Bytes ({len(out) / 1e6:.2f} MB)")
    for midi, excl, flags, pan, gain, arts, name in notes:
        print(f"  GM {midi:2d} {name:48s} Klasse {excl} Pan {pan / 10:+.0f} % Pegel {gain:+.1f} dB Artikulationen {len(arts)} Layer {len(arts[0][2])}")
    print("\n".join(rows))


if __name__ == '__main__':
    main()
