package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import java.io.File

/**
 * Groups all per-session state into a single immutable snapshot.
 *
 * Today the various managers (plan, question, skill, ledger, etc.) are stored as
 * individual @Volatile fields on [com.workflow.orchestrator.agent.AgentService].
 * This data class is the first step toward replacing those scattered fields with
 * a cohesive object that can be passed around, tested, and swapped atomically.
 *
 * **Migration plan:** AgentService gets `@Volatile var activeScope: SessionScope?`
 * alongside the old fields. New code reads from SessionScope; old code still reads
 * from the individual fields. Once all consumers are migrated, the individual
 * fields are removed.
 */
data class SessionScope(
    val sessionId: String,
    val sessionDir: File,
    val bridge: EventSourcedContextBridge,
    val planManager: PlanManager,
    val questionManager: QuestionManager,
    val skillManager: SkillManager,
    val changeLedger: ChangeLedger,
    val rollbackManager: AgentRollbackManager,
    val selfCorrectionGate: SelfCorrectionGate,
    val backpressureGate: BackpressureGate,
    val completionGatekeeper: CompletionGatekeeper,
    val loopGuard: LoopGuard,
    val fileOwnership: FileOwnershipRegistry,
    val workerMessageBus: WorkerMessageBus,
    val metrics: AgentMetrics,
    val uiCallbacks: UiCallbacks?
)
