---
name: tdd
description: Disciplined red-green-refactor TDD workflow that writes the test first from requirements, watches it fail to confirm it tests the right thing, writes minimal production code to make it pass, then refactors with confidence. Use this when the user asks for test-driven development or test-first approaches — trigger phrases include "TDD", "test first", "test-driven", "write tests for", "add tests", "test this", and "cover this with tests". Also use this when adding new functionality that needs test coverage or when fixing bugs that should have regression tests to prevent recurrence. Do not use this for exploratory changes where the requirements are still unclear, or when the user explicitly says they will write tests later. For example, if the user says "Write tests for the new service method", "Add test coverage for the parser", or "Fix this bug with a regression test", load this skill first. It enforces a disciplined cycle where tests are written from requirements and specifications rather than from the implementation, ensuring they actually validate intended behavior and catch real regressions instead of just mirroring what the code already does.
user-invocable: true
preferred-tools: [read_file, edit_file, search_code, run_command, runtime_exec, diagnostics, think, find_definition, find_references]
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

## Agent-Specific TDD Rules

AI agents fail at TDD in specific, predictable ways. These rules prevent the most common failures:

1. **Write tests from requirements, NOT from implementation.** Do NOT read the implementation file before writing test assertions. Read the spec, ticket, or user request. The test defines what the code SHOULD do, not what it DOES do.

2. **Context isolation.** Open the test file first. Write all assertions. Only THEN open implementation files. If you read the implementation first, you'll unconsciously mirror it in your tests.

3. **Tests are the spec.** Without test constraints, you will over-engineer. The test defines the exact boundary of what to build — nothing more.

4. **One test at a time.** Write one test, watch it fail, make it pass. Do NOT generate all tests upfront. Each RED-GREEN cycle teaches you something about the design.

5. **Unit tests in the agent loop, integration tests at the end.** Fast unit tests give immediate RED-GREEN feedback. Run integration tests after all unit tests pass.

## Outside-In TDD for REST Endpoints

For creating or modifying REST endpoints, use outside-in (double-loop) TDD:

```
Outer loop: Acceptance test defining full behavior (stays RED until all layers are done)
  Inner loop 1: Controller slice test → implement controller (RED → GREEN)
  Inner loop 2: Service unit test → implement service (RED → GREEN)
  Inner loop 3: Repository test → implement repository (RED → GREEN)
Outer loop: Acceptance test turns GREEN
```

### Step-by-step workflow:

1. **Define the API contract** — create request/response DTOs as data classes
2. **Write the acceptance test** (`@SpringBootTest`) — full end-to-end behavior
3. **Run it — RED** (endpoint doesn't exist)
4. **Write controller slice test** (`@WebMvcTest`) — request mapping, status codes, response structure
5. **Implement controller** — minimal, delegates to service
6. **Write service unit test** — business logic, validation
7. **Implement service** — minimal code to pass
8. **Write repository test** (`@DataJpaTest`) — only if custom queries are needed
9. **Implement repository**
10. **Run acceptance test — GREEN** (full stack works)
11. **Refactor**

### CRUD TDD Checklist

When TDD-ing a CRUD endpoint, cover these scenarios:

**CREATE (POST):**
- Valid request → 201 + response body with generated ID
- Missing required field → 400 + field-level error
- Invalid data (bad format, out of range) → 400
- Duplicate (if uniqueness constraint) → 409

**READ (GET one):**
- Existing entity → 200 + full response body
- Non-existent ID → 404

**READ (GET list):**
- Returns list → 200 + array (may be empty)
- Pagination params work correctly

**UPDATE (PUT/PATCH):**
- Valid update → 200 + updated response body
- Non-existent ID → 404
- Invalid data → 400

**DELETE:**
- Existing entity → 204 (no body)
- Non-existent ID → 404
- Verify GET after delete → 404

### Spring Boot Test Annotation Decision Tree

| Testing | Annotation | Speed | Mocks |
|---------|-----------|-------|-------|
| Controller (request/response mapping) | `@WebMvcTest(FooController::class)` | Fast | `@MockkBean` for service |
| Service (business logic) | `@ExtendWith(MockKExtension::class)` | Very fast | `mockk<Repository>()` |
| Repository (custom queries) | `@DataJpaTest` | Medium | Real H2 or Testcontainers |
| Full stack (end-to-end) | `@SpringBootTest(RANDOM_PORT)` | Slow | Real everything |
| Security (auth/authz rules) | `@WebMvcTest` + `@WithMockUser` | Fast | Mock user with roles |

**Default to the fastest annotation that covers what you're testing.** Don't use `@SpringBootTest` when `@WebMvcTest` suffices.

## Kotlin Test Data Factories

Kotlin eliminates the need for Java-style Builder classes. Use factory functions with default parameters:

```kotlin
// src/test/kotlin/com/example/TestFixtures.kt
object TestFixtures {
    fun user(
        id: Long = 1L,
        name: String = "Alice",
        email: String = "alice@test.com",
        role: Role = Role.USER
    ) = User(id, name, email, role)

    fun createUserRequest(
        name: String = "Alice",
        email: String = "alice@test.com"
    ) = CreateUserRequest(name, email)
}
```

Usage — override only what matters for THIS test:
```kotlin
val admin = TestFixtures.user(role = Role.ADMIN)
val noEmail = TestFixtures.user(email = "")
val customName = TestFixtures.user().copy(name = "Bob")
```

**Rules:**
- One factory per entity, vary with named parameters
- Each test sets up exactly the data it needs (no shared mutable state)
- Define test data as code, not SQL or JSON files
- For complex object graphs, compose: `order(user = user(role = Role.ADMIN))`

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

Use `runtime_exec` for structured results (activate with `tool_search(query="runtime")` first):
```
runtime_exec(action="run_tests", class_name="com.example.RetryTest", method="retries failed operations 3 times")
```

Or via shell if `runtime_exec` is not available:
```
run_command(command="./gradlew :module:test --tests '*.RetryTest.retries failed operations 3 times'")
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

```
runtime_exec(action="run_tests", class_name="com.example.RetryTest")
```

Then check results:
```
runtime_exec(action="get_test_results", config_name="RetryTest", status_filter="FAILED")
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

Run: `runtime_exec(action="run_tests", class_name="com.example.ParserTest")`

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

Run: `runtime_exec(action="run_tests", class_name="com.workflow.orchestrator.agent.tools.builtin.SkillToolTest")`

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

The patterns AI agents fall into most often (ranked by risk):

| Anti-Pattern | Agent Risk | What Goes Wrong | Fix |
|-------------|-----------|-----------------|-----|
| **The Mockery** | **HIGHEST** | Mocking everything, asserting on mock call counts instead of real outcomes | Mock only external systems; assert on returned values and state changes |
| **Testing implementation** | **HIGH** | Tests break when you refactor internals, even if behavior is unchanged | Test observable behavior and public contracts only |
| **The Liar** | **HIGH** | Tests pass without actually verifying the intended thing | Always verify RED step; check the failure message makes sense |
| **The Giant** | **HIGH** | One test with many assertions testing multiple behaviors | One behavior per test; "and" in the name means split it |
| **Over-coverage** | **HIGH** | Pursuing 100% coverage instead of testing critical paths | Focus on business logic, edge cases, error paths |
| **Test-only methods** | **MEDIUM** | Adding getters/helpers in production classes just for tests | Test through public interfaces; put helpers in test utilities |

**Core rule:** Test what the code does, not what the mocks do. If you're asserting on `verify(exactly = 3) { mock() }`, you're testing the mock.

## Verification Checklist

Before marking work complete:

- [ ] Every new function/method has a test
- [ ] Watched each test fail before implementing
- [ ] Each test failed for expected reason (feature missing, not typo)
- [ ] Wrote minimal code to pass each test
- [ ] All tests pass: `runtime_exec(action="run_tests")`
- [ ] No compilation errors: `runtime_exec(action="compile_module")`
- [ ] No IDE warnings: `diagnostics` on changed files
- [ ] Tests use real code (mocks only if unavoidable)
- [ ] Edge cases and errors covered
- [ ] Coverage adequate (optional): `coverage(action="run_with_coverage")` on the test class

Can't check all boxes? You skipped TDD. Start over.

## Final Rule

```
Production code → test exists and failed first
Otherwise → not TDD
```

No exceptions without the user's permission.
