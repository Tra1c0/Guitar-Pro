# Projektformat und Recovery

Schema 1 wird durch die `@Serializable`-Typen in `core:model` definiert. Eine inkompatible Form benötigt eine neue Schema-Version und getestete Migration. Phase 1 hat keine Altversionen zu migrieren; neuere Versionen werden ausdrücklich abgelehnt.

Polymorphe Artikulationen verwenden feste Tags (`mark`, `bend`, `whammy`, `slide`, `harmonic`, `vibrato`, `trill`, `tremolo_picking`, `grace`, `dynamic`, `pickstroke`, `percussion`). Kotlin-Paket- oder Klassennamen sind kein Bestandteil dieses Wire-Vertrags.

## Privater Speicher

```text
workbench/
  projects/<project-id>/
    head.json
    head.previous.json
    revision-<n>.json
    snapshots/<snapshot-id>.json
    snapshots/<snapshot-id>.meta.json
  assets/<sha256>
  settings.json
  trash/<project-id>-<uuid>/
```

Revisionsdateien sind vollständige Snapshots. Head benennt Revision, Dateiname und SHA-256. Daten werden in eine Datei desselben Verzeichnisses geschrieben, per fsync abgeschlossen und atomar umbenannt. Erst dann wird der neue Head veröffentlicht. Der vorherige Head bleibt als Rückfall. Eine neuere Schema-Version führt nicht zu einem stillen Downgrade.

Unreferenzierte temporäre Dateien und Revisionsdateien gelten nicht als Commit. Korruption der aktiven Revision lässt sich über den vorherigen Head wiederherstellen; die UI erhält einen Hinweis. Sind beide Stände unbrauchbar, erscheint das Problem in der Bibliothek. Es gibt keine automatische Bereinigung alter Revisionen/verwaister Assets in Phase 1. Speicherkompaktierung ist ein eigener späterer Auftrag. Löschen im Repository verschiebt ein Projekt in trash; die UI bietet derzeit keine Projektlöschung an.

Atomare Sichtbarkeit bei Prozessabbruch wird getestet. Stromausfall, Provider-Verhalten, Mehrprozess-Schreibzugriff und Directory-fsync sind nicht als verifiziert ausgewiesen. Der unterstützte Schreibpfad hat eine Repository-Instanz pro Prozess plus optimistische Revisionsprüfung.

## Austauschdatei

ZIP-Inhalt: `manifest.json`, `project.json`, `assets/<sha256>`. Manifest benennt `riffworkbench-project`, Schema 1, Projekt-Hash und Asset-Deskriptoren. Alle referenzierten Original-/Audio-/IR-/Klangbankbytes müssen vorhanden und korrekt sein. Import erzeugt eine neue Projekt-ID und Revision 0; interne Musik-IDs bleiben erhalten.

Limits: 64 MiB komprimiert, 64 MiB je Eintrag, 256 MiB entpackt, 1024 Einträge und JSON-Tiefe 128. ZIP-Einträge werden unter laufenden Limits gelesen; Größenangaben werden nicht vertraut. Keine vom Archiv vorgegebenen Pfade werden extrahiert. Doppelte/unbekannte Einträge und falsche Prüfsummen werden abgelehnt. Erst das geprüfte Archiv darf Assets übernehmen; danach wird das neue Projekt gespeichert.

Native Dateien bewahren typisierte, derzeit noch nicht bearbeitbare Merkmale. Unverstandene GPIF-Daten liegen zusätzlich als Originalassets/ForeignData mit Referenzen und Ausgangsrevision vor. Native Speicherung behauptet nicht deren Gültigkeit für späteren GP-Rückexport. Der GP-Adapter muss Semantik und Referenzen nach Änderungen prüfen und verworfene Daten als LossEntry ausweisen.

Die Android-Dateiauswahl kopiert Bytes in den privaten Speicher. Verlorene Provider-Berechtigungen zerstören damit keine laufende Projektsitzung. Eine externe Exportdatei gehört zum Provider; dessen atomare Schreibgarantie kann die App nicht versprechen.
