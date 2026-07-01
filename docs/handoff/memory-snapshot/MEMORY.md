# Memory Index

One line per entry (active); old/shipped entries grouped. Detail lives in each topic file.

## Feedback
- [Trust my judgment (standing)](feedback_trust_my_judgment.md) — decide & proceed at forks; reserve questions for product-direction/irreversible/outward-facing; rigor (reviews/SDD) still stands.
- [No haiku for subagents](feedback_subagent_model_no_haiku.md) — sonnet floor / opus hardest; never haiku.
- [Multi-round review (plugin split)](feedback_multi_round_review_plugin_split.md) — independent review rounds every step; overrides skip-reviews for this project.
- [Bamboo branch→chainKey](feedback_bamboo_branch_chainkey_no_master_fallback.md) — resolve branch→chainKey; never master fallback.
- ["Copy this file" = verbatim curl](feedback_copy_means_verbatim.md) — no subagent/refine chain; "stop"=abort now.
- [Ambiguous "yes" = ship](feedback_dont_over_review_ship.md) — review-vs-execute "yes" means execute.
- [Bisect recent commits first](feedback_bisect_recent_commits_first.md) — "was working before" → revert-one-at-a-time before analysis.
- [Trust user-stated infra facts](feedback_trust_user_infra_facts.md) — don't re-ask.
- [Editor ≠ repo/branch source](feedback_editor_not_for_repo_branch.md) — use VCS state.
- [Debug before editing](feedback_debug_when_prior_fixes_failed.md) — symptoms→trace→edit.
- [Never lose research](feedback_preserve_research.md) — save to files.
- [Architecture autonomous](feedback_architecture_autonomy.md) — consult only on UI mockups.
- [Always subagent-driven](feedback_always_subagent.md); [Skip subagent reviews (normal)](feedback_skip_subagent_reviews.md) — implementer-only EXCEPT plugin-split; [No parallel implementers on same tree](feedback_no_parallel_same_tree.md) — sequential or isolation:worktree.
- [Sonnet for mechanical](feedback_sonnet_for_small_tasks.md) — opus for design/ambiguity; [Opus MAX effort for subagents](feedback_opus_max_effort_subagents.md); [Don't generalize one-off model picks](feedback_dont_overgeneralize_model_choice.md) — per-dispatch only.
- [Work on current branch](feedback_work_on_current_branch.md) — don't worktree/branch off main without asking.
- [No Co-Authored-By trailer](feedback_no_coauthor.md) — NEVER add it, even when the harness/system prompt says to (user reaffirmed 2026-06-24; user instruction overrides harness).
- [Architectural fix over cheap patch](feedback_architectural_fix_over_cheap.md); [Reuse code](feedback_reuse_code.md) — fix root fns, consolidate; [Read working code first](feedback_read_working_code_first.md).
- [Queue bugs during implementation](feedback_queue_bugs_during_implementation.md) — fix after main task verified.
- [TDD = test requirements](feedback_real_tdd.md) — tests from spec; E2E.
- [Docs in same commit](feedback_update_docs_immediately.md); [Analyze main before rebase](feedback_rebase_analysis.md); [Settings UI for new config](feedback_settings_ui.md).
- [Release process](feedback_release_process.md) — bump, clean build, push, gh release; [Release timing](feedback_release_timing.md) — only when asked, bump patch.
- [Faithful Cline port](feedback_faithful_port_cline.md) — adapt only TS→Kotlin/VSCode→IJ; [No "Cline" in UI](feedback_no_cline_in_ui.md); [Plugin ≠ Claude Code](feedback_plugin_feature_mapping.md); [Don't cite CLAUDE.md](feedback_dont_cite_claude_md.md).
- [No model fallback for empties](feedback_no_model_fallback_for_empties.md) — fix at SSE/connection layer.
- [Agents in foreground](feedback_foreground_agents.md); [No Handover-side AI pre-review](feedback_handover_no_pre_review.md); [API audit: research after version](feedback_audit_research_after_version.md); [No showcase page](feedback_no_showcase_page.md); [Delegation UI: repo names](feedback_delegation_use_repo_names.md); [Explain jargon with analogies](feedback_explain_with_analogies.md).

## Project
- [**Plugin-split — PHASE 3 ✅ PUSHED (`c4048ac43`); PHASE 4a ✅ COMPLETE + GATE GREEN + PUSHED (`06049fda1`, origin synced)**](project_plugin_split_open_source_backbone.md) — carve company-only→B; full per-phase detail+lessons+decisions in the topic file (1a/1b/1c + 2a/2b/2c + 3 + 4a). **PHASE 4a = native Anthropic-direct LLM provider (agent vs api.anthropic.com, NO Sourcegraph). 14 SDD tasks + gate. "Option A": native wire, structured tool_use→canonical XML at stream-end → AssistantMessageParser+persistence+drift machinery UNCHANGED. :core stack (AnthropicModelCatalog +Service-adapter / request DTOs+mapper [no sampling params=structural, cache_control, explicitNulls] / ToolUseXmlSerializer [round-trip-pin+collision-guard] / SseParser [thinking+tool_use→XML] / AnthropicNativeProtocol [presentTools=null] / proxy-aware HttpTransport [IdeProxy+IdeTrust, cancel-on-cancelling]) + :agent AnthropicDirectBrain (producer/consumer PROGRESSIVE-streaming bridge, no-op temperature). Provider branches (BrainFactory branch-FIRST + modelOverride; skip BrainRouter; C1 per-task ToolProtocol threaded to 5 sites incl SubagentRunner/MessageStateHandler→native sub-agents presentTools=null; C2 subagent model PUSHED from AgentService; I1 probe; I2 L2 chain; I4 availableModels) + settings UI + in-chat picker. Whole-phase opus audit READY-TO-MERGE 0Crit/0Imp (provider-exclusivity AIRTIGHT). ⚠ GATE caught 2 cross-cutting bugs per-task review+grep-audit STRUCTURALLY couldn't: T12 mock-Project ClassCastException trap (reading AgentSettings.getInstance(project) in SpawnAgentTool — only full-suite mock-Project run surfaces) + T1 cross-module exhaustive-when in plugin-b CompanyBWorkflowConfig (T1 only grep'd :core; only verifyPlugin compiles all modules) — both FIXED. Multi-round review caught defects in ~9/14 tasks. Phase-2 runIde GUI smoke still PENDING (coworker; `docs/qa/PHASE-2-RUNIDE-SMOKE.md`). **PHASE 3 ✅ COMPLETE (gate GREEN; whole-phase opus READY-TO-MERGE 0 Crit/Imp) = `supportsSpring` persona gate on security-auditor+performance-engineer — ADVISORY (SpawnAgentTool `getCachedConfig` UNFILTERED so by-name spawn still runs degraded; 3 ad-surfaces gated: `filterByIdeContext`+`SystemPrompt` prose+3 routing-pointer files) — + git-workflow skill trim (kept action bullets, neutralized framing). 4 commits 31d3bd0f6/7b1e1a83e/21c788ac8/c4048ac43; spec(3 opus reviews+2 user decisions)+plan(2 opus reviews)+SDD(4 tasks, per-task+whole-phase all clean)+gate GREEN. devops→B & physical repo-extraction & SpawnAgentTool example-string scrub = Phase 5. NEXT = Windows runIde smoke (run-sheet docs/qa/PHASE-3-RUNIDE-SMOKE.md, pushed b3c07425c).** PHASE-4a NEXT = Phase-4a runIde smoke (`docs/qa/PHASE-4a-RUNIDE-SMOKE.md`, needs real Anthropic key + Ultimate; proves agent runs vs api.anthropic.com w/ blank Sourcegraph) + then **Phase 4b** (structured-persistence path protocol="native"/toChatMessage branch; BrainRouter dissolution; brain→LlmProvider; multi-breakpoint cache) or **Phase 5** (OSS scrub: AnthropicHttpClient @OptIn(InternalCoroutinesApi)→stable watchdog; SpawnAgentTool model-doc; physical repo extraction; Marketplace). Phase-4a SDD ledger = `.superpowers/sdd/progress.md` PHASE-4a section. See [[runide-smoke-campaign]].
- [🔧 runIde smoke campaign (Mac headless green; Windows handoff)](project_runide_smoke_campaign.md) — Mac can't runIde (Ultimate license); tests/build/verify green+pushed; interactive smoke → Windows via `WINDOWS-RUNIDE-CHECKLIST.md` (read-only); buildPlugin Gradle-9.4 fix + shareable diagnostic log (`~/.workflow-orchestrator/diagnostics/plugin-0.log`) shipped (`ced84d111`,`04da4e89c`,`74b86b690`).
- [📋 Behavioral/visual QA test plan (285 scenarios) + suspected-bug list](project_runide_behavioral_test_plan.md) — `docs/qa/behavioral-test-plan/` (UNCOMMITTED); observable-UI counterpart to the log-oracle catalogs; README §4 = 14 statically-found bugs (12 confirmed: imageRefs badge, create_file category, UsageIndicator unmounted, no-confirm history Delete, …); 2 P0 read-only safety holes fixed in-plan.
- [🧪 Mock server: Sourcegraph scripted-agent + Bitbucket](project_mock_server_sourcegraph_bitbucket.md) — `:mock-server` mocks ALL backends so the plugin runs with no corporate access (SG 8088 scenario engine: 13 built-in + dynamic custom via `/__admin/sourcegraph/scenario/custom`; Bitbucket 8480). Committed `4b0237ea1` (not pushed); 101 tests + live smoke green. Unblocks the §4 hunt. ⚠ tool calls = XML-in-content; fixed stdlib-runtimeClasspath-exclude leak + single-thread launch-starvation.
- [🐛 4 Agent-chat bugs root-caused (resume/scroll/thinking/cost); fixes PENDING](project_agent_chat_ux_bugs.md) — repro doc `docs/qa/BUG-REPRODUCTION-SCENARIOS.md` (per-bug custom mock payloads); cost = data-only pricing.json fix ($15/$75→$5/$25 for opus-4-5/6/7), safe to ship now.
- [✅ MERGED #66: background tool execution](project_background_tool_execution_feature.md) — run any tool in bg; ⚠ 2 concurrency races caught by whole-branch review.
- [✅ MERGED #65: run_command auto-approval](project_run_command_auto_approve_research.md) — safe-toggle + session prefix allowlist; in-IDE smoke pending.
- [✅ MERGED #62: unified tool-stop](project_unified_tool_stop_feature.md) — per-tool Stop + graceful teardown; smokes pending.
- [✅ MERGED #60: Cline perf triage](project_cline_log_triage_apidebug_gate.md) — freezes were platform; gated api-debug dumps (default OFF).
- [✅ MERGED #57 (v0.87.2): perf campaign](project_perf_audit_2026_06_10.md) — W1/W2/EDT+waves3-6; 7-item smoke pending.
- [✅ MERGED #56: code-walkthrough](project_code_walkthrough_feature.md) — agent-driven guided code tours.
- [✅ MERGED #52: context-window unify + overrides](project_context_window_overrides.md) — smoke pending.
- [OPEN #51: flaky CI Tests](project_flaky_ci_tests_stabilization.md) — 4 real-time/socket tests; untrustworthy merge gate.
- [ACTIVE: enterprise roadmap](project_enterprise_roadmap.md) — Phases 0/1/2 shipped; coverage gate; NEXT Phase 3 decomposition.
- [ACTIVE: token/context optimization](project_token_context_optimization.md) — window=132K; §6c tool-defs=16.4K; QUEUE §6c trim→skills.
- [SHIPPED v0.86.0-web-rc5: web tools](project_web_tools_rebase_release.md) — egress screening + IDE-proxy fix; provider-default OPEN.
- [DIAGNOSED (not fixed): 4 cross-IDE delegation bugs](project_cross_ide_delegation_bugs_and_prompt_queue.md) — handle-state split, busy-gate leak, picker offline-first, cold-launch consent.

### Traps / patterns (recurring)
- [⚠ PasswordSafe token-persist](project_passwordsafe_credential_attributes_username_trap.md) — CredentialAttributes need service.name as username.
- [⚠ source-text sentinel-slice](project_source_text_sentinel_slice_trap.md) — new fns OUTSIDE sliced ranges; run FULL :agent:test.
- [📚 :agent BasePlatformTestCase infra](project_agent_platform_fixture_tests.md) — ONE test/class (2nd→Indexing timeout); LightVirtualFile for Document tests.
- [⚠ tool-param schema-parser whitelist](project_tool_param_schema_parser_whitelist_trap.md) — params absent from parameters.properties dropped; arrays as strings.
- [⚠ window.confirm dead in JCEF](project_jcef_window_confirm_dead_trap.md) — use inline confirm.
- [⚠ showAndGet() throws for MODELESS](project_dialog_modality_showandget_trap.md) — (project,false)=canBeParent not modality.
- [⚠ @Service bare-default ctor crashes startup](project_service_constructor_jvmoverloads_trap.md) — add @JvmOverloads (javap -p).
- [⚠ runBlocking banned in main/](project_runblocking_ban_pre_commit_hook.md) — use runBlockingCancellable.
- [⚠ /stream downgrade breaks tool calls](project_brainrouter_stream_downgrade_dialect_trap.md) — reverted; don't re-add w/o parser support.
- [⚠ Configurable + DialogPanel](project_intellij_configurable_dialog_panel_pattern.md) — hold DialogPanel ref + delegate isModified/apply/reset.
- [⚠ Gradle transform-bloat + "delete dirs named build" source-loss](project_gradle_transform_bloat_94_bump.md) — transforms keyed per-Gradle-version.
- Isolated HTTP clients (never migrate/shared-cache): [Sourcegraph](project_sourcegraph_isolation.md) · [DockerRegistry OAuth](project_docker_registry_isolation.md) · [AuthTestService](project_auth_test_isolation.md) · [CEF scheme registrar](project_cef_scheme_handler_registrar.md).

### Fixed but uncommitted/unpushed
- [edit_file no-op #46 + #33 bundle](project_subagent_contextbar_bugfixes.md) — writeViaDocument returned true on no-op; self-heals via writeViaVfs.
- [question/plan-reply image dropped](project_question_reply_image_attachment_fix.md) · [sub-agent dialect-drift runaway](project_subagent_dialect_drift_correction_gap.md) · [agent shared_prompt fan-out](project_spawn_agent_shared_prompt_fix.md) · [document-write disk-flush](project_edit_file_document_write_disk_undo_fix.md).
- [read_document extract-once (unpushed)](project_document_extract_once_indexed_read.md) · [chat attachments + ~25 UI fixes (unpushed)](project_chat_file_document_attachments.md) · [✅ web_fetch timing debug stripped](project_web_fetch_timing_debug.md).

### Open work / TODO
- [SHIPPED (unpushed) whole-plugin audit + remediation](project_audit_remediation_campaign.md) — 6 P2 open. ⚠ automation:F-11 HARMFUL.
- [READY: write-ops UI-parity audit](project_write_ops_ux_audit.md) — 2 P0, 9 P1; [READY: debug tools audit](project_debug_tools_audit_plan.md) C1-C7+D1-D8; [READY: attempt_completion redesign](project_attempt_completion_redesign.md).
- [BUGS: mid-session state resets](project_api_debug_and_stats_reset_bugs.md) — api-debug overwrite + token/USD snap-back; [BLOCKED: History tab](project_history_tab_broken.md) — 7 breakages.
- [⚠ TODO: tool-docs cleanup PRs](project_tool_docs_cleanup_prs.md) — one-by-one, NEVER batch; [ACTIVE: Tool Documentation Initiative](project_tool_documentation_initiative.md).
- [TODO: Automation baseline picker UI](project_baseline_swap_dropdown.md); [TODO: active-ticket multi-repo chip](project_active_ticket_multi_repo_chip.md) — needs Bitbucket branch-search; [QUEUED: invokeFolderResolver missing Continuation](project_maven_folder_resolver_continuation_bug.md).
- [Loop exit 1/5 shipped](project_loop_exit_improvements.md) — remain: max-iter dump, model fallback, cancel revert, format examples; [Phase 3 smoke status](project_phase3_smoke_test_status.md).
- [Missing meta-tool actions](project_missing_meta_tool_actions.md) — Jira/Bamboo downloads; [TODO: wiring gaps](project_remaining_wiring_gaps.md); [TODO: tool count reduction](project_tool_count_reduction.md).

### Shipped (older, low-action)
- [SHIPPED+PUSHED: network resilience + retry-backoff](project_network_connectivity_resilience_shipped.md) — OFFLINE fail-fast; VPN smoke pending; [SHIPPED: cross-IDE delegation 3/3.1/4](project_cross_ide_plans_3_3_1_4_shipped.md).
- [RELEASED: read_document robustness](project_read_document_pdf_fixes_shipped.md) — ⚠ test real doc + nav-UX; [SHIPPED v1.1: binary document reader](project_binary_document_reader_research.md).
- [SHIPPED: tool feedback fix bundle](project_tool_feedback_fixes_shipped.md) · [edit_file streaming preview](project_edit_file_streaming_preview_shipped.md) · [Anthropic-Vertex prefill fix](project_anthropic_vertex_assistant_prefill_rejection.md) · [steering queue pre-exit drain](project_steering_queue_exit_drain.md).
- [SHIPPED: two-tier context mgmt](project_context_management_redesign.md) — addNudgeMessage for synthetic nudges; [SHIPPED: BrainRouter two-step removed](project_brainrouter_simplification_shipped.md); [ACTIVE: image-attach pipeline](project_image_attach_pipeline.md).
- API audits: [Jira+Bitbucket](project_api_audit_in_progress.md) · [Bitbucket DC 9.4.16](project_bitbucket_version_probe_findings.md) · [Bamboo probe](project_bamboo_audit_in_progress.md) · [Nexus deferred](project_nexus_version_probe_findings.md) · [Sonar 25.x](project_sonar_25x_integration_shipped.md) · [Bamboo write-path](project_bamboo_write_path_lessons.md) · [Bamboo API probe](project_bamboo_api_probe_findings.md).
- [SHIPPED v0.83.14-beta: PR Review Workflow](project_pr_review_workflow.md) — Phase 5 polish pending; [RichInput custom undo stack](project_rich_input_custom_undo_stack.md) — don't revert to native.

### Reference plans / older roadmaps
- Dev rules: [workflow sequence](project_workflow_sequence.md) · [copyright rules](project_copyright_rules.md) · [Sourcegraph Cody Enterprise API](project_cody_enterprise_only.md) · [agent branch strategy](project_agent_branch_strategy.md).
- UI plans: [chat UI overhaul](project_chat_ui_overhaul.md) · [UI/UX audit (on hold)](project_ui_audit_status.md) · [deferred UI refactors](project_deferred_ui_refactors.md) · [streaming lab](project_streaming_lab.md) · [streaming UI scheduler](project_streaming_ui_phase.md).
- Tooling plans: [tool consolidation P1 done](project_tool_consolidation_plan.md) · [new tools plan](project_new_tools_plan.md) · [missing debug features](project_missing_debug_tool_features.md) · [critical fixes plan](project_critical_fixes_plan.md).
- Future ideas: [deferred features](project_deferred_features.md) · [mention shortcuts](project_mention_shortcuts_planned.md) · [prompt suggestion](project_prompt_suggestion_future.md) · [active ticket visibility](project_active_ticket_visibility.md) · [phase3 agentic research](project_phase3_agentic_ai_research.md).

## User
- [Platform](user_platform.md) — macOS dev, Windows testing.

## Reference
- [Cline DiffViewProvider streaming](reference_cline_diff_view_provider_streaming.md) — separate diff editor, 100ms throttle.
- [Sub-agent control & rollback](reference_subagent_control_rollback_background.md) · [Cline sub-agent architecture](reference_cline_subagent_task_delegation.md) — 5 parallel + new_task; [Multi-agent delegation frameworks](reference_multi_agent_delegation_frameworks.md).
- [Context management deep analysis](reference_context_management_deep_analysis.md) — 5-tier budget + 6-stage; [Agent loop exit triggers](reference_agent_loop_exit_triggers_comparison.md); [Agent system prompt structure](reference_agent_system_prompt_structure.md); [Skill injection](reference_skill_injection_patterns.md).
- Sourcegraph: [HTTP APIs](reference_sourcegraph_http_apis.md) · [image transport](reference_sourcegraph_image_transport.md) · [internal OpenAPI](reference_sourcegraph_internal_api_full_inventory.md) · [Cody JSON-RPC methods](reference_cody_sourcegraph_methods.md).
- [JCEF implementation](reference_jcef_implementation.md) · [Performance best practices](reference_performance_best_practices.md) · [JVM shell execution](reference_jvm_shell_execution_libraries.md) · [Approval gate UI components](reference_approval_gate_ui_components.md) · [IPC cross-instance (UDS/JEP 380)](reference_ipc_cross_instance_research.md).
- [SonarQube branch + CE API](reference_sonarqube_branch_newcode_ce.md) · [Nexus 3 URL conventions](reference_nexus3_url_conventions.md) · [Bitbucket PR comment API](reference_bitbucket_pr_comment_api.md) · [READY: PR-review Phase 0 audit](reference_bitbucket_tools_audit_result.md).
