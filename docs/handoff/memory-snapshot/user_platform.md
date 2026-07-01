---
name: user_platform
description: User develops on macOS but tests/uses the IntelliJ plugin on Windows
metadata: 
  node_type: memory
  type: user
  originSessionId: 642b8044-8e13-47a0-a293-32636bc54cc5
---

User develops on macOS (this machine) but tests/runs the IntelliJ plugin on a separate Windows machine. Plugin logs, runtime data (~/.workflow-orchestrator/), and IntelliJ sandbox are all on Windows — NOT on this macOS dev machine. Never look for plugin runtime logs here. When giving file paths or debugging instructions for the running plugin, use Windows paths and conventions.

**Dev machine (2026-06-30):** bought a 14" MacBook Pro, M5 Pro (15-core CPU / 16-core GPU), 24GB / 2TB, with AppleCare+, at ₹289,990 — upgrading FROM a severely RAM-constrained 8GB M1 Air (was running ~6.6GB swap). So pre-2026-06-30 perf complaints were likely the 8GB Air thrashing, not code. Broader stack beyond the plugin: Android dev (Studio, cmdline-tools, scrcpy, Maestro), Flutter, iOS (Xcode, cocoapods), Docker via **OrbStack** (not Docker Desktop), data/ML (Spark, Jupyter, ollama, R), Node via fnm, Python via pyenv, Ruby via rbenv. Video editing (Resolve/Insta360) is rare. On 24GB the heaviest *combined* days (Android Studio + IntelliJ + OrbStack + emulator) can approach the ceiling — habit: don't keep every heavy app open at once. Clean-setup checklist + Brewfile at ~/Desktop/ (new-mac-setup-checklist.md).
