package com.workflow.orchestrator.agent.tools.docs

/**
 * Type-safe builder DSL for [ToolDocumentation].
 *
 * Usage on a tool class:
 * ```
 * override fun documentation(): ToolDocumentation = toolDoc("read_file") {
 *     summary {
 *         technical("Read file contents with line numbers")
 *         plain("Like opening a file in an editor — shows you the lines so you can refer to them later.")
 *     }
 *     whatLLMSees(description)  // pass the existing description string
 *     params {
 *         required("path", "string") {
 *             llmSeesIt("The path of the file to read…")
 *             humanReadable("Where the file lives — relative to the project or absolute.")
 *             whenPresent("File at the path is read.")
 *             example("src/main/kotlin/Foo.kt")
 *         }
 *         optional("offset", "integer") {
 *             llmSeesIt("…")
 *             humanReadable("Skip past the first N lines — handy for jumping to a known place.")
 *             whenPresent("Reading begins at this 1-based line number.")
 *             whenAbsent("Defaults to 1 — file is read from the top.")
 *             constraint("must be ≥ 1")
 *         }
 *     }
 *     verdict {
 *         keep("Core file reading is universally needed.", VerdictSeverity.STRONG)
 *     }
 *     related("search_code", Relationship.COMPLEMENT, "Search inside the file once it's read.")
 *     downside("Returns raw bytes for binary files — caller must check the extension first.")
 *     narrative("read_file")  // loads agent/src/main/resources/tool-docs/read_file.md
 * }
 * ```
 */
@DslMarker
annotation class ToolDocDsl

fun toolDoc(toolName: String, build: ToolDocBuilder.() -> Unit): ToolDocumentation =
    ToolDocBuilder(toolName).apply(build).build()

@ToolDocDsl
class ToolDocBuilder internal constructor(private val toolName: String) {
    private var summary: ToolSummary? = null
    private var whatLLMSees: String = ""
    private var sideEffect: SideEffectKind? = null
    private var actions: MutableList<ActionDoc>? = null
    private var singleParams: ParamGroup? = null
    private var toolVerdict: Verdict = Verdict()
    private var counterfactual: String? = null
    private val commonLLMMistakes = mutableListOf<String>()
    private val auditNotes = mutableListOf<AuditNote>()
    private val relatedTools = mutableListOf<RelatedTool>()
    private var flowchart: String? = null
    private val downsides = mutableListOf<String>()
    private var narrativeResource: String? = null

    fun summary(build: SummaryBuilder.() -> Unit) {
        summary = SummaryBuilder().apply(build).build()
    }

    fun whatLLMSees(description: String) {
        whatLLMSees = description.trimIndent()
    }

    fun sideEffect(kind: SideEffectKind) {
        sideEffect = kind
    }

    fun counterfactual(text: String) {
        counterfactual = text.trim()
    }

    fun llmMistake(text: String) {
        commonLLMMistakes.add(text.trim())
    }

    fun actions(build: ActionsBuilder.() -> Unit) {
        actions = ActionsBuilder().apply(build).build().toMutableList()
    }

    fun params(build: ParamGroupBuilder.() -> Unit) {
        singleParams = ParamGroupBuilder().apply(build).build()
    }

    fun verdict(build: VerdictBuilder.() -> Unit) {
        toolVerdict = VerdictBuilder().apply(build).build()
    }

    fun mergeOpportunity(text: String) {
        auditNotes.add(AuditNote(AuditKind.MERGE_OPPORTUNITY, text))
    }

    fun removableParam(text: String) {
        auditNotes.add(AuditNote(AuditKind.REMOVABLE_PARAM, text))
    }

    fun observation(text: String) {
        auditNotes.add(AuditNote(AuditKind.OBSERVATION, text))
    }

    fun deprecation(text: String) {
        auditNotes.add(AuditNote(AuditKind.DEPRECATION, text))
    }

    fun related(toolName: String, relationship: Relationship, note: String) {
        relatedTools.add(RelatedTool(toolName, relationship, note))
    }

    fun flowchart(mermaidSource: String) {
        flowchart = mermaidSource.trimIndent()
    }

    fun downside(text: String) {
        downsides.add(text)
    }

    fun narrative(resourceName: String) {
        narrativeResource = resourceName
    }

    internal fun build(): ToolDocumentation {
        val resolvedSummary = summary
            ?: error("toolDoc('$toolName') must declare summary { technical(...); plain(...) }")
        val resolvedSideEffect = sideEffect
            ?: error("toolDoc('$toolName') must declare sideEffect(SideEffectKind.X) — this drives the blast-radius chip and the Compare Tools filter")
        return ToolDocumentation(
            toolName = toolName,
            summary = resolvedSummary,
            whatLLMSees = whatLLMSees,
            sideEffect = resolvedSideEffect,
            actions = actions,
            singleActionParams = singleParams,
            toolVerdict = toolVerdict,
            counterfactual = counterfactual,
            commonLLMMistakes = commonLLMMistakes.toList(),
            auditNotes = auditNotes.toList(),
            relatedTools = relatedTools.toList(),
            flowchart = flowchart,
            downsides = downsides.toList(),
            narrativeResource = narrativeResource,
        )
    }
}

@ToolDocDsl
class SummaryBuilder internal constructor() {
    private var technical: String? = null
    private var plain: String? = null

    fun technical(text: String) { technical = text.trim() }
    fun plain(text: String) { plain = text.trim() }

    internal fun build(): ToolSummary {
        val t = technical ?: error("summary must include technical(\"...\")")
        val p = plain ?: error("summary must include plain(\"...\")")
        return ToolSummary(t, p)
    }
}

@ToolDocDsl
class ActionsBuilder internal constructor() {
    private val list = mutableListOf<ActionDoc>()

    fun action(name: String, build: ActionBuilder.() -> Unit) {
        list.add(ActionBuilder(name).apply(build).build())
    }

    internal fun build(): List<ActionDoc> = list.toList()
}

@ToolDocDsl
class ActionBuilder internal constructor(private val name: String) {
    private var description: ToolSummary? = null
    private var whenLLMUses: String = ""
    private var paramGroup: ParamGroup = ParamGroup()
    private val rejectedParams = mutableListOf<RejectedParam>()
    private val preconditions = mutableListOf<String>()
    private var onSuccess: String = ""
    private val onFailure = mutableListOf<FailureMode>()
    private val examples = mutableListOf<ToolExample>()
    private var flowchart: String? = null
    private var verdict: Verdict = Verdict()

    fun description(build: SummaryBuilder.() -> Unit) {
        description = SummaryBuilder().apply(build).build()
    }

    fun whenLLMUses(text: String) { whenLLMUses = text.trim() }

    fun params(build: ParamGroupBuilder.() -> Unit) {
        paramGroup = ParamGroupBuilder().apply(build).build()
    }

    fun rejectsParam(name: String, reason: String) {
        rejectedParams.add(RejectedParam(name, reason))
    }

    fun precondition(text: String) {
        preconditions.add(text)
    }

    fun onSuccess(text: String) { onSuccess = text.trim() }

    fun onFailure(condition: String, response: String) {
        onFailure.add(FailureMode(condition, response))
    }

    fun example(title: String, build: ExampleBuilder.() -> Unit) {
        examples.add(ExampleBuilder(title).apply(build).build())
    }

    fun flowchart(mermaidSource: String) {
        flowchart = mermaidSource.trimIndent()
    }

    fun verdict(build: VerdictBuilder.() -> Unit) {
        verdict = VerdictBuilder().apply(build).build()
    }

    internal fun build(): ActionDoc {
        val d = description ?: error("action('$name') must declare description { ... }")
        require(onSuccess.isNotBlank()) { "action('$name') must declare onSuccess(\"...\")" }
        return ActionDoc(
            name = name,
            description = d,
            whenLLMUses = whenLLMUses,
            requiredParams = paramGroup.required,
            optionalParams = paramGroup.optional,
            rejectedParams = rejectedParams.toList(),
            preconditions = preconditions.toList(),
            onSuccess = onSuccess,
            onFailure = onFailure.toList(),
            examples = examples.toList(),
            flowchart = flowchart,
            verdict = verdict,
        )
    }
}

@ToolDocDsl
class ExampleBuilder internal constructor(private val title: String) {
    private val params = mutableMapOf<String, String>()
    private var outcome: String = ""
    private var notes: String? = null

    fun param(name: String, value: String) {
        params[name] = value
    }

    fun outcome(text: String) { outcome = text.trim() }
    fun notes(text: String) { notes = text.trim() }

    internal fun build(): ToolExample {
        require(outcome.isNotBlank()) { "example('$title') must declare outcome(\"...\")" }
        return ToolExample(title, params.toMap(), outcome, notes)
    }
}

@ToolDocDsl
class ParamGroupBuilder internal constructor() {
    private val required = mutableListOf<ParamDoc>()
    private val optional = mutableListOf<ParamDoc>()

    fun required(name: String, type: String, build: ParamDocBuilder.() -> Unit) {
        required.add(ParamDocBuilder(name, type, isRequired = true).apply(build).build())
    }

    fun optional(name: String, type: String, build: ParamDocBuilder.() -> Unit) {
        optional.add(ParamDocBuilder(name, type, isRequired = false).apply(build).build())
    }

    internal fun build(): ParamGroup = ParamGroup(required.toList(), optional.toList())
}

@ToolDocDsl
class ParamDocBuilder internal constructor(
    private val name: String,
    private val type: String,
    private val isRequired: Boolean,
) {
    private var llmSees: String = ""
    private var humanReadable: String = ""
    private var whenPresent: String = ""
    private var whenAbsent: String? = null
    private val constraints = mutableListOf<String>()
    private val examples = mutableListOf<String>()
    private val enumValues = mutableListOf<String>()

    fun llmSeesIt(text: String) { llmSees = text.trim() }
    fun humanReadable(text: String) { humanReadable = text.trim() }
    fun whenPresent(text: String) { whenPresent = text.trim() }
    fun whenAbsent(text: String) { whenAbsent = text.trim() }
    fun constraint(text: String) { constraints.add(text) }
    fun example(value: String) { examples.add(value) }
    fun enumValue(vararg values: String) { enumValues.addAll(values) }

    internal fun build(): ParamDoc {
        require(llmSees.isNotBlank()) { "param '$name' must declare llmSeesIt(\"...\")" }
        require(humanReadable.isNotBlank()) { "param '$name' must declare humanReadable(\"...\")" }
        require(whenPresent.isNotBlank()) { "param '$name' must declare whenPresent(\"...\")" }
        if (!isRequired && whenAbsent == null) {
            error("optional param '$name' must declare whenAbsent(\"...\")")
        }
        return ParamDoc(
            name = name,
            type = type,
            descriptionLLM = llmSees,
            descriptionHuman = humanReadable,
            whenPresent = whenPresent,
            whenAbsent = whenAbsent,
            constraints = constraints.toList(),
            examples = examples.toList(),
            enumValues = enumValues.toList(),
        )
    }
}

@ToolDocDsl
class VerdictBuilder internal constructor() {
    private var keep: VerdictReason? = null
    private var drop: VerdictReason? = null

    fun keep(reasoning: String, severity: VerdictSeverity = VerdictSeverity.NORMAL) {
        keep = VerdictReason(reasoning.trim(), severity)
    }

    fun drop(reasoning: String, severity: VerdictSeverity = VerdictSeverity.NORMAL) {
        drop = VerdictReason(reasoning.trim(), severity)
    }

    internal fun build(): Verdict = Verdict(keep, drop)
}
