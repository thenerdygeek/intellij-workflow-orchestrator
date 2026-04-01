# :cody Module (DEPRECATED)

Former Cody CLI agent module. The JSON-RPC infrastructure has been removed.
LLM calls now use direct Sourcegraph HTTP API via `LlmBrainFactory` in `:core`.

## Remaining Files

Only rewired UI actions remain:

- `CodyIntentionAction` -- Alt+Enter quick fix, redirects to agent chat via `AgentChatRedirect`
- `CodyTestGenerator` -- "Generate Test" gutter action, redirects to agent chat via `AgentChatRedirect`
- `GenerateCommitMessageAction` -- VCS toolbar action, uses `LlmBrainFactory` for commit messages
- `PsiContextEnricher` -- PSI-based code intelligence used by commit message generation
