# Eigene Verifikationsfixtures

Erzeugen: `./gradlew :data:local:generateFixtures`. Der separate Source-Set `fixtureTools` wird nicht in die App gepackt. Ausgabe ist deterministisch; `SHA256SUMS` dokumentiert die erzeugten Bytes.

- `drop-a-metal.riffproj` / `.json`: siebensaitige Drop-A-Gitarre (33/40/45/50/55/59/64), 150 BPM, 4/4 und 7/8, PM/Dead/Slide/Pinch/Bend sowie gehaltene andere Saite und Kick/Snare/HH. Eine serialisierte Technik belegt noch keine vollständige Gravur oder hörbare Umsetzung.
- `four-voices-nested-tuplets.riffproj` / `.json`: vier getrennte Stimmen; verschachteltes 3:2 × 5:4-Tuplet mit exakter Dauer 1/15 einer ganzen Note.
- `eight-tracks-200-bars.riffproj`: acht Spuren, 200 Takte, 12.800 Noten, stabile IDs und vollständige Stimmenstruktur. Struktur-/Viewport-Fixture, kein Nachweis von 60 fps.
- `shared-clock.riffproj` / `.json`: vier 4/4-Takte bei 120 BPM, eigene Referenz-WAV und Sync-Map.
- `clock-120bpm-48k.wav`: 8 s, 48 kHz, mono, PCM 16 Bit. Ein selbst erzeugter 128-Frame-Tonimpuls beginnt bei Frames 0, 24.000, …, 360.000. Anker bei 0 und 7,5 s. Loop-Ende bei Frame 384.000 ist exklusiv. Synth/Stem-Gleichlauf ist erst nach Spike B testbar.

Musik und Testsignal wurden für dieses Projekt erstellt. Das synthetische WAV ist CC0-1.0; es enthält weder Instrumentaufnahmen noch fremde Soundbanks. Die übrigen Projektquellen haben noch keine Veröffentlichungslizenz.

Es liegen noch keine verifizierten GP3/4/5/GPX/GP7/GP8-Dateien vor. Der Format-Spike muss solche selbst erstellten Dateien mit überprüfter Herkunft ergänzen. Dateinamen für den späteren Desktop-Gegencheck stehen in `docs/SPIKES.md`; keine native Fixture wird als Guitar-Pro-Datei ausgegeben.
