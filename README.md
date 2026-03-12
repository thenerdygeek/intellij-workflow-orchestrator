# Workflow Orchestrator — IntelliJ Plugin

Eliminates context-switching between Jira, Bamboo, SonarQube, Bitbucket, and Cody Enterprise by consolidating the entire Spring Boot development lifecycle into a single IDE interface.

## Installation

1. Download `intellij-workflow-orchestrator-<version>.zip` from [Releases](https://github.com/example/intellij-workflow-orchestrator/releases)
2. Open IntelliJ IDEA 2025.1+
3. **Settings → Plugins → ⚙ → Install Plugin from Disk…** → select the ZIP
4. Restart IDE

## First-Time Setup

On first launch, a setup wizard appears to connect your services. You can skip it and configure later.

**Settings path:** **Settings → Tools → Workflow Orchestrator → Connections**

### Supported Services

| Service | Auth Type | Fields |
|---|---|---|
| Jira Server | PAT (Bearer) | Server URL, Access Token |
| Bamboo Server | PAT (Bearer) | Server URL, Access Token |
| Bitbucket Server | HTTP Access Token (Bearer) | Server URL, Access Token |
| SonarQube | User Token (Bearer) | Server URL, Access Token |
| Cody Enterprise (Sourcegraph) | Access Token (`token` scheme) | Server URL, Access Token |
| Nexus Docker Registry | Username + Password (Basic) | Registry URL, Username, Password |

**Note:** Nexus Docker Registry uses Basic auth with a username and password — it does NOT use a single access token like the other services.

## Settings Location

All plugin settings are stored per-project:

| OS | Settings File |
|---|---|
| macOS | `~/Library/Application Support/JetBrains/IntelliJIdea2025.1/projects/<project>/workflowOrchestrator.xml` |
| Linux | `~/.config/JetBrains/IntelliJIdea2025.1/projects/<project>/workflowOrchestrator.xml` |
| Windows | `%APPDATA%\JetBrains\IntelliJIdea2025.1\projects\<project>\workflowOrchestrator.xml` |

Also check `.idea/workflowOrchestrator.xml` inside each project directory.

### Settings Pages

- **Settings → Tools → Workflow Orchestrator → Connections** — service URLs and credentials
- **Settings → Tools → Workflow Orchestrator → Workflow Mapping** — Jira status transition rules
- **Settings → Tools → Workflow Orchestrator → Advanced** — timeouts, thresholds, feature toggles

## Credentials Storage

Credentials (tokens, passwords) are stored securely in the OS keychain via IntelliJ's `PasswordSafe`:

| OS | Storage |
|---|---|
| macOS | Keychain Access (service name: `WorkflowOrchestrator`) |
| Linux | GNOME Keyring / KWallet |
| Windows | Windows Credential Manager |

Credentials are **never** stored in plain XML files.

## Log Files

Plugin logs use the `[Module:Component]` prefix convention in the standard IDE log:

| OS | Log File |
|---|---|
| macOS | `~/Library/Logs/JetBrains/IntelliJIdea2025.1/idea.log` |
| Linux | `~/.cache/JetBrains/IntelliJIdea2025.1/log/idea.log` |
| Windows | `%LOCALAPPDATA%\JetBrains\IntelliJIdea2025.1\log\idea.log` |

**Grep for plugin logs:**
```bash
grep -E "\[Core:|Jira:|Bamboo:|Sonar:|Cody:|Automation:|Handover:" idea.log
```

### Key Log Prefixes

| Prefix | What It Covers |
|---|---|
| `[Core:Auth]` | Connection testing, auth headers |
| `[Core:Credentials]` | Token storage/retrieval |
| `[Core:Onboarding]` | First-run setup wizard |
| `[Core:Settings]` | Settings load/save |
| `[Core:EventBus]` | Cross-module events |
| `[Jira:Sprint]` | Sprint dashboard, ticket loading |
| `[Jira:Branch]` | Branch creation, ticket detection |
| `[Bamboo:Build]` | Build monitoring, polling |
| `[Sonar:Data]` | Coverage data, quality gate |
| `[Cody:Agent]` | Agent lifecycle, JSON-RPC |
| `[Cody:Chat]` | Chat sessions, commit messages |
| `[Automation:Queue]` | Build queue, tag validation |
| `[Automation:Drift]` | Docker tag drift detection |
| `[Handover:PR]` | PR creation, Jira closure |

## Clean Uninstall

### Step 1: Uninstall Plugin
**Settings → Plugins → Installed** → find "Workflow Orchestrator" → gear icon → **Uninstall** → restart IDE

### Step 2: Delete Settings
```bash
# macOS
find ~/Library/Application\ Support/JetBrains -name "workflowOrchestrator.xml" -delete

# Linux
find ~/.config/JetBrains -name "workflowOrchestrator.xml" -delete

# Windows (PowerShell)
Get-ChildItem -Path "$env:APPDATA\JetBrains" -Recurse -Filter "workflowOrchestrator.xml" | Remove-Item
```

Also delete from project directories:
```bash
find /path/to/your/projects -path "*/.idea/workflowOrchestrator.xml" -delete
```

### Step 3: Delete Credentials from OS Keychain

**macOS:**
```bash
# List matching entries
security find-generic-password -s "IntelliJ Platform" -a "WorkflowOrchestrator" 2>/dev/null
# Delete (repeat for each entry: NEXUS, NEXUS_PASSWORD, JIRA, BAMBOO, etc.)
security delete-generic-password -s "IntelliJ Platform" -a "WorkflowOrchestrator.JIRA" 2>/dev/null
security delete-generic-password -s "IntelliJ Platform" -a "WorkflowOrchestrator.NEXUS_PASSWORD" 2>/dev/null
```

Or open **Keychain Access** → search "WorkflowOrchestrator" → delete all matching entries.

**Linux:**
```bash
secret-tool search service "WorkflowOrchestrator"
# Then delete each entry
```

**Windows:**
Open **Credential Manager → Windows Credentials** → search for "WorkflowOrchestrator" → remove.

## Building from Source

```bash
git clone <repo-url>
cd IntelijPlugin
./gradlew buildPlugin
# Output: build/distributions/intellij-workflow-orchestrator-<version>.zip
```

### Requirements
- JDK 21
- IntelliJ IDEA 2025.1+ (for `runIde` testing)

### Useful Commands
```bash
./gradlew :core:test              # Run core unit tests
./gradlew verifyPlugin            # Check API compatibility
./gradlew runIde                  # Launch sandbox IDE with plugin loaded
./gradlew buildPlugin             # Build installable ZIP
```

## Architecture

```
:core        — Auth, HTTP, settings, events, caching, onboarding, tool window
:jira        — Sprint dashboard, branching, commit prefix, time tracking
:bamboo      — Build dashboard, polling, log parsing, CVE remediation
:sonar       — Coverage markers, quality tab, health check, Cody fix actions
:automation  — Docker tag builder, queue, drift detector, conflict detector
:handover    — Copyright enforcer, Cody pre-review, PR creation
:cody        — Cody agent lifecycle, chat, context enrichment
```

Feature modules depend only on `:core`, never on each other. Cross-module communication uses `SharedFlow` events via the event bus.
