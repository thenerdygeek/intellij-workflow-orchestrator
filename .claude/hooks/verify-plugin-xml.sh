#!/usr/bin/env bash
# Post-edit guard: run `./gradlew verifyPlugin` after any plugin.xml edit.
# Plugin.xml typos silently break extension registration (the branch's auto-memory
# records a DatabaseSettings FQN typo that left a platform service unregistered).
# verifyPlugin catches the class of regression in ~20s.
set -euo pipefail

input="$(cat)"
file_path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')"

case "$file_path" in
  *plugin.xml) ;;
  *) exit 0 ;;
esac

cd "${CLAUDE_PROJECT_DIR:-$(pwd)}"
if ! out="$(./gradlew verifyPlugin --quiet 2>&1)"; then
  echo "verifyPlugin FAILED after edit to ${file_path}." >&2
  echo "$out" | tail -40 >&2
  exit 2
fi
