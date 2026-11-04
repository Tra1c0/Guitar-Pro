# Abhängigkeiten, Quellen und Rechte

Stand der Prüfung: 4. November 2026. Eigene Veröffentlichungslizenz noch nicht festgelegt. Es wurden keine Fremdquellen, Testtabs, RSE-Sounds, SF2s, IRs oder Amp-Modelle übernommen. Die App verwendet Systemfonts.

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

## Recherchierte Kandidaten, noch keine Runtime-Abhängigkeiten

- [alphaTab 1.9.0](https://github.com/CoderLine/alphaTab/releases/tag/v1.9.0), Commit `9f1aa10460df1b326123d28a2222f7b20d081e61`: MPL-2.0. [Maven-POM](https://repo.maven.apache.org/maven2/net/alphatab/alphaTab/1.9.0/alphaTab-1.9.0.pom), [Android-View](https://github.com/CoderLine/alphaTab/blob/v1.9.0/packages/kotlin/src/android/src/main/java/alphaTab/AlphaTabView.kt), [Buildanforderungen](https://github.com/CoderLine/alphaTab/blob/v1.9.0/packages/kotlin/src/android/build.gradle.kts).
- [Android-Installation](https://www.alphatab.net/docs/getting-started/installation-android): kein Compose-Control; AndroidView-Einbettung. Das Dokument zeigt ältere Beispielversionen; deshalb Release-Quellen und Maven-Manifest gesondert geprüft.
- [Bounds](https://www.alphatab.net/docs/reference/api/boundslookup/), [GP7-Export](https://www.alphatab.net/docs/guides/exporter/), [Backing](https://www.alphatab.net/docs/features/play-sync/backing-track-audio/): dokumentierte Fähigkeiten, nicht hier praktisch verifiziert. Synth und Backing werden laut Dokumentation nicht gemeinsam gemischt.
- alphaTab.net-Attribution sichtbar lassen. MPL-/Notice-/Quellbereitstellungspflichten und zusätzliche Paket-/Font-/Soundbanklizenzen beim tatsächlichen Einbau prüfen.
- [fretwork](https://github.com/bubiche/fretwork), Commit `e72878a19ab4ff82bc3d29a936a498add95bdf3b`: MIT-Lizenzdatei und Architektur gelesen. Referenz für Command-History, getrennte Render-/MIDI-Invalidierung und Link-Reparatur; kein Code/Asset übernommen.
- [TuxGuitar](https://github.com/helge17/tuxguitar), Commit `9f2647dfc216401cc70569f1ab25ad03e18cab5b`: Haupt-README benennt LGPL. Android-/Java-Editorreferenz. Konkrete Modullizenzen müssten vor Wiederverwendung geprüft werden; keine Wiederverwendung erfolgt.
- Oboe/TinySoundFont/FluidSynth/Signalsmith/NAM/Basic Pitch sind ausschließlich Kandidaten in den Folgeaufträgen. Ihre konkreten Versionen, Pflichten und Assetrechte werden erst vor Integration festgelegt.

## Evidenzklassen

**Quellenbehauptung:** oben verlinkte API-/Lizenz-/Publikationsangaben. **Getestete Beobachtung:** lokale Build-/Testberichte, siehe VALIDATION.md. **Technische Annahme:** spätere native Audio-/Samplerimplementierung erfüllt die definierten Ports. **Offen:** Geräteverhalten, hörbare Techniktreue, GP8-Desktop-Roundtrip, Cubase-Übergabe, iOS und lizenzgebundene Integrationen.
