---
name: research
description: Spawn the research sub-agent for thorough external research on a topic. Forwards the topic to a dedicated sub-agent with web_fetch / web_search tools that compiles a sourced markdown report into the project's research folder. Use when the user asks to research, investigate, or look up information about a topic — trigger phrases include "research", "look up", "find out about", "investigate", "what is", or any request for external information gathering.
user-invocable: true
preferred-tools: [agent, read_file]
---

The user wants thorough external research on the following topic:

$ARGUMENTS

Call the `agent` tool now with these arguments:
- `agent_type`: `research`
- `prompt`: the topic above (verbatim from `$ARGUMENTS`)
- `description`: a 3-5 word summary of the topic

When the sub-agent returns, summarise its dump file path back to the user and offer to `read_file` it if they want the findings inline.
