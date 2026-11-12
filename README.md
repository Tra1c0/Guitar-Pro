# ArtiFret

Android-first Offline-Editor für TAB und Partitur mit eigenem Notensatz, eigener Klangerzeugung und Guitar-Pro-Austausch. Die App läuft offline; einzige Netzwerkverbindung ist die optionale Update-Prüfung bei GitHub (abschaltbar, siehe [Updates](#updates)); es werden keine fremden Tabs, RSE-Inhalte oder Impulsantworten mitgeliefert; einzige mitgelieferte Klangbank sind die CC0-lizenzierten DI-Gitarrensamples Karoryfer Emilyguitar (siehe [DEPENDENCIES](docs/DEPENDENCIES.md)). Grundlage ist das [Briefing](docs/BRIEFING.md); welche Merkmale in welcher Tiefe fertig sind, steht in der [Paritätsmatrix](docs/PARITY.md).

## Was die App kann

**Eingabe und Bearbeitung**
- Projekte mit Gitarren (3–10 Saiten, freie Stimmungen, Teil-Capo), Bass, Drums und Keys; Spuren anlegen, duplizieren, umbenennen, umsortieren, Instrument wechseln (Tonhöhe oder Griff erhalten).
- Bünde mit Vorschau und Commit, Notenwerte, Punktierung, Tuplets, Pausen, Akkordmodus, vier unabhängige Stimmen. Jede eingetragene Note wird angespielt.
- Quick-Bar und Effektseite: Palm-Mute, Dead, Ghost, Akzente, Staccato, Let Ring, Haltebögen, Hammer-on/Pull-off, alle Slide-Arten, Bends (voll, ½, ¼, Release, Prebend), Whammy, Vibrato, Flageoletts (natürlich, künstlich, Pinch, Tap, Semi), Tapping, Slap/Pop, Tremolo-Picking, Triller, Vorschläge, Dynamik, Fermaten, Strum/Arpeggio/Rasgueado, Pickstroke.
- Takte einfügen, löschen, duplizieren, kopieren/einfügen (auch über alle Spuren), Bereichsauswahl, Transposition, Taktart, Tonart, Swing/Triplet Feel, Tempowechsel, Abschnitte, Wiederholungen mit Voltas, D.C./D.S./Coda/Fine.
- Drumgrid (1/8 bis 1/32 und Triolenraster) mit Artikulationen und Velocity auf demselben Notenmodell.
- Undo/Redo, benannte Schnappschüsse, Autosave mit Konflikterkennung, externe Tastatur mit Kürzeln (Ziffern, Pfeile, Strg+Z/Y/C/V/D, Leertaste, F4 wiederholt die letzte Aktion).

**Notensatz**
- TAB mit Rhythmus, Standardnotation (Schlüssel, Vorzeichen, Balken, Haltebögen, Hilfslinien) oder beides; Drum-Notation; fortlaufend oder umbrochen; Zoom per Pinch, Tippen setzt den Cursor, mitlaufender Playhead.

**Wiedergabe und Klang**
- Eigener Synthesizer: physikalisch modellierte Saiten mit Bends und Vibrato pro Note, PM/Dead/Flageolett/Legato, synthetische Drums mit Choke-Gruppen, Keys. Ton-Presets (High-Gain, Crunch, Clean, Akustik, Bass Pick/Finger, Keys) und editierbare Effektkette (Gate, Overdrive, Amp, Cabinet, EQ, Kompressor, Chorus, Delay, Reverb).
- Mixer mit Volume, Pan, Mute, Solo, Master. Wiederholungen werden korrekt aufgelöst, Tempo und Swing wirken auf die Wiedergabe.

**Üben**
- Geschwindigkeit 25–150 %, Loop über Taktbereich, Count-in, Metronom, Speed-Trainer, Stimmgerät über das Mikrofon.
- Referenz-Audio: eigene Aufnahmen (WAV, MP3, AAC/M4A, Ogg, FLAC) importieren, per Wellenform „Takt 1“ setzen, Pegel/Pan/Mute/Solo. Clips laufen sample-genau zur Partitur, auch bei Loop und Sprüngen – vorerst nur bei 100 % Tempo.

**Austausch**
- Import mit Inhaltserkennung (nicht nach Dateiendung): Guitar Pro 3–8, MusicXML, MIDI und eigene Projekte, siehe [Formatstand](#formatstand). Die Originaldatei bleibt im Projekt erhalten, nicht übernommene Merkmale stehen im Verlustbericht auf der Austausch-Seite.
- Export: natives `.riffproj`, GP7, MusicXML, MIDI, ASCII-TAB, SVG, PDF, WAV-Mix und Stems für die DAW. Jedes Fremdformat meldet, was es nicht abbilden kann.

## Formatstand

| Format | Import | Export |
|---|---|---|
| ArtiFret `.riffproj` | ja, vollständig | ja, vollständig inklusive Originale und Audio |
| Guitar Pro 3, 4, 5 (`.gp3/.gp4/.gp5`) | ja | – |
| Guitar Pro 6 (`.gpx`) | experimentell | – |
| Guitar Pro 7/8 (`.gp`) | ja (GP8-Extras wie Backing Track landen im Verlustbericht) | GP7-kompatibel |
| MusicXML (`.musicxml`, `.mxl`) | ja | ja (Noten + TAB-System) |
| MIDI (`.mid`) | ja (quantisiert, Bünde werden automatisch verteilt) | ja (Wiedergabereihenfolge mit Tempo-Map) |
| ASCII-TAB (`.txt`) | – | ja |
| SVG, PDF (A4) | – | ja |
| WAV-Mix, Stems + MIDI (ZIP) | – | ja (48 kHz, 24 Bit, gemeinsamer Startpunkt) |

Alles ist gegen selbst erzeugte Dateien und Rundreisen innerhalb der App geprüft, **nicht gegen Guitar Pro Desktop, MuseScore oder Cubase**. Siehe [offene Abnahmen](docs/SPIKES.md).

## Eigene Entscheidungen

- **Eigener Notensatz statt alphaTab** – alphaTab 1.9.0 passt nicht zur gepinnten Toolchain (SDK 37, Kotlin 2.4), und Bearbeitung über fremde Bounds wäre eine zusätzliche Übersetzungsschicht.
- **Eigener Synthesizer statt SoundFont/RSE** – keine Klangbank mit ungeklärten Rechten; Bends und Techniken pro Note statt Kanal-Controller; vollständig auf der JVM testbar.
- **AudioTrack in Kotlin statt Oboe/NDK** – Low-Latency-Float-Ausgabe mit Hardware-Zeitstempeln; die Engine bleibt testbar, ein Oboe-Adapter wäre nur eine weitere Ausgabe.
- **Referenz-Audio nur bei 100 %** – lieber stumm als falsch synchron, bis Time-Stretch existiert.
- **Schema 1 unverändert** – alle Phase-1-Projekte öffnen weiter.

Begründungen und Folgen: [Architektur](docs/ARCHITECTURE.md), [Abhängigkeiten und Rechte](docs/DEPENDENCIES.md).

## Was noch offen ist

- Geräte-Abnahmen: Latenz, Underruns, Bluetooth/USB-Routing, Thermik, Hörvergleich des Synthesizers, Stimmgerät mit echter Saite.
- Gegencheck in Guitar Pro Desktop und Cubase.
- Time-Stretch und Anker-Sync für frei gespielte Aufnahmen, SF2/eigene Samplebank, Bus-Routing und Automation, USB-MIDI-Eingabe, Line-in, Befehlspalette, Slash/Jianpu, Lyrics- und Akkordsymbol-Satz.

Jede offene Fähigkeit meldet in der App einen erklärten Status mit Vertrags-ID ([Verträge](docs/CONTRACTS.md)); es gibt keine vorgetäuschte Wiedergabe und keine leeren Erfolgsdateien.

## Installieren

Die CI baut bei jedem Push eine Debug-APK (Artefakt `riffworkbench-debug-apk` im jeweiligen Lauf unter *Actions*). Jeder grüne Push auf `ccr-0b650dff-jnhups-qa` wird zusätzlich ein GitHub Release (*Releases*, Datei `ArtiFret-0.<Lauf>.apk`), sobald der Signaturschlüssel als Secret hinterlegt ist; diese APK ist mit einem festen Schlüssel signiert und lässt sich danach direkt aus der App aktualisieren (siehe [Updates](#updates)).

## Updates

**Einrichtung (einmalig, Repository-Besitzer):** unter *Settings → Secrets and variables → Actions* vier Secrets anlegen: `ANDROID_KEYSTORE_BASE64` (PKCS12-Keystore, base64-kodiert), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`artifret`) und `ANDROID_KEY_PASSWORD`. Den Keystore sicher aufbewahren: Ohne denselben Schlüssel kann keine spätere Version über die installierte installiert werden. Fehlt das Secret, baut die CI wie bisher (Standard-Debug-Schlüssel) und veröffentlicht kein Release.

**Einmalige Umstellung auf dem Handy:** Die bisher installierte APK ist mit einem wechselnden Debug-Schlüssel signiert. Einmal: alle Projekte als `.riffproj` exportieren, ArtiFret deinstallieren, die neueste `ArtiFret-….apk` aus *Releases* installieren, Projekte wieder öffnen. Danach kommen Updates aus der App.

**Danach:** Beim Start fragt ArtiFret höchstens alle 6 Stunden bei GitHub nach einer neueren Version und meldet „Update … verfügbar“ mit „Installieren“; ohne Netz oder bei aktueller Version erscheint nichts. *Einstellungen → Updates* zeigt die installierte Version, schaltet die automatische Prüfung ab und hat „Jetzt prüfen“. Beim ersten Installieren fragt Android, ob ArtiFret Apps installieren darf („Unbekannte Apps installieren“); danach bestätigt man jede Installation im Systemdialog. Gesendet wird nur eine anonyme Anfrage an `api.github.com` (bzw. der APK-Download), keine Projekt- oder Nutzerdaten; Einzelheiten in [DEPENDENCIES](docs/DEPENDENCIES.md#netzwerk-und-updates-zyklus-27).

## Bauen und prüfen

Voraussetzung: JDK 17, Android SDK Platform 36, Build Tools 36.0.0 und Python 3. `ANDROID_HOME` oder eine lokale, nicht versionierte `local.properties` mit `sdk.dir=…` setzen. Der Wrapper prüft die Gradle-Distribution gegen ihren SHA-256-Hash.

```sh
./gradlew check :app:assembleDebug :app:lintDebug
./gradlew :data:local:generateFixtures
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

UI-Tests laufen auf Robolectric API 35; das ersetzt keinen Gerätetest. Stand der Prüfungen: [Verifikation](docs/VALIDATION.md).

## Weitere Dokumente

- [Architektur, Datenmodell und Zustandsflüsse](docs/ARCHITECTURE.md)
- [Vollständige Paritätsmatrix](docs/PARITY.md) und [maschinenlesbares Register](spec/features.json)
- [Dienstverträge](docs/CONTRACTS.md)
- [Offene Abnahmen und Roadmap](docs/SPIKES.md)
- [Testanleitung und Verifikationsstand](docs/VALIDATION.md)
- [Native Datei und Wiederherstellung](docs/PROJECT_FORMAT.md)
- [Quellen, Abhängigkeiten und Rechte](docs/DEPENDENCIES.md)
- [Eigene Fixtures](fixtures/README.md)

Nach einer Änderung an `spec/features.json`: `python3 tools/generate_registry.py`. `verifyArchitecture` verhindert veraltete Ableitungen, unbekannte Routen/Verträge, Kernimporte von Android/Java und zyklische Modulabhängigkeiten.

Für die eigenen Anwendungsquellen ist noch keine Veröffentlichungslizenz festgelegt. Drittanbieter behalten ihre jeweiligen Lizenzbedingungen; die synthetischen Testsignale und Fixtures sind separat als CC0 ausgewiesen.
