---
name: TDD means testing requirements, not implementation
description: Write tests from the SPEC/REQUIREMENTS, not from the code. Tests should describe what the system must do, not verify what the code does. Run tests before looking at implementation. Failing tests reveal bugs; passing tests that mirror implementation reveal nothing.
type: feedback
---

TDD means testing requirements, not implementation. Write test assertions from the spec FIRST, run them, watch them fail, THEN fix the code.

**Why:** In the context management redesign, 21 "TDD" tests all passed but missed 3 critical bugs (15 message injection bypasses, missing anchors in condenser output, dual compression). The tests passed because they tested individual components in isolation, confirming the implementation works as written — not that it meets the requirements.

**How to apply:** For any multi-component system:
1. Write an end-to-end scenario test FROM THE SPEC: "Given X setup, when Y happens, the output should contain Z"
2. Don't look at the implementation while writing assertions
3. Run the test. If it passes immediately, be suspicious — either the test is too weak or too coupled to implementation
4. A good TDD test for context management: "add 30 events including budget warnings, nudges, facts, guardrails → call getMessagesViaCondenser() → verify ALL messages appear in output" — this would have caught all 3 bugs
