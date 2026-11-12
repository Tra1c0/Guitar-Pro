#!/usr/bin/env bash
# Zyklus 27 (automatische Updates), only for GitHub Actions: build number and update keystore for app/build.gradle.kts.
#  - RW_VERSION_CODE = run number + 1000 (always > 1, monotonic per workflow), RW_VERSION_NAME = "0.<run number>".
#  - KEYSTORE_BASE64 (secret ANDROID_KEYSTORE_BASE64) is decoded to $RUNNER_TEMP, never into the checkout; RW_KEYSTORE_FILE
#    points to it. The passwords and the alias come into the job as RW_* from the secrets directly.
#  - Without the secret: a notice in the log, the build stays as before (default debug key), output signed=false.
set -euo pipefail
: "${GITHUB_RUN_NUMBER:?}" "${GITHUB_ENV:?}" "${GITHUB_OUTPUT:?}" "${RUNNER_TEMP:?}"
code=$((GITHUB_RUN_NUMBER + 1000))
{ echo "RW_VERSION_CODE=$code"; echo "RW_VERSION_NAME=0.$GITHUB_RUN_NUMBER"; } >> "$GITHUB_ENV"
echo "Version: code $code, name 0.$GITHUB_RUN_NUMBER"
if [ -z "${KEYSTORE_BASE64:-}" ]; then
  echo "::notice title=Update-Signatur::Secret ANDROID_KEYSTORE_BASE64 fehlt - APK mit Standard-Debug-Schluessel, kein Release."
  echo "signed=false" >> "$GITHUB_OUTPUT"
  exit 0
fi
if [ -z "${RW_KEYSTORE_PASSWORD:-}" ] || [ -z "${RW_KEY_ALIAS:-}" ] || [ -z "${RW_KEY_PASSWORD:-}" ]; then
  echo "::error title=Update-Signatur::ANDROID_KEYSTORE_BASE64 ist gesetzt, aber ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS oder ANDROID_KEY_PASSWORD fehlt."
  exit 1
fi
file="$RUNNER_TEMP/artifret-update.p12"
printf '%s' "$KEYSTORE_BASE64" | tr -d ' \r\n' | base64 -d > "$file"
chmod 600 "$file"
if [ ! -s "$file" ]; then echo "::error title=Update-Signatur::Keystore leer nach dem Dekodieren."; exit 1; fi
echo "RW_KEYSTORE_FILE=$file" >> "$GITHUB_ENV"
echo "signed=true" >> "$GITHUB_OUTPUT"
echo "Update-Keystore dekodiert ($(stat -c %s "$file") Bytes); die Debug-APK wird mit dem Update-Schluessel signiert."
