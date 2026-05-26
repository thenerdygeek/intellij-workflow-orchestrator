package com.workflow.orchestrator.agent.link

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.util.ValidatedPath

/**
 * Resolves `symbol:` chat hyperlinks to a concrete file location.
 *
 * Language-agnostic: delegates to every registered [com.workflow.orchestrator.agent.ide.LanguageIntelligenceProvider]
 * through [LanguageProviderRegistry] (Java/Kotlin AND Python), rather than calling
 * `JavaPsiFacade` directly. This is why the resolver lives in `:agent` (where the
 * registry is owned by `AgentService`) and not in `:core` alongside the other link
 * resolvers. A `symbol:` href that no provider can resolve returns null, and the
 * webview scanner strips the href so the mention degrades to plain text.
 *
 * Link forms (the agent is instructed to emit fully-qualified names):
 *  - `symbol:com.example.Foo`        → the type Foo
 *  - `symbol:com.example.Foo#member` → a method or field named `member` on Foo
 *
 * Member queries use the simple class name (`Foo.member`): both providers honour a
 * 2-part `Class.member` form — `JavaKotlinProvider` splits on `#` or `.`, `PythonProvider`
 * on `.` — but neither recognises a full FQN with a member suffix. If the member misses
 * we fall back to navigating to the enclosing class so the link still lands somewhere useful.
 */
class SymbolLinkResolver(
    private val project: Project,
    private val registry: LanguageProviderRegistry,
) {

    suspend fun resolveAll(hrefs: List<String>): List<ValidatedPath> =
        hrefs.mapNotNull { resolve(it) }

    suspend fun resolve(href: String): ValidatedPath? {
        val fqn = href.removePrefix("symbol:")
        if (fqn.isBlank()) return null

        val classFqn = fqn.substringBefore('#')
        val member = if ('#' in fqn) fqn.substringAfter('#').takeIf { it.isNotBlank() } else null
        val simpleClass = classFqn.substringAfterLast('.')

        val element: PsiElement = readAction {
            val providers = registry.allProviders()
            if (providers.isEmpty()) return@readAction null

            if (member != null) {
                providers.firstNotNullOfOrNull { it.findSymbol(project, "$simpleClass.$member") }
                    ?: providers.firstNotNullOfOrNull { it.findSymbol(project, classFqn) }
                    ?: simpleClass.takeIf { it != classFqn }
                        ?.let { s -> providers.firstNotNullOfOrNull { it.findSymbol(project, s) } }
            } else {
                providers.firstNotNullOfOrNull { it.findSymbol(project, classFqn) }
                    ?: simpleClass.takeIf { it != classFqn }
                        ?.let { s -> providers.firstNotNullOfOrNull { it.findSymbol(project, s) } }
            }
        } ?: return null

        return readAction {
            val containingFile = element.containingFile ?: return@readAction null
            val vFile = containingFile.virtualFile ?: return@readAction null
            val doc = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                ?: return@readAction null
            val line = doc.getLineNumber(element.textOffset)
            val col = element.textOffset - doc.getLineStartOffset(line)
            ValidatedPath(
                input = href,
                canonicalPath = vFile.path,
                line = line,
                column = col,
            )
        }
    }
}
