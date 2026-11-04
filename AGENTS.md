# RiffWorkbench contributor instructions

This is the phase-1 Android skeleton for the attached mobile score-editor briefing. Read README.md, docs/ARCHITECTURE.md, docs/CONTRACTS.md and spec/features.json before extending a subsystem.

- Keep core modules independent of Android, Compose, Java platform APIs and vendor models. Concrete wiring belongs in AppContainer.
- Publish musical changes through EditorSession commands. Preserve stable IDs, exact rational time, independent voices and per-note pitch curves. Detached proposals must check their base revision.
- A future feature must return a typed UNAVAILABLE result with a documented contract until implemented. Do not substitute fake playback, silent no-ops or empty successful exports.
- spec/features.json is the capability source of truth. After editing it, run `python3 tools/generate_registry.py`. Do not hand-edit FeatureRegistry.kt or PARITY.md.
- Native schema changes need an explicit version/compatibility decision. Preserve original foreign assets. Never claim GP7/GP8 fidelity from native serialization or an extension alone.
- Audio timing belongs to the native sample clock. Never use a UI timer as transport. Keep IO/allocations/locks out of real-time callbacks.
- Before integrating alphaTab, reconcile its published Android requirements and document MPL/attribution/asset duties. No third-party tabs, soundbanks, RSE content, IRs or models are bundled without provenance.
- Validation: `./gradlew check :app:assembleDebug :app:lintDebug`; fixture changes: `./gradlew :data:local:generateFixtures`. The UI tests use the actual MainActivity on Robolectric API 35 for debug/release. Real-device, GP Desktop and Cubase checks remain separate.
- Test reports and screenshots belong in build/ directories. Refresh evidence with tools/collect_validation.py after a full successful run. Do not label unperformed hardware or listening tests as passed.

JDK 17, Android SDK 36 / Build Tools 36.0.0 and Python 3 are required. Local SDK paths, proxy settings, signing keys and generated build directories must not be committed or included in the source archive.
