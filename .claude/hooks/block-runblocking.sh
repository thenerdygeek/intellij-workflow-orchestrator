#!/usr/bin/env bash
# Pre-edit guard: block re-introduction of `runBlocking` in production .kt files.
# Phase 4 (Prongs A + A.2 + A.2b) removed every kotlinx.coroutines.runBlocking from
# main sources. This hook stops Claude-driven Edit/Write from regressing the inventory.
# Allowed:
#   - runBlockingCancellable (the sanctioned replacement)
#   - runBlocking in /src/test/ (tests routinely use it)
#   - manual edits via terminal/IDE (the hook only sees Claude tool calls)
set -euo pipefail

input="$(cat)"
file_path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')"

case "$file_path" in
  */src/main/*.kt) ;;
  *) exit 0 ;;
esac

# Edit → new_string ; Write → content
content="$(printf '%s' "$input" | jq -r '.tool_input.new_string // .tool_input.content // empty')"
[ -z "$content" ] && exit 0

# Mask the sanctioned form before searching the dangerous one.
filtered="$(printf '%s' "$content" | sed 's/runBlockingCancellable/__OK__/g')"
if printf '%s' "$filtered" | grep -qE '(^|[^A-Za-z_])runBlocking[[:space:]]*[\{(]'; then
  cat >&2 <<EOF
BLOCKED: introducing \`runBlocking\` in production code (${file_path}).

Phase 4 removed all runBlocking from main sources. Replacements:
  - BG-thread call site → \`runBlockingCancellable { ... }\`
  - EDT call site (JCEF bridge / Swing listener) → launch on a service-injected scope
  - Suspend caller → just \`await\` / \`withContext\`

If this is genuinely intentional (rare), edit via terminal/IDE — this hook only
sees Claude Edit/Write calls.
EOF
  exit 2
fi
