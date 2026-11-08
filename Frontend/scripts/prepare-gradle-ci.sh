#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Retry distribution setup only; build and test failures must remain failures.
for attempt in 1 2 3; do
  if bash ./gradlew --version --no-daemon --console=plain; then
    exit 0
  fi
  if [[ "$attempt" -lt 3 ]]; then
    delay=$((attempt * 5))
    printf 'Gradle download failed (%s/3); retrying in %ss.\n' "$attempt" "$delay" >&2
    sleep "$delay"
  fi
done

printf 'Gradle download failed after 3 attempts.\n' >&2
exit 1
