# API Probe Toolkit — Design Notes

**Branch:** `fix/automation-handover-quality-tabs` (the API-audit branch)
**Scope:** Captures the design rationale for `tools/atlassian-probe/{probe_jira.py, redact.py, bundle.py}` so future sessions extending the toolkit (Bitbucket probe, Nexus probe) don't have to reverse-engineer the why from commit messages.

For **how to use** the tools, see `tools/atlassian-probe/README.md`. This doc only covers the **why**.

---

## 1. Overall pipeline

```
probe_<product>.py
   ↓ writes Result_N/{summary.md, raw/*.json}
redact.py --in Result_N
   ↓ writes Result_N_redacted/   (structured + custom-word redaction)
bundle.py pack --in Result_N_redacted [--compress]
   ↓ writes Result_N_redacted.bundle.txt or .bundle.b64.txt
USER PASTES BUNDLE INTO CHAT
   ↓
bundle.py unpack --in <bundle>
   ↓ verifies SHA256 round-trip, refuses corrupted parts
```

Three responsibilities, three scripts, one direction. No back-channel; nothing reads from the redacted dir except bundle, nothing reads from the bundle except unpack.

---

## 2. Probe (`probe_<product>.py`)

**Read-only.** Per the user's explicit instruction (2026-05-06): no transition execution, no comment posting, no worklog posting, no merges/declines, no branch creation. POST is allowed only for endpoints that are functionally search/lookup (`/rest/api/3/search/jql`, `/rest/api/2/search/approximate-count`) — those are idempotent in effect.

**Three modes per probe:**
- `--versions-only` — minimum-viable connectivity check (4 calls). Tells the user the deployment type + version before they commit to a full sweep.
- `--discover` — derives sensible parameter values from the user's actual work (assigned/reported/watched issues → project keys → boards → sprints), so the full sweep doesn't require digging through the product's UI for IDs. Falls back to "list visible projects" only when the user has zero activity.
- Full sweep — exercises every endpoint the plugin currently calls + a curated list of candidates.

**Categories** (`version` / `existing` / `internal` / `candidate`) drive how the summary table groups results. Internal endpoints (`/rest/dev-status/`, `/rest/api/1.0/labels/suggest`) always carry a "no public deprecation policy" note.

**Per-call output:** every probe writes `raw/<name>.json` with the full request metadata, response body, and a `request_body` field for POST probes. The `summary.md` is the executive overview; raw files exist for cases where the JSON shape itself is the answer (e.g., "what's the schema of a transition's field metadata?").

---

## 3. Redact (`redact.py`)

### Threat model

The user shares the redacted output with an LLM (in chat) so the LLM can analyze API behaviour. We assume the chat transcript could be visible to anyone with access to it — the redaction should defeat both casual readers and someone trying to reverse the data programmatically.

### Two-tier redaction

**Tier 1 — structured redaction.** Built-in handling for known field shapes:
- Hostnames (auto-detected from `self` / `baseUrl` URLs in raw bodies) → `jira.redacted.example`
- Email addresses → `user-N@redacted.example` (regex)
- Issue keys (`PROJ-1234` style) → `KEY-001` (regex; project component stays consistent across keys, so `PROJ-1234` and `PROJ-9876` both become `PROJ-NNN`)
- User names / display names — only when the surrounding object has user-indicator keys (`emailAddress`, `avatarUrls`, `accountId`, `active`)
- Free-text fields (`description`, `summary`, `body`, `comment`) → `<redacted-text:N-chars>` length-preserving placeholder
- Commit hashes / branch names in dev-status responses → `<commit-N>` / `<branch-N>`

These run first and produce a structurally consistent placeholder space.

**Tier 2 — custom-word substring markers.** A user-editable `CUSTOM_REDACT_WORDS` list at the top of `redact.py`. Each entry is a substring marker. The redactor finds any **maximal run of `[a-zA-Z0-9_\- ]` characters** that contains the marker (case-insensitively, anywhere within the run), and replaces the entire run with a same-length random string preserving case-class per character.

Why "run" includes hyphens, underscores, and spaces: enables matching multi-word brand names (e.g., marker `"acme"` catches `"AcmeCorp internal"` as one unit). The trade-off is that sentences containing the marker get redacted whole — explicitly documented; users should choose specific markers.

Why no start-boundary requirement: the user explicitly asked that `subclaude` and `1claude` be redacted when marker is `"claud"`. Substring matching anywhere within the run is more conservative (catches more) than start-only.

### Stable per-run mapping

Same exact input string → same fake string within a single `redact.py` invocation. So `"AcmeCorp"` appearing in 5 files all show the same redaction (cross-file linkage stays intact for the analyst). Different case patterns of the same word (e.g., `"AcmeCorp"` vs `"ACMECORP"`) get **independent** fakes — their per-character case patterns differ, so case-class preservation requires independent outputs. Mapping regenerates from scratch on every run.

### CSPRNG-backed randomness

Replacement uses `random.SystemRandom()` (default), backed by `os.urandom`. Mersenne Twister was rejected because an attacker observing enough output of a single MT instance can reconstruct internal state and predict subsequent draws. SystemRandom has no internal state to recover. Tests inject a seeded `random.Random` for reproducibility; production never sees a fixed seed.

### What's preserved by design (intentional structural disclosure)

- Length of every redacted run
- Case-class pattern (which positions were upper / lower / digit)
- Position of underscores, hyphens, spaces inside the run
- "Same string appears 5 times" — the cache makes that observable

These are necessary for the analyst to spot schema patterns. If a future audit needs total opacity, add a `--paranoid` mode that replaces every run with a fixed `<redacted-N>` token regardless of length. Not built today.

### What is NOT preserved

- Original characters at any position (uniform random within character class; no algebraic relationship between input and output)
- The mapping itself — never written to disk; only kept in memory during one `redact.py` run

---

## 4. Bundle (`bundle.py`)

### Format choice: MIME-multipart-inspired text

**Plain mode** (`bundle.py pack --in DIR`):
```
# atlassian-probe bundle v1
# boundary: atlprobe-<16-hex>
# files: 38
# generated: <timestamp>

--atlprobe-<boundary>
path: relative/path.json
size: 1234
sha256: <hex>

<full file content>
--atlprobe-<boundary>
...
--atlprobe-<boundary>--
```

UUID-based boundary (~64 bits entropy) makes collision with file content statistically impossible. Each part has its own SHA256 — corruption from paste truncation or accidental edits is detected and the unpacker refuses to write the file. Format is human-readable so the user can sanity-check the bundle before sharing.

**Why not tar/zip + base64:** opaque binary format breaks the human-readability goal; some chat clipboards mangle binary; base64 expansion is no better than plain text for typical probe sizes.

### Compression mode (`--compress`)

Same multipart text → `gzip -9` → base64 → 76-col-wrapped → outer header with original SHA256. Typical 5–40× shrink (JSON compresses extremely well due to repeated keys). Output filename gets `.bundle.b64.txt` suffix to distinguish.

The unpacker auto-detects compressed vs plain via the header line (`# atlassian-probe bundle (compressed) v1`). No user-visible flag on unpack.

**Why outer SHA256 in addition to per-part SHA256:** if the base64 blob gets a few characters stripped during paste, gzip might silently decompress garbage. The outer hash over the original-decompressed bytes catches that case. Per-part hashes catch per-file corruption. Both layers are needed.

### Lessons from the format-design bug

The first compressed-mode implementation packed `header + wrapped` directly with no blank-line separator. The unpacker used `if line == ""` as the header/body delimiter and walked 100% of the body into `header_lines`. The decompressed body became `b""` whose SHA256 is `e3b0c44298fc1c14...` — the SHA of the empty string is a useful "this serialized to nothing" tell.

Fix was a one-line change: write `"\n".join(header_lines) + "\n\n" + wrapped + "\n"`. Codified now with end-to-end round-trip tests on a 3MB / 46-file fixture.

---

## 5. Reusing this toolkit for non-Atlassian products

The probe driver is product-specific (each REST surface is different). The redactor and bundler are **product-agnostic** — both operate on `Result_N/raw/*.json` shape and don't know what's in those files. To audit a new product:

1. Write `tools/<product>-probe/probe_<product>.py` with the same modes (`--versions-only`, `--discover`, full sweep).
2. Mirror the four categories (`version` / `existing` / `internal` / `candidate`).
3. Save responses as `Result_N/raw/<name>.json` with the exact same wrapper format (`{"result": {...}, "request_body": ..., "raw_body": ...}`).
4. The user's `redact.py --in Result_N` and `bundle.py pack --in Result_N_redacted` calls work without any further changes.

### Auth quirks to handle in the probe driver

- **Atlassian (Jira / Bitbucket DC):** `Authorization: Bearer <PAT>` — direct, no challenge flow.
- **Nexus REST v1:** `Authorization: Basic <base64>` — basic auth.
- **Docker Registry v2:** OAuth bearer-challenge — the first request gets a 401 with `WWW-Authenticate`, the client follows the realm to get a token, retries. Already implemented in `DockerRegistryClient.kt`; the Nexus probe driver should mirror this rather than reuse the simpler flows.

---

## 6. Files to read for context (in order)

1. `tools/atlassian-probe/README.md` — operational how-to
2. `tools/atlassian-probe/probe_jira.py` — reference probe driver
3. `tools/atlassian-probe/redact.py` — top-of-file comment block on `CUSTOM_REDACT_WORDS` is authoritative on the matching rules
4. `tools/atlassian-probe/bundle.py` — top-of-file docstring on the format
5. This doc — design rationale only
6. `docs/research/2026-05-06-jira-audit-handoff.md` — Jira-specific
7. `docs/research/2026-05-06-bitbucket-audit-brief.md` — Bitbucket-specific
8. `docs/research/2026-05-06-nexus-audit-brief.md` — Nexus-specific
