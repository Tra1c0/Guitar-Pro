#!/usr/bin/env bash
# Zyklus 27 (automatische Updates), only for GitHub Actions (job "release"): publishes the signed debug APK as GitHub release
# "v<RW_VERSION_CODE>" with the asset "ArtiFret-<RW_VERSION_NAME>.apk". Notes = short log since the previous v<code> tag (else
# the commit message). Marked "latest" only when newer than the current latest release (parallel runs may finish out of
# order); never a prerelease, because /releases/latest ignores prereleases. A re-run replaces the asset of its own tag.
set -euo pipefail
: "${RW_VERSION_CODE:?}" "${RW_VERSION_NAME:?}" "${GITHUB_SHA:?}" "${RUNNER_TEMP:?}" "${GH_TOKEN:?}"
tag="v$RW_VERSION_CODE"
src="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$src" ] || { echo "::error::$src fehlt"; exit 1; }
apk="$RUNNER_TEMP/ArtiFret-$RW_VERSION_NAME.apk"
cp "$src" "$apk"

notes="$RUNNER_TEMP/release-notes.md"
prev=$(git describe --tags --abbrev=0 --match 'v[0-9]*' "$GITHUB_SHA^" 2>/dev/null || true)
if [ -n "$prev" ]; then
  git log --no-merges --format='- %s (%h)' "$prev..$GITHUB_SHA" | head -n 60 > "$notes"
else
  : > "$notes"
fi
[ -s "$notes" ] || git log -1 --format='- %s (%h)' "$GITHUB_SHA" > "$notes"
{ echo; echo "Installation: in ArtiFret unter Einstellungen > Updates > Installieren, oder die APK auf dem Handy öffnen."; } >> "$notes"
echo "Release-Text:"; cat "$notes"

current=$(gh release view --json tagName --jq .tagName 2>/dev/null || true)
current_code=${current#v}
case "$current_code" in ''|*[!0-9]*) current_code=0 ;; esac
latest=true
if [ "$current_code" -gt "$RW_VERSION_CODE" ]; then latest=false; fi

if gh release view "$tag" > /dev/null 2>&1; then
  gh release upload "$tag" "$apk" --clobber
else
  gh release create "$tag" "$apk" --target "$GITHUB_SHA" --title "ArtiFret $RW_VERSION_NAME" --notes-file "$notes" --latest="$latest"
fi
echo "Release $tag veröffentlicht (latest=$latest, vorher: ${current:-keins})."
