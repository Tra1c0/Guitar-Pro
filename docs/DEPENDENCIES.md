# Abhängigkeiten, Quellen und Rechte

Stand der Prüfung: 4. November 2026. Eigene Veröffentlichungslizenz noch nicht festgelegt. Es wurden keine Fremdquellen, Testtabs, RSE-Sounds, SF2s, IRs oder Amp-Modelle übernommen. Die Oberfläche bündelt zwei OFL-Schriften (unten).

Die Fertigstellung hat **keine neue Bibliotheks-Abhängigkeit** hinzugefügt. Synthesizer, Effekte, Notensatz, Formatparser und der WAV-Decoder sind eigener Kotlin-Code; komprimierte Audiodateien decodiert Android selbst (MediaExtractor/MediaCodec gehören zum Betriebssystem, es werden keine Codecs mitgeliefert).

## Gepinnte Basis

| Komponente | Version | Lizenz / Verwendung |
|---|---|---|
| Gradle Wrapper | 8.13 | Apache-2.0; Distribution-SHA256 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78 |
| Android Gradle Plugin | 8.13.2 | Apache-2.0; Build |
| Kotlin / Compose Compiler | 2.2.21 | Apache-2.0 |
| Compose BOM | 2025.10.01 | Apache-2.0; genaue Bibliotheksauflösung über BOM |
| Activity | 1.11.0 | Apache-2.0 |
| Lifecycle | 2.9.4 | Apache-2.0 |
| Navigation | 2.9.5 | Apache-2.0 |
| AndroidX Core | 1.17.0 | Apache-2.0 |
| Coroutines | 1.10.2 | Apache-2.0 |
| Serialization | 1.9.0 | Apache-2.0 |
| JUnit | 4.13.2 | EPL-1.0; nur Tests |
| Robolectric | 4.16 | MIT; nur Tests |
| JDK / SDK | Java 17 / Android 36 | Build-Werkzeuge separat, nicht als eigene Runtime in der APK verteilt |

Maven-Publikation wurde gegen Google Maven/Maven Central geprüft. Ein gemeinsamer Build ist die maßgebliche Prüfung, nicht die bloße Existenz der POMs. Das konkrete Dependency-Inventar ist mit `./gradlew :app:dependencies --configuration debugRuntimeClasspath` reproduzierbar. Die vollständige Apache-2.0-Lizenz liegt unter third_party und als App-Asset vor.

## Mitgelieferte Schriften

| Schrift | Quelle | Lizenz / Verwendung |
|---|---|---|
| Bricolage Grotesque (variabel) | [google/fonts `ofl/bricolagegrotesque`](https://github.com/google/fonts/tree/main/ofl/bricolagegrotesque), SHA-256 `413e7357…ac682` | SIL OFL 1.1; Überschriften und Ziffern der Oberfläche (Design „Tabino Mobile“). Lizenztext in `third_party/` und in der APK unter `assets/licenses/`. |
| DM Sans (variabel) | [google/fonts `ofl/dmsans`](https://github.com/google/fonts/tree/main/ofl/dmsans), SHA-256 `8cd08d97…c1c0d` | SIL OFL 1.1; Fließtext der Oberfläche. Lizenztext wie oben. |

Die Schriften werden unverändert gebündelt, damit das Design nicht von Gerätefonts oder Play-Diensten abhängt. Notensatz-Export (SVG/PDF) nutzt weiterhin Systemschriften.

## Mitgelieferte Klänge

| Asset | Quelle | Lizenz / Verwendung |
|---|---|---|
| `assets/banks/guitar-emily-di.rwsb` (29,2 MB, 208 Regionen, 44,1 kHz/16 Bit mono), SHA-256 `f2d71f23…813af2` | [Karoryfer Emilyguitar](https://github.com/sfzinstruments/karoryfer.emilyguitar), Commit `b4920dc662fd9cad6dcaccdeecffdd91c8725d8c`: DI-Aufnahmen einer E-Gitarre, Töne im Kleinterzabstand Db2–D6, 4 Velocity-Stufen × 3 Round Robins, Releases und gedämpfte Anschläge | CC0-1.0 (laut Repository-Lizenz und Karoryfer-Angaben). Umgewandelt mit `tools/make_guitar_bank.py` (RWSB v2): 3 Layer, auf 2 s gekürzt, Schleife über ganze Perioden mit 40 ms Überblendung, Abklingrate gemessen; je Region Feinstimmung (Tonkörper, Cent) und Pegelnormierung gemessen (`tools/bank_analysis.py`, aus der v1-Bank mit `tools/tune_guitar_bank.py` erzeugt, PCM unverändert). Keine Pflicht zur Namensnennung, wird trotzdem genannt. |

Die Cabinet-Impulsantworten sind eigene, berechnete Entwürfe. Frei auffindbare IR-Pakete hatten ungeklärte oder nicht weitergebbare Lizenzen und wurden deshalb nicht gebündelt.

## Werkzeuge außerhalb der App

| Werkzeug | Version | Lizenz / Verwendung |
|---|---|---|
| [PyGuitarPro](https://github.com/Perlence/PyGuitarPro) | 0.11 | LGPL-3.0; nur `tools/make_gp_fixtures.py` zum Schreiben der eigenen GP3/4/5-Fixtures und ihrer JSON-Orakel. Weder gebündelt noch zur Laufzeit oder für den Gradle-Build nötig. |

## Recherchierte Kandidaten, bewusst nicht eingebaut

- [alphaTab 1.9.0](https://github.com/CoderLine/alphaTab/releases/tag/v1.9.0), Commit `9f1aa10460df1b326123d28a2222f7b20d081e61`: MPL-2.0. [Maven-POM](https://repo.maven.apache.org/maven2/net/alphatab/alphaTab/1.9.0/alphaTab-1.9.0.pom), [Android-View](https://github.com/CoderLine/alphaTab/blob/v1.9.0/packages/kotlin/src/android/src/main/java/alphaTab/AlphaTabView.kt), [Buildanforderungen](https://github.com/CoderLine/alphaTab/blob/v1.9.0/packages/kotlin/src/android/build.gradle.kts).
- [Android-Installation](https://www.alphatab.net/docs/getting-started/installation-android): kein Compose-Control; AndroidView-Einbettung. Das Dokument zeigt ältere Beispielversionen; deshalb Release-Quellen und Maven-Manifest gesondert geprüft.
- [Bounds](https://www.alphatab.net/docs/reference/api/boundslookup/), [GP7-Export](https://www.alphatab.net/docs/guides/exporter/), [Backing](https://www.alphatab.net/docs/features/play-sync/backing-track-audio/): dokumentierte Fähigkeiten, nicht hier praktisch verifiziert. Synth und Backing werden laut Dokumentation nicht gemeinsam gemischt.
- alphaTab.net-Attribution sichtbar lassen. MPL-/Notice-/Quellbereitstellungspflichten und zusätzliche Paket-/Font-/Soundbanklizenzen beim tatsächlichen Einbau prüfen.
- [fretwork](https://github.com/bubiche/fretwork), Commit `e72878a19ab4ff82bc3d29a936a498add95bdf3b`: MIT-Lizenzdatei und Architektur gelesen. Referenz für Command-History, getrennte Render-/MIDI-Invalidierung und Link-Reparatur; kein Code/Asset übernommen.
- [TuxGuitar](https://github.com/helge17/tuxguitar), Commit `9f2647dfc216401cc70569f1ab25ad03e18cab5b`: Haupt-README benennt LGPL. Android-/Java-Editorreferenz. Konkrete Modullizenzen müssten vor Wiederverwendung geprüft werden; keine Wiederverwendung erfolgt.
- Entscheidung alphaTab: nicht eingebaut. 1.9.0 verlangt SDK 37 und Kotlin-stdlib 2.4 (gepinnt sind SDK 36 und Kotlin 2.2.21), und Bearbeitung müsste über Bounds auf eigene IDs zurückübersetzt werden. Der eigene Notensatz in `core:notation` ersetzt ihn; `adapter:alphatab` bleibt eine erklärte UNAVAILABLE-Grenze.
- Entscheidung Audio: Oboe, TinySoundFont und FluidSynth sind nicht eingebaut. Die eigene Kotlin-Engine auf AudioTrack ist auf der JVM vollständig testbar und braucht keine Klangbank mit ungeklärten Rechten; ein Oboe-Adapter bleibt über `AudioSink` möglich.
- [Neural Amp Modeler](https://github.com/sdatkinson/neural-amp-modeler) (MIT): klanglich stark, Standardmodelle (WaveNet) für Echtzeit auf dem Telefon bei 8 Spuren zu teuer; nicht eingebaut. Ein leichtes LSTM-Modell pro Spur wäre ein späterer Kandidat, wenn die Gerätemessungen Luft zeigen.
- [FreePats Clean Electric Guitar](https://freepats.zenvoid.org/) (CC0, DI): Alternative zu Emilyguitar; hier nicht erreichbar, daher nicht verglichen.
- Oboe/TinySoundFont/FluidSynth/Signalsmith/NAM/Basic Pitch sind ausschließlich Kandidaten in den Folgeaufträgen. Ihre konkreten Versionen, Pflichten und Assetrechte werden erst vor Integration festgelegt.

## Evidenzklassen

**Quellenbehauptung:** oben verlinkte API-/Lizenz-/Publikationsangaben. **Getestete Beobachtung:** lokale Build-/Testberichte, siehe VALIDATION.md. **Technische Annahme:** spätere native Audio-/Samplerimplementierung erfüllt die definierten Ports. **Offen:** Geräteverhalten, hörbare Techniktreue, GP8-Desktop-Roundtrip, Cubase-Übergabe, iOS und lizenzgebundene Integrationen.
