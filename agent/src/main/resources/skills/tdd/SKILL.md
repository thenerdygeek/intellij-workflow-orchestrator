---
name: tdd
description: Disciplined red-green-refactor TDD workflow that writes the test first from requirements, watches it fail to confirm it tests the right thing, writes minimal production code to make it pass, then refactors with confidence. Use this when the user asks for test-driven development or test-first approaches — trigger phrases include "TDD", "test first", "test-driven", "write tests for", "add tests", "test this", and "cover this with tests". Also use this when adding new functionality that needs test coverage or when fixing bugs that should have regression tests to prevent recurrence. Do not use this for exploratory changes where the requirements are still unclear, or when the user explicitly says they will write tests later. For example, if the user says "Write tests for the new service method", "Add test coverage for the parser", or "Fix this bug with a regression test", load this skill first. It enforces a disciplined cycle where tests are written from requirements and specifications rather than from the implementation, ensuring they actually validate intended behavior and catch real regressions instead of just mirroring what the code already does.
preferred-tools: [read_file, edit_file, search_code, run_command, diagnostics, think, find_definition, find_references]
---

# Test-Driven Development (TDD)

## Overview

Write the test first. Watch it fail. Write minimal code to pass.

**Core principle:** If you didn't watch the test fail, you don't know if it tests the right thing.

**Violating the letter of the rules is violating the spirit of the rules.**

## When to Use

**Always:**
- New features
- Bug fixes
- Refactoring
- Behavior changes

**Exceptions (ask the user):**
- Throwaway prototypes
- Generated code
- Configuration files

Thinking "skip TDD just this once"? Stop. That's rationalization.

## The Iron Law

```
NO PRODUCTION CODE WITHOUT A FAILING TEST FIRST
```

Write code before the test? Delete it. Start over.

**No exceptions:**
- Don't keep it as "reference"
- Don't "adapt" it while writing tests
- Don't look at it
- Delete means delete

Implement fresh from tests. Period.

## Red-Green-Refactor

### RED — Write Failing Test

Write one minimal test showing what should happen.

<Good>
```kotlin
@Test
fun `retries failed operations 3 times`() = runTest {
    var attempts = 0
    val operation = suspend {
        attempts++
        if (attempts < 3) throw IOException("fail")
        "success"
    }

    val result = retryOperation(operation)

    assertEquals("success", result)
    assertEquals(3, attempts)
}
```
Clear name, tests real behavior, one thing
</Good>

<Bad>
```kotlin
@Test
fun `retry works`() = runTest {
    val mock = mockk<suspend () -> String>()
    coEvery { mock() } throws IOException() andThen throws IOException() andThen returns "success"
    retryOperation(mock)
    coVerify(exactly = 3) { mock() }
}
```
Vague name, tests mock not code
</Bad>

**Requirements:**
- One behavior
- Clear name
- Real code (no mocks unless unavoidable)

### Verify RED — Watch It Fail

**MANDATORY. Never skip.**

```bash
./gradlew :module:test --tests "*.RetryTest.retries failed operations 3 times"
```

Confirm:
- Test fails (not errors)
- Failure message is expected
- Fails because feature missing (not typos)

**Test passes?** You're testing existing behavior. Fix test.

**Test errors?** Fix error, re-run until it fails correctly.

### GREEN — Minimal Code

Write simplest code to pass the test.

<Good>
```kotlin
suspend fun <T> retryOperation(operation: suspend () -> T): T {
    repeat(2) {
        try { return operation() }
        catch (_: Exception) { /* retry */ }
    }
    return operation() // final attempt, let exception propagate
}
```
Just enough to pass
</Good>

<Bad>
```kotlin
suspend fun <T> retryOperation(
    operation: suspend () -> T,
    maxRetries: Int = 3,
    backoffStrategy: BackoffStrategy = ExponentialBackoff(),
    onRetry: (Int, Exception) -> Unit = { _, _ -> }
): T { /* YAGNI */ }
```
Over-engineered
</Bad>

Don't add features, refactor other code, or "improve" beyond the test.

### Verify GREEN — Watch It Pass

**MANDATORY.**

```bash
./gradlew :module:test --tests "*.RetryTest"
```

Confirm:
- Test passes
- Other tests still pass
- Output pristine (no errors, warnings)

**Test fails?** Fix code, not test.

**Other tests fail?** Fix now.

### REFACTOR — Clean Up

After green only:
- Remove duplication
- Improve names
- Extract helpers

Keep tests green. Don't add behavior.

### Repeat

Next failing test for next feature.

## Testing Strategies by Context

The right test approach depends on what you're testing. Let the LLM decide the appropriate level:

### Unit Tests (JUnit 5 + MockK)
For isolated business logic, utilities, data transformations.

```kotlin
@Test
fun `parses sprint board from API response`() {
    val response = JiraSprintResponse(/* ... */)
    val board = SprintParser.parse(response)
    assertEquals(3, board.issues.size)
    assertEquals("In Progress", board.issues[0].status)
}
```

Run: `./gradlew :module:test --tests "*.ParserTest"`

### Spring Boot Slice Tests
For testing specific layers in isolation when the user is building Spring applications.

**Controller layer** — `@WebMvcTest`:
```kotlin
@WebMvcTest(UserController::class)
class UserControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @MockkBean lateinit var userService: UserService

    @Test
    fun `returns user by ID`() {
        every { userService.findById(1L) } returns User(1L, "Alice")

        mockMvc.get("/api/users/1")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.name") { value("Alice") } }
    }
}
```

**Repository layer** — `@DataJpaTest`:
```kotlin
@DataJpaTest
class UserRepositoryTest {
    @Autowired lateinit var repository: UserRepository

    @Test
    fun `finds users by email domain`() {
        repository.save(User(name = "Alice", email = "alice@corp.com"))
        repository.save(User(name = "Bob", email = "bob@other.com"))

        val result = repository.findByEmailContaining("@corp.com")
        assertEquals(1, result.size)
        assertEquals("Alice", result[0].name)
    }
}
```

### Integration Tests
For testing the full stack when components interact.

**Full application** — `@SpringBootTest`:
```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserApiIntegrationTest {
    @Autowired lateinit var restTemplate: TestRestTemplate

    @Test
    fun `creates and retrieves user end-to-end`() {
        val created = restTemplate.postForEntity(
            "/api/users",
            CreateUserRequest("Alice", "alice@test.com"),
            UserResponse::class.java
        )
        assertEquals(HttpStatus.CREATED, created.statusCode)

        val fetched = restTemplate.getForEntity(
            "/api/users/${created.body!!.id}",
            UserResponse::class.java
        )
        assertEquals("Alice", fetched.body!!.name)
    }
}
```

### IntelliJ Plugin Tests
For our plugin code specifically.

```kotlin
class SkillToolTest {
    private lateinit var project: Project
    private lateinit var agentService: AgentService

    @BeforeEach
    fun setUp() {
        project = mockk<Project>(relaxed = true)
        agentService = mockk<AgentService>(relaxed = true)
        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } returns agentService
    }

    @Test
    fun `returns full skill content on success`() = runTest {
        // ... setup mocks ...
        val result = tool.execute(params, project)
        assertFalse(result.isError)
        assertEquals(expectedContent, result.content)
    }
}
```

Run: `./gradlew :agent:test --tests "*.SkillToolTest"`

## Good Tests

| Quality | Good | Bad |
|---------|------|-----|
| **Minimal** | One thing. "and" in name? Split it. | `test validates email and domain and whitespace` |
| **Clear** | Name describes behavior | `test1` |
| **Shows intent** | Demonstrates desired API | Obscures what code should do |

## Why Order Matters

**"I'll write tests after to verify it works"**

Tests written after code pass immediately. Passing immediately proves nothing:
- Might test wrong thing
- Might test implementation, not behavior
- Might miss edge cases you forgot
- You never saw it catch the bug

Test-first forces you to see the test fail, proving it actually tests something.

**"Deleting X hours of work is wasteful"**

Sunk cost fallacy. The time is already gone. Your choice now:
- Delete and rewrite with TDD (X more hours, high confidence)
- Keep it and add tests after (30 min, low confidence, likely bugs)

The "waste" is keeping code you can't trust.

## Common Rationalizations

| Excuse | Reality |
|--------|---------|
| "Too simple to test" | Simple code breaks. Test takes 30 seconds. |
| "I'll test after" | Tests passing immediately prove nothing. |
| "Tests after achieve same goals" | Tests-after = "what does this do?" Tests-first = "what should this do?" |
| "Need to explore first" | Fine. Throw away exploration, start with TDD. |
| "Test hard = design unclear" | Listen to test. Hard to test = hard to use. |
| "TDD will slow me down" | TDD faster than debugging. |
| "Existing code has no tests" | You're improving it. Add tests for what you touch. |

## Red Flags — STOP and Start Over

- Code before test
- Test after implementation
- Test passes immediately
- Can't explain why test failed
- Tests added "later"
- Rationalizing "just this once"
- "Keep as reference" or "adapt existing code"

**All of these mean: Delete code. Start over with TDD.**

## Testing Anti-Patterns

When adding mocks or test utilities, avoid:
- **Testing mock behavior** instead of real behavior — if asserting on mock elements, you're testing the mock
- **Test-only methods in production classes** — put cleanup/helpers in test utilities instead
- **Mocking without understanding** — understand dependencies before mocking; mock at the right level
- **Incomplete mocks** — mirror real data structures completely, not just fields you think you need
- **Integration tests as afterthought** — testing is part of implementation, not optional follow-up

**Core rule:** Test what the code does, not what the mocks do.

## Verification Checklist

Before marking work complete:

- [ ] Every new function/method has a test
- [ ] Watched each test fail before implementing
- [ ] Each test failed for expected reason (feature missing, not typo)
- [ ] Wrote minimal code to pass each test
- [ ] All tests pass
- [ ] Output pristine (no errors, warnings)
- [ ] Tests use real code (mocks only if unavoidable)
- [ ] Edge cases and errors covered

Can't check all boxes? You skipped TDD. Start over.

## Final Rule

```
Production code → test exists and failed first
Otherwise → not TDD
```

No exceptions without the user's permission.
