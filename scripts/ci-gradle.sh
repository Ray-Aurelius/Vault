#!/usr/bin/env bash
# Runs Gradle in CI. On failure, copies the key error lines into a GitHub annotation so the cause is
# visible on the pull request without opening the raw log.
set -uo pipefail
log="$(mktemp)"
./gradlew "$@" --console=plain 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  details=$(grep -E -A3 "What went wrong|Dependency verification failed|on artifact|FAILED|^e: |error:|Caused by|Could not|> " "$log" \
    | grep -v '^--$' | head -60)
  # GitHub annotations need %, CR and LF escaped.
  details="${details//'%'/'%25'}"
  details="${details//$'\r'/'%0D'}"
  details="${details//$'\n'/'%0A'}"
  echo "::error title=Gradle failed (${*})::${details}"
fi
exit "$status"
