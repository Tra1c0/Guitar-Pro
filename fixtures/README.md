# Eigene Verifikationsfixtures

Erzeugen: `./gradlew :data:local:generateFixtures`. Der separate Source-Set `fixtureTools` wird nicht in die App gepackt. Ausgabe ist deterministisch; `SHA256SUMS` dokumentiert die erzeugten Bytes.

- `drop-a-metal.riffproj` / `.json`: siebensaitige Drop-A-Gitarre (33/40/45/50/55/59/64), 150 BPM, 4/4 und 7/8, PM/Dead/Slide/Pinch/Bend sowie gehaltene andere Saite und Kick/Snare/HH. Eine serialisierte Technik belegt noch keine vollständige Gravur oder hörbare Umsetzung.
- `four-voices-nested-tuplets.riffproj` / `.json`: vier getrennte Stimmen; verschachteltes 3:2 × 5:4-Tuplet mit exakter Dauer 1/15 einer ganzen Note.
- `eight-tracks-200-bars.riffproj`: acht Spuren, 200 Takte, 12.800 Noten, stabile IDs und vollständige Stimmenstruktur. Struktur-/Viewport-Fixture, kein Nachweis von 60 fps.
- `shared-clock.riffproj` / `.json`: vier 4/4-Takte bei 120 BPM, eigene Referenz-WAV und Sync-Map.
- `clock-120bpm-48k.wav`: 8 s, 48 kHz, mono, PCM 16 Bit. Ein selbst erzeugter 128-Frame-Tonimpuls beginnt bei Frames 0, 24.000, …, 360.000. Anker bei 0 und 7,5 s. Loop-Ende bei Frame 384.000 ist exklusiv. Synth/Stem-Gleichlauf ist erst nach Spike B testbar.

Musik und Testsignal wurden für dieses Projekt erstellt. Das synthetische WAV ist CC0-1.0; es enthält weder Instrumentaufnahmen noch fremde Soundbanks. Die übrigen Projektquellen haben noch keine Veröffentlichungslizenz.

Es liegen noch keine in Guitar Pro Desktop verifizierten GP3/4/5/GPX/GP7/GP8-Dateien vor. Der Format-Spike muss solche selbst erstellten Dateien mit überprüfter Herkunft ergänzen. Dateinamen für den späteren Desktop-Gegencheck stehen in `docs/SPIKES.md`; keine native Fixture wird als Guitar-Pro-Datei ausgegeben.

## Synthetische GP3/GP4/GP5-Dateien (`gp/`)

Erzeugen: `python3 -I tools/make_gp_fixtures.py` mit installiertem PyGuitarPro 0.11 (LGPL-3.0, nur Generator, weder gebündelt noch zur Laufzeit benötigt). Ausgabe ist deterministisch; das Skript aktualisiert ausschließlich die `gp/`-Zeilen in `SHA256SUMS`. Achtung: `:data:local:generateFixtures` schreibt `SHA256SUMS` derzeit ohne Unterordner neu und entfernt diese Zeilen.

- `gp/drop-a-band.gp3` (v3.00), `gp/drop-a-band.gp4` (v4.06), `gp/drop-a-band.gp5` (v5.10), `gp/drop-a-band-v500.gp5` (v5.00): dieselbe eigene Musik je Formatversion, soweit das Format sie kennt. Siebensaitige Drop-A-Gitarre (33/40/45/50/55/59/64), viersaitiger Bass, Drums (Kick 36, Snare 38, Closed HH 42, Crash 49), Akustikgitarre mit Kapo 2; 4/4 und 7/8, Wiederholung mit zwei Schlüssen, Marker, Tempowechsel, Triolen, Quintolen, punktierte Noten, zwei Stimmen (GP5), Sprungmarken (GP5), Liedtext (GP4/GP5), Akkorddiagramme. Effekte: Palm Mute, Dead Note, Slides, natürliche/künstliche/Pinch-/Tapped-/Semi-Flageoletts, Bend mit Release, Tremolo-Bar, Hammer-on/Pull-off, Haltebogen über den Taktstrich, Let Ring, Vibrato, Vorschlagsnoten, Tremolo-Picking, Triller, Anschlag/Rasgueado, Akzente, Ghost Notes. Absichtlich enthalten: ein übervoller Takt (GP4) und ein unvollständiger Takt (GP3).
- `gp/*.json`: Orakel, also das, was PyGuitarPro aus denselben Bytes zurückliest. Die Tests in `adapter/formats` vergleichen den Kotlin-Import damit und rechnen die Einheiten dabei unabhängig um.

Herkunft: Inhalt eigens für dieses Projekt erstellt, CC0-1.0; keine Fremd-Tabs. PyGuitarPro 0.11 liest und schreibt Vorschlagsnoten in GP3/GP4 in der Reihenfolge Bund, Dynamik, Dauer, Übergang. Das Skript korrigiert das lokal auf die dokumentierte Reihenfolge Bund, Dynamik, Übergang, Dauer (wie GP5). Die Dateien belegen nur die Übereinstimmung mit PyGuitarPro, nicht die Kompatibilität mit Guitar Pro Desktop.
